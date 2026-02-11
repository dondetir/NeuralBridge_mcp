package com.neuralbridge.companion.screenshot

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.neuralbridge.companion.service.ScreenshotQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Screenshot Pipeline
 *
 * Captures screenshots using MediaProjection → VirtualDisplay → ImageReader.
 * Falls back to ADB screencap if MediaProjection is unavailable.
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
    }

    // MediaProjection components
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Screen dimensions
    private val displayMetrics: DisplayMetrics by lazy {
        val windowManager = accessibilityService.getSystemService(WindowManager::class.java)
        DisplayMetrics().apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // Use new API for Android 11+ (API 30+)
                val metrics = windowManager.currentWindowMetrics
                val bounds = metrics.bounds
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                densityDpi = accessibilityService.resources.displayMetrics.densityDpi
            } else {
                // Use deprecated API for Android 7-10 (API 24-29)
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
     * Request MediaProjection permission (launches consent dialog)
     *
     * This should be called on app startup to pre-request permission.
     * @return true if permission was granted, false if denied
     */
    suspend fun requestMediaProjectionPermission(): Boolean {
        return try {
            val projection = initializeMediaProjection()
            mediaProjection = projection
            Log.i(TAG, "MediaProjection permission granted")
            true
        } catch (e: Exception) {
            Log.w(TAG, "MediaProjection permission denied: ${e.message}")
            false
        }
    }

    /**
     * Capture screenshot
     *
     * @param quality JPEG quality level
     * @return JPEG-encoded screenshot bytes
     */
    suspend fun capture(quality: ScreenshotQuality): ByteArray = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            // Try MediaProjection path first
            val bitmap = captureViaMediaProjection()

            // Encode to JPEG
            val jpegBytes = encodeToJpeg(bitmap, quality)

            val elapsedMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "Screenshot captured: ${jpegBytes.size} bytes in ${elapsedMs}ms")

            jpegBytes
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection capture failed, falling back to ADB", e)

            // Fallback to ADB screencap
            // TODO Week 4: Implement ADB fallback
            // This requires routing through MCP server's ADB connection
            throw Exception("MediaProjection unavailable and ADB fallback not yet implemented", e)
        }
    }

    /**
     * Capture screenshot via MediaProjection
     */
    private suspend fun captureViaMediaProjection(): Bitmap = withContext(Dispatchers.IO) {
        // Step 1: Check if MediaProjection is already initialized (from previous manual approval)
        // Do NOT try to initialize if not available - it would launch consent dialog
        if (mediaProjection == null) {
            throw SecurityException("MediaProjection not initialized. Manual user consent required.")
        }

        // Step 2: Create VirtualDisplay if not already created
        if (virtualDisplay == null || imageReader == null) {
            virtualDisplay = createVirtualDisplay(mediaProjection!!)
        }

        // Step 3: Wait for next image from ImageReader
        val image = suspendCancellableCoroutine<android.media.Image> { continuation ->
            val reader = imageReader ?: run {
                continuation.resumeWithException(IllegalStateException("ImageReader is null"))
                return@suspendCancellableCoroutine
            }

            reader.setOnImageAvailableListener({ imageReader ->
                try {
                    val img = imageReader.acquireLatestImage()
                    if (img != null) {
                        continuation.resume(img)
                    } else {
                        continuation.resumeWithException(IllegalStateException("acquireLatestImage returned null"))
                    }
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }, null)

            // Handle cancellation
            continuation.invokeOnCancellation {
                reader.setOnImageAvailableListener(null, null)
            }
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

        // Crop if there's row padding
        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        }
    }

    /**
     * Initialize MediaProjection (requires user consent)
     */
    private suspend fun initializeMediaProjection(): MediaProjection = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            Log.d(TAG, "Initializing MediaProjection (requires user consent)")

            // Step 1: Launch ScreenshotConsentActivity to get user consent
            val intent = ScreenshotConsentActivity.createIntent(accessibilityService)
            accessibilityService.startActivity(intent)

            // Step 2: Poll for consent result (since we can't directly await Activity result from Service)
            scope.launch(Dispatchers.IO) {
                var attempts = 0
                val maxAttempts = 100 // 10 seconds timeout (100 * 100ms)

                while (attempts < maxAttempts) {
                    if (ScreenshotConsentActivity.hasConsentResult()) {
                        val result = ScreenshotConsentActivity.consumeConsentResult()

                        if (result != null) {
                            val (resultCode, resultData) = result

                            if (resultCode == Activity.RESULT_OK) {
                                try {
                                    // Step 3: Create MediaProjection from consent result
                                    val mediaProjectionManager = accessibilityService.getSystemService(
                                        MediaProjectionManager::class.java
                                    )
                                    val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

                                    Log.i(TAG, "MediaProjection initialized successfully")
                                    continuation.resume(projection)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to create MediaProjection", e)
                                    continuation.resumeWithException(e)
                                }
                            } else {
                                val error = Exception("MediaProjection consent denied by user")
                                Log.w(TAG, error.message.orEmpty())
                                continuation.resumeWithException(error)
                            }
                        } else {
                            val error = Exception("MediaProjection consent result is null")
                            Log.e(TAG, error.message.orEmpty())
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

            // Handle cancellation
            continuation.invokeOnCancellation {
                Log.d(TAG, "MediaProjection initialization cancelled")
            }
        }
    }

    /**
     * Create virtual display for screenshot capture
     */
    private fun createVirtualDisplay(mediaProjection: MediaProjection): VirtualDisplay {
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val densityDpi = displayMetrics.densityDpi

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
            val elapsedMs = System.currentTimeMillis() - startTime
            Log.d(TAG, "JPEG encoded via JNI: ${jpegBytes.size} bytes in ${elapsedMs}ms")
            return jpegBytes
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "JNI JPEG encoder not available, using fallback")
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
     * TODO Week 5: Implement C++ JNI encoder
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
     * Clean up resources
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

/**
 * ADB Screenshot Provider (fallback)
 *
 * This is a placeholder for the ADB fallback path.
 * Actual implementation must route through MCP server's ADB connection.
 */
object AdbScreencapProvider {
    /**
     * Capture screenshot via ADB
     *
     * Requires routing request back to MCP server, which executes:
     * adb exec-out screencap -p
     *
     * Returns PNG bytes, which must be converted to JPEG if needed.
     */
    suspend fun capture(): ByteArray {
        // TODO Week 4: Implement ADB screenshot routing
        throw NotImplementedError("ADB screencap fallback not yet implemented")
    }
}
