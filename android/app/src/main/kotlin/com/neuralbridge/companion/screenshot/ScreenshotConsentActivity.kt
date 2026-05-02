package com.neuralbridge.companion.screenshot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.neuralbridge.companion.service.NeuralBridgeAccessibilityService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screenshot Consent Activity
 *
 * Transparent Activity that handles MediaProjection user consent dialog.
 * This Activity is launched when MediaProjection needs user permission,
 * displays the system consent dialog, and stores the result for ScreenshotPipeline.
 *
 * On Android 14+, getMediaProjection() requires a foreground service with
 * MEDIA_PROJECTION type, so we upgrade the service type before creating
 * the projection. If the upgrade fails (OEM restriction), we fall back
 * gracefully and rely on AccessibilityService.takeScreenshot() instead.
 */
class ScreenshotConsentActivity : Activity() {

    companion object {
        private const val TAG = "ScreenshotConsentActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1

        // Prevents two concurrent instances from both showing the system dialog
        private val isConsentInProgress = AtomicBoolean(false)

        // Shared result storage (since we can't directly pass result between components)
        @Volatile
        private var pendingMediaProjection: MediaProjection? = null

        /**
         * Check if consent result is available
         */
        fun hasConsentResult(): Boolean {
            return pendingMediaProjection != null
        }

        /**
         * Get consent result and clear it (thread-safe — only one caller wins).
         * @return MediaProjection or null if not available
         */
        @Synchronized
        fun consumeMediaProjection(): MediaProjection? {
            val projection = pendingMediaProjection
            pendingMediaProjection = null
            return projection
        }

        /**
         * Create intent to launch this Activity
         */
        fun createIntent(context: Context): Intent {
            return Intent(context, ScreenshotConsentActivity::class.java).apply {
                // Launch as new task (since we're starting from Service context)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If another instance is already showing the dialog, bail immediately.
        // This prevents double-popups when the service auto-request and the
        // Setup tab GRANT button fire at the same time.
        if (!isConsentInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Consent already in progress — dismissing duplicate")
            finish()
            return
        }

        Log.d(TAG, "ScreenshotConsentActivity created")

        // Request MediaProjection consent
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()

        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            Log.d(TAG, "MediaProjection consent result: resultCode=$resultCode")

            if (resultCode == RESULT_OK && data != null) {
                tryCreateMediaProjection(resultCode, data)
            } else {
                Log.w(TAG, "MediaProjection consent denied or cancelled")
            }
        }

        // Allow future consent requests
        isConsentInProgress.set(false)

        // Close this Activity
        finish()
    }

    /**
     * Try to create MediaProjection after user grants consent.
     * On Android 14+, the foreground service must have MEDIA_PROJECTION type
     * BEFORE calling getMediaProjection(), so we upgrade it first.
     * The service-side callback also upgrades (with an AtomicBoolean guard
     * preventing double execution), but the Activity-side call must happen
     * first because getMediaProjection() checks the FGS type immediately.
     * If the upgrade fails (OEM restriction), AccessibilityService.takeScreenshot()
     * is used as fallback.
     */
    private fun tryCreateMediaProjection(resultCode: Int, data: Intent) {
        val service = NeuralBridgeAccessibilityService.instance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && service != null) {
            service.upgradeForegroundServiceForMediaProjection()
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, data)
            if (projection != null) {
                pendingMediaProjection = projection
                Log.i(TAG, "MediaProjection created and stored")
            } else {
                Log.e(TAG, "getMediaProjection returned null")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "MediaProjection not available (${e.message}), will use AccessibilityService.takeScreenshot() fallback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MediaProjection: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        // Reset the gate so future consent requests are not permanently blocked
        // if this activity is killed before onActivityResult fires
        isConsentInProgress.set(false)
        super.onDestroy()
    }
}
