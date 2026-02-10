package com.neuralbridge.companion.screenshot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Screenshot Consent Activity
 *
 * Transparent Activity that handles MediaProjection user consent dialog.
 * This Activity is launched when MediaProjection needs user permission,
 * displays the system consent dialog, and stores the result for ScreenshotPipeline.
 *
 * Usage:
 * 1. ScreenshotPipeline calls startActivityForResult() with consent intent
 * 2. System shows "Start capturing everything on your screen?" dialog
 * 3. User taps "Start now"
 * 4. This Activity receives result and stores it in MediaProjectionManager
 * 5. ScreenshotPipeline creates MediaProjection from stored result
 *
 * Note: On Android 14+, consent is single-use and must be re-requested
 * after app restart or device reboot.
 */
class ScreenshotConsentActivity : Activity() {

    companion object {
        private const val TAG = "ScreenshotConsentActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1

        // Shared result storage (since we can't directly pass result between components)
        @Volatile
        private var pendingResultCode: Int? = null

        @Volatile
        private var pendingResultData: Intent? = null

        /**
         * Check if consent result is available
         */
        fun hasConsentResult(): Boolean {
            return pendingResultCode != null && pendingResultData != null
        }

        /**
         * Get consent result and clear it
         * @return Pair of (resultCode, resultData) or null if not available
         */
        fun consumeConsentResult(): Pair<Int, Intent>? {
            val code = pendingResultCode
            val data = pendingResultData

            if (code != null && data != null) {
                // Clear after consuming
                pendingResultCode = null
                pendingResultData = null
                return Pair(code, data)
            }

            return null
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

        Log.d(TAG, "ScreenshotConsentActivity created")

        // Request MediaProjection consent
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()

        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            Log.d(TAG, "MediaProjection consent result: resultCode=$resultCode")

            if (resultCode == RESULT_OK && data != null) {
                // Store result for ScreenshotPipeline to consume
                pendingResultCode = resultCode
                pendingResultData = data

                Log.i(TAG, "MediaProjection consent granted")
            } else {
                Log.w(TAG, "MediaProjection consent denied or cancelled")

                // Store failure result
                pendingResultCode = resultCode
                pendingResultData = null
            }
        }

        // Close this Activity
        finish()
    }
}
