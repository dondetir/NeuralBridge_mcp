package com.neuralbridge.companion.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Notification Listener Service
 *
 * Listens for system notifications to provide full notification content
 * to AI agents.
 *
 * AccessibilityService provides basic notification events, but
 * NotificationListenerService is required for:
 * - Full notification title and text
 * - Notification actions (buttons)
 * - Notification icons
 * - Notification priority/category
 *
 * Requires separate permission:
 * Settings → Notifications → Notification access → NeuralBridge
 */
class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"

        /**
         * Static reference to the running service instance.
         * Used by command handlers to access notification information.
         */
        @Volatile
        var instance: NotificationListener? = null
            private set
    }

    /**
     * Service connected
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListenerService connected")
        instance = this
    }

    /**
     * Service disconnected
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "NotificationListenerService disconnected")
        instance = null
    }

    /**
     * Notification posted
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        Log.d(TAG, "Notification posted: ${sbn.packageName}")

        // Extract notification information
        val notification = sbn.notification
        val extras = notification.extras

        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()

        Log.d(TAG, "  Title: $title")
        Log.d(TAG, "  Text: $text")

        // Notification data is available via MCP tools/call → get_notifications
    }

    /**
     * Notification removed
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return

        Log.d(TAG, "Notification removed: ${sbn.packageName}")

        // TODO: Push notification removal event if needed
    }

    /**
     * Get all active notifications (custom method, not overriding)
     */
    fun getActiveNotificationsList(): List<NotificationInfo> {
        return try {
            activeNotifications.map { sbn ->
                val notification = sbn.notification
                val extras = notification.extras

                NotificationInfo(
                    packageName = sbn.packageName,
                    title = extras.getCharSequence("android.title")?.toString() ?: "",
                    text = extras.getCharSequence("android.text")?.toString() ?: "",
                    postTime = sbn.postTime,
                    ongoing = notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0,
                    clearable = !sbn.isOngoing
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get active notifications", e)
            emptyList()
        }
    }
}

/**
 * Notification information
 */
data class NotificationInfo(
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val ongoing: Boolean,
    val clearable: Boolean
)
