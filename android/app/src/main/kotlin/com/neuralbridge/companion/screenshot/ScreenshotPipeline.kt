package com.neuralbridge.companion.screenshot

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.neuralbridge.companion.service.ScreenshotQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Screenshot Pipeline
 *
 * Captures screenshots using MediaProjection -> VirtualDisplay -> ImageReader.
 * Falls back to AccessibilityService.takeScreenshot() (API 30+) if MediaProjection is unavailable.
 *
 * Performance target: <60ms for 1080p JPEG encoding
 * - Capture: <30ms
 * - JPEG encode: <20ms (via JNI/libjpeg-turbo)
 * - TCP transfer: <10ms (localhost)
 *
 * Note: MediaProjection requires user consent dialog on first use.
 * On Android 14+, consent is single-use and must be re-requested after
 * app restart or device reboot.
 */
class ScreenshotPipeline(
    private val accessibilityService: AccessibilityService,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ScreenshotPipeline"
        private const val VIRTUAL_DISPLAY_NAME = "NeuralBridge-Screenshot"
        private const val IMAGE_READER_TIMEOUT_MS = 5000L
    }

    private val captureMutex = Mutex()

    // MediaProjection components — @Volatile for visibility across callback/capture threads
    @Volatile
    private var mediaProjection: MediaProjection? = null
    @Volatile
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile
    private var imageReader: ImageReader? = null

    // Listener notified when MediaProjection session dies
    var onMediaProjectionLost: (() -> Unit)? = null

    // Listener notified when MediaProjection is granted (to upgrade foreground service type)
    var onMediaProjectionGranted: (() -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection session stopped by system")
            releaseProjectionResources()
            onMediaProjectionLost?.invoke()
        }
    }

    /**
     * Read current display metrics fresh each time.
     * Avoids stale dimensions after device rotation.
     */
    private fun getDisplayMetrics(): DisplayMetrics {
        val windowManager = accessibilityService.getSystemService(WindowManager::class.java)
        return DisplayMetrics().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.currentWindowMetrics
                val bounds = metrics.bounds
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                densityDpi = accessibilityService.resources.displayMetrics.densityDpi
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(this)
            }
        }
    }

    /**
     * Check if MediaProjection permission is granted
     */
    fun hasMediaProjectionPermission(): Boolean {
        return mediaProjection != null
    }

    /**
     * Consume a pending consent result without launching the consent activity.
     *
     * Used by tryConsumeMediaProjectionConsent() after MainActivity.onResume() to avoid
     * re-launching ScreenshotConsentActivity when the service's polling loop already
     * stored the result but hasn't consumed it yet.
     *
     * @return true if a pending result was consumed and MediaProjection was registered
     */
    suspend fun tryConsumePendingConsent(): Boolean {
        val projection = ScreenshotConsentActivity.consumeMediaProjection() ?: return false
        return try {
            registerCallback(projection)
            mediaProjection = projection
            onMediaProjectionGranted?.invoke()
            Log.i(TAG, "MediaProjection permission granted from pending consent")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register MediaProjection from pending consent: ${e.message}")
            projection.stop()
            false
        }
    }

    /**
     * Request MediaProjection permission (launches consent dialog)
     *
     * This should be called on app startup to pre-request permission.
     * @return true if permission was granted, false if denied
     */
    suspend fun requestMediaProjectionPermission(): Boolean {
        return try {
            val projection = initializeMediaProjection()
            mediaProjection = projection
            onMediaProjectionGranted?.invoke()
            Log.i(TAG, "MediaProjection permission granted")
            true
        } catch (e: Exception) {
            Log.w(TAG, "MediaProjection permission denied: ${e.message}")
            false
        }
    }

    /**
     * Register MediaProjection.Callback for session death detection.
     * On Android 14+ (API 34), this is MANDATORY before createVirtualDisplay().
     */
    private fun registerCallback(projection: MediaProjection) {
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
    }

    /**
     * Capture screenshot
     *
     * @param quality JPEG quality level
     * @return JPEG-encoded screenshot bytes
     */
    suspend fun capture(quality: ScreenshotQuality): ByteArray = captureMutex.withLock {
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()

            try {
                // Try MediaProjection path first
                val bitmap = captureViaMediaProjection()

                try {
                    val jpegBytes = encodeToJpeg(bitmap, quality)
                    val elapsedMs = System.currentTimeMillis() - startTime
                    Log.i(TAG, "Screenshot captured: ${jpegBytes.size} bytes in ${elapsedMs}ms")
                    jpegBytes
                } finally {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaProjection capture failed, trying AccessibilityService fallback", e)

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    throw Exception(
                        "Screenshot failed: MediaProjection unavailable and device is below " +
                        "Android 11 (API 30) so AccessibilityService fallback is not available. " +
                        "Grant screenshot consent via the system dialog.",
                        e
                    )
                }

                try {
                    val bitmap = captureViaAccessibilityService()
                    try {
                        val jpegBytes = encodeToJpeg(bitmap, quality)
                        val elapsedMs = System.currentTimeMillis() - startTime
                        Log.i(TAG, "Screenshot captured via AccessibilityService fallback: ${jpegBytes.size} bytes in ${elapsedMs}ms")
                        jpegBytes
                    } finally {
                        bitmap.recycle()
                    }
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "AccessibilityService fallback also failed", fallbackError)
                    throw Exception(
                        "Screenshot failed: MediaProjection unavailable and AccessibilityService " +
                        "fallback failed. Grant screenshot consent via the system dialog, or " +
                        "ensure Android 11+ for fallback.",
                        fallbackError
                    )
                }
            }
        }
    }

    /**
     * Capture screenshot via MediaProjection
     */
    private suspend fun captureViaMediaProjection(): Bitmap = withContext(Dispatchers.IO) {
        // Step 1: Check if MediaProjection is already initialized (from previous manual approval)
        // Do NOT try to initialize if not available - it would launch consent dialog
        val projection = mediaProjection
            ?: throw SecurityException("MediaProjection not initialized. Manual user consent required.")

        // Step 2: Create VirtualDisplay if not already created
        if (virtualDisplay == null || imageReader == null) {
            virtualDisplay = createVirtualDisplay(projection)
        }

        // Capture local reference so projectionCallback.onStop() can't null it mid-capture
        val reader = imageReader
            ?: throw IllegalStateException("ImageReader is null after createVirtualDisplay")

        // Step 3: Wait for next image from ImageReader (with timeout to prevent deadlock)
        val image = try { withTimeout(IMAGE_READER_TIMEOUT_MS) {
            suspendCancellableCoroutine<android.media.Image> { continuation ->
                // Use local reader reference — safe from concurrent nullification by projectionCallback

                // Drain stale images from previous capture to prevent buffer overflow
                var stale = reader.acquireLatestImage()
                while (stale != null) {
                    stale.close()
                    stale = reader.acquireLatestImage()
                }

                reader.setOnImageAvailableListener({ ir ->
                    // Remove listener FIRST — prevents double-fire if a second frame arrives
                    // before the coroutine resumes and unregisters it
                    ir.setOnImageAvailableListener(null, null)
                    val img = ir.acquireLatestImage()
                    if (img != null) {
                        continuation.resume(img)
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException("acquireLatestImage returned null")
                        )
                    }
                }, Handler(Looper.getMainLooper()))

                continuation.invokeOnCancellation {
                    reader.setOnImageAvailableListener(null, null)
                }
            }
        } } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Tear down stale VirtualDisplay/ImageReader so next capture recreates them fresh
            Log.w(TAG, "ImageReader timed out after ${IMAGE_READER_TIMEOUT_MS}ms — tearing down pipeline")
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            throw e
        }

        try {
            // Step 4: Convert Image to Bitmap
            val bitmap = convertImageToBitmap(image)
            image.close()
            bitmap
        } catch (e: Exception) {
            image.close()
            throw e
        }
    }

    /**
     * Capture screenshot via AccessibilityService.takeScreenshot() (API 30+ fallback)
     *
     * Slower than MediaProjection (~200-400ms) but requires no user consent beyond
     * the accessibility service permission that is already granted.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureViaAccessibilityService(): Bitmap = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException(
                "AccessibilityService.takeScreenshot() requires Android 11+ (API 30)"
            )
        }

        suspendCancellableCoroutine { continuation ->
            accessibilityService.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                accessibilityService.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.hardwareBuffer,
                                screenshotResult.colorSpace
                            )
                            screenshotResult.hardwareBuffer.close()
                            if (hardwareBitmap == null) {
                                continuation.resumeWithException(
                                    IllegalStateException("wrapHardwareBuffer returned null")
                                )
                                return
                            }
                            // Copy to software bitmap for JPEG encoding
                            val softwareBitmap = try {
                                hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            } finally {
                                hardwareBitmap.recycle()
                            }
                            if (softwareBitmap == null) {
                                continuation.resumeWithException(
                                    IllegalStateException("Failed to copy hardware bitmap to software bitmap")
                                )
                                return
                            }
                            continuation.resume(softwareBitmap)
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        continuation.resumeWithException(
                            RuntimeException("AccessibilityService.takeScreenshot failed with error code: $errorCode")
                        )
                    }
                }
            )
        }
    }

    /**
     * Convert Image to Bitmap
     */
    private fun convertImageToBitmap(image: android.media.Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        // Create Bitmap
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )

        bitmap.copyPixelsFromBuffer(buffer)

        // Crop if there's row padding (recycle the oversized intermediate bitmap)
        return if (rowPadding == 0) {
            bitmap
        } else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()
            cropped
        }
    }

    /**
     * Initialize MediaProjection (requires user consent)
     *
     * Launches ScreenshotConsentActivity which creates the MediaProjection while
     * still in the foreground (required on Android 15+). We then poll for the
     * pre-created MediaProjection instance.
     */
    private suspend fun initializeMediaProjection(): MediaProjection = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            Log.d(TAG, "Initializing MediaProjection (requires user consent)")

            // Step 1: Launch ScreenshotConsentActivity to get user consent
            val intent = ScreenshotConsentActivity.createIntent(accessibilityService)
            accessibilityService.startActivity(intent)

            // Step 2: Poll for consent result (since we can't directly await Activity result from Service)
            val pollJob = scope.launch(Dispatchers.IO) {
                var attempts = 0
                val maxAttempts = 100 // 10 seconds timeout (100 * 100ms)

                while (attempts < maxAttempts) {
                    if (ScreenshotConsentActivity.hasConsentResult()) {
                        val projection = ScreenshotConsentActivity.consumeMediaProjection()

                        if (projection != null) {
                            try {
                                registerCallback(projection)
                                Log.i(TAG, "MediaProjection initialized successfully")
                                continuation.resume(projection)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to register MediaProjection callback", e)
                                projection.stop()
                                continuation.resumeWithException(e)
                            }
                        } else {
                            val error = Exception("MediaProjection consent denied by user")
                            Log.w(TAG, error.message.orEmpty())
                            continuation.resumeWithException(error)
                        }

                        return@launch
                    }

                    // Wait 100ms before checking again
                    kotlinx.coroutines.delay(100)
                    attempts++
                }

                // Timeout
                val error = Exception("MediaProjection consent timeout (user did not respond)")
                Log.e(TAG, error.message.orEmpty())
                continuation.resumeWithException(error)
            }

            // Cancel polling if the coroutine is cancelled
            continuation.invokeOnCancellation {
                Log.d(TAG, "MediaProjection initialization cancelled")
                pollJob.cancel()
            }
        }
    }

    /**
     * Create virtual display for screenshot capture
     */
    private fun createVirtualDisplay(mediaProjection: MediaProjection): VirtualDisplay {
        val metrics = getDisplayMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        // Create ImageReader
        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2 // Max images
        )
        imageReader = reader

        // Create VirtualDisplay
        return mediaProjection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null, // Callback
            null  // Handler
        )
    }

    /**
     * Encode bitmap to JPEG
     *
     * Uses JNI call to libjpeg-turbo for hardware-accelerated encoding.
     * Falls back to Android Bitmap.compress() if JNI not available.
     */
    private fun encodeToJpeg(bitmap: Bitmap, quality: ScreenshotQuality): ByteArray {
        val startTime = System.currentTimeMillis()

        // Try JNI encoder first (faster)
        try {
            val jpegBytes = encodeJpegNative(bitmap, quality.jpegQuality)
            if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                val elapsedMs = System.currentTimeMillis() - startTime
                Log.d(TAG, "JPEG encoded via JNI: ${jpegBytes.size} bytes in ${elapsedMs}ms")
                return jpegBytes
            }
            Log.w(TAG, "JNI returned empty/null, falling back to Bitmap.compress()")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "JNI JPEG encoder not available, using fallback")
        } catch (e: Exception) {
            Log.w(TAG, "JNI JPEG encoding failed, using fallback", e)
        }

        // Fallback to Android Bitmap.compress()
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, outputStream)
        val jpegBytes = outputStream.toByteArray()

        val elapsedMs = System.currentTimeMillis() - startTime
        Log.d(TAG, "JPEG encoded via Bitmap.compress(): ${jpegBytes.size} bytes in ${elapsedMs}ms")

        return jpegBytes
    }

    /**
     * Native JPEG encoding via JNI
     *
     * Calls C++ jpeg_encoder.cpp using libjpeg-turbo for faster encoding.
     * This method is declared as external and implemented in C++.
     *
     */
    private external fun encodeJpegNative(bitmap: Bitmap, quality: Int): ByteArray

    /**
     * Load native library
     */
    init {
        try {
            System.loadLibrary("neuralbridge_jni")
            Log.d(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library not available, will use fallback encoder")
        }
    }

    /**
     * Release MediaProjection resources (VirtualDisplay, ImageReader) without stopping projection.
     * Called from the MediaProjection.Callback when the system kills the session.
     */
    private fun releaseProjectionResources() {
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection = null

        Log.d(TAG, "MediaProjection resources released (session lost)")
    }

    /**
     * Clean up all resources. Call synchronously from service onDestroy/disable.
     */
    fun cleanup() {
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        Log.d(TAG, "Screenshot pipeline cleaned up")
    }
}
