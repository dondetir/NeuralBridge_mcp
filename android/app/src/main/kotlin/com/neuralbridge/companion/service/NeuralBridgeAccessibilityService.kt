package com.neuralbridge.companion.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.neuralbridge.companion.R
import com.neuralbridge.companion.gesture.GestureEngine
import com.neuralbridge.companion.input.InputEngine
import com.neuralbridge.companion.uitree.UiTreeWalker
import com.neuralbridge.companion.screenshot.ScreenshotPipeline
import com.neuralbridge.companion.mcp.McpHttpServer
import com.neuralbridge.companion.mcp.McpToolHandler
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * NeuralBridge AccessibilityService
 *
 * Core automation service that provides:
 * - UI tree observation across all apps
 * - Gesture injection via dispatchGesture()
 * - Screenshot capture
 * - Event streaming to connected clients
 *
 * The service runs as a foreground service with persistent notification
 * to prevent Android from killing it during automation sessions.
 */
class NeuralBridgeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NeuralBridge"
        private const val NOTIFICATION_CHANNEL_ID = "neuralbridge_service"
        private const val NOTIFICATION_ID = 1

        // Event streaming throttling: max 10 events/sec = 1 event per 100ms
        private const val MIN_EVENT_INTERVAL_MS = 100L

        /**
         * Static reference to the running service instance.
         * Used by command handlers to access service capabilities.
         */
        @Volatile
        var instance: NeuralBridgeAccessibilityService? = null
            private set

        /**
         * Static toast buffer for McpToolHandler.handleGetRecentToasts().
         * CopyOnWriteArrayList is thread-safe for concurrent reads from MCP HTTP handler.
         */
        val recentToasts = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Long>>()
    }

    // Coroutine scope for service lifecycle
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Core components
    private lateinit var gestureEngine: GestureEngine
    private lateinit var inputEngine: InputEngine
    private lateinit var uiTreeWalker: UiTreeWalker
    private lateinit var screenshotPipeline: ScreenshotPipeline

    // MCP HTTP server
    private var mcpHttpServer: McpHttpServer? = null

    // Event listener for UI changes
    private val eventListeners = mutableListOf<AccessibilityEventListener>()

    // Event streaming state
    private val eventsEnabled = AtomicBoolean(false)
    private val lastEventTime = AtomicLong(0)

    // Track service start time
    private var startTime: Long = 0L

    /**
     * Service connected - called when AccessibilityService is bound
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AccessibilityService connected")

        startTime = System.currentTimeMillis()

        // Configure accessibility service
        configureAccessibilityService()

        // Initialize components (sets gestureEngine, uiTreeWalker, screenshotPipeline)
        initializeComponents()

        // Publish instance before conditional startup so MainActivity can query it
        instance = this

        // Only start foreground service, HTTP server, and screen recording if toggle is on
        if (isEnabled()) {
            startForegroundService()
            startMcpHttpServer()
            requestMediaProjectionPermission()
        }

        Log.i(TAG, "NeuralBridge service fully initialized")
    }

    /**
     * Configure AccessibilityService flags
     */
    private fun configureAccessibilityService() {
        // Note: Most configuration is in accessibility_service_config.xml
        // Additional runtime configuration can be done here if needed

        Log.d(TAG, "AccessibilityService configured")
    }

    /**
     * Initialize core components
     */
    private fun initializeComponents() {
        gestureEngine = GestureEngine(this)
        inputEngine = InputEngine(this)
        uiTreeWalker = UiTreeWalker(this)
        screenshotPipeline = ScreenshotPipeline(this, serviceScope)

        Log.d(TAG, "Core components initialized")
    }

    /**
     * Start MCP HTTP server (JSON-RPC over HTTP, port 7474)
     */
    private fun startMcpHttpServer() {
        val toolHandler = McpToolHandler(
            service = this,
            gestureEngine = gestureEngine,
            uiTreeWalker = uiTreeWalker,
            inputEngine = inputEngine,
            screenshotPipeline = screenshotPipeline
        )
        val server = McpHttpServer(
            context = this,
            toolHandler = toolHandler
        )
        mcpHttpServer = server
        serviceScope.launch {
            try {
                server.start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MCP HTTP server", e)
            }
        }
    }

    /**
     * Start service as foreground service
     */
    private fun startForegroundService() {
        createNotificationChannel()

        val notification = buildNotification(
            title = getString(R.string.foreground_service_title),
            message = getString(R.string.foreground_service_message)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: must specify foreground service type matching manifest declaration
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.d(TAG, "Foreground service started")
    }

    /**
     * Request MediaProjection permission for fast screenshot capture
     *
     * This is called on service startup to pre-request permission so that
     * screenshots use the fast path (60ms) instead of ADB fallback (200ms).
     *
     * On Android 14+, this permission is single-use and expires when the
     * app process is killed or device restarts.
     */
    private fun requestMediaProjectionPermission() {
        // Check if already granted
        if (screenshotPipeline.hasMediaProjectionPermission()) {
            Log.i(TAG, "MediaProjection permission already granted")
            return
        }

        // Request permission asynchronously
        serviceScope.launch {
            delay(1000) // Wait 1 second after service startup before showing dialog

            val granted = screenshotPipeline.requestMediaProjectionPermission()

            if (granted) {
                Log.i(TAG, "MediaProjection permission granted - fast screenshots enabled")
                updateNotificationForFastScreenshots()
            } else {
                Log.w(TAG, "MediaProjection permission denied - using ADB fallback for screenshots")
            }
        }
    }

    /**
     * Try to consume any pending MediaProjection consent result.
     * Called from MainActivity.onResume() after user grants consent via Setup tab.
     *
     * Deliberately calls tryConsumePendingConsent() rather than requestMediaProjectionPermission()
     * to avoid re-launching ScreenshotConsentActivity. When onResume() fires after the user
     * accepts the auto-prompted dialog, the service's polling loop may not have consumed the
     * result yet — calling requestMediaProjectionPermission() would show the popup a second time.
     */
    fun tryConsumeMediaProjectionConsent() {
        if (screenshotPipeline.hasMediaProjectionPermission()) return
        if (!com.neuralbridge.companion.screenshot.ScreenshotConsentActivity.hasConsentResult()) return
        serviceScope.launch {
            val granted = screenshotPipeline.tryConsumePendingConsent()
            if (granted) {
                Log.i(TAG, "MediaProjection permission granted from UI - fast screenshots enabled")
                updateNotificationForFastScreenshots()
            }
        }
    }

    /**
     * Update notification to indicate fast screenshots are enabled
     */
    private fun updateNotificationForFastScreenshots() {
        val notification = buildNotification(
            title = getString(R.string.foreground_service_title),
            message = "Fast screenshots enabled (60ms)"
        )

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Create notification channel (Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "NeuralBridge Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps NeuralBridge service running"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Build notification for foreground service
     */
    private fun buildNotification(title: String, message: String): Notification {
        val openAppIntent = Intent(this, com.neuralbridge.companion.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * Handle accessibility events
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Always notify local listeners first (for wait_for_idle, etc.)
        eventListeners.forEach { listener ->
            try {
                listener.onEvent(event)
            } catch (e: Exception) {
                Log.e(TAG, "Event listener error", e)
            }
        }

        // Check if event streaming is enabled
        if (!eventsEnabled.get()) {
            return
        }

        // Throttle events to prevent flooding
        if (shouldThrottleEvent()) {
            return
        }

        // Stream relevant events to MCP server
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                sendUIChangeEvent(event)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // Check if it's a Toast
                if (event.className == "android.widget.Toast") {
                    sendToastEvent(event)
                }
            }
        }
    }

    /**
     * Handle service interruption
     */
    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    /**
     * Service destroyed
     */
    override fun onDestroy() {
        Log.i(TAG, "Service shutting down")

        // Release screen wake lock synchronously before scope cancellation
        mcpHttpServer?.releaseScreenWakeLock()

        // Stop MCP HTTP server if it was started
        serviceScope.launch {
            mcpHttpServer?.stop()
        }

        // Cancel all coroutines
        serviceScope.cancel()

        // Clear static instance
        instance = null

        super.onDestroy()
    }

    // ========================================================================
    // Public API for command handlers
    // ========================================================================

    /**
     * Get root UI node
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    /**
     * Execute a gesture
     */
    fun executeGesture(
        gesture: GestureDescription,
        callback: GestureResultCallback? = null
    ) {
        val internalCallback = if (callback != null) {
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback.onCompleted(gestureDescription ?: return)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback.onCancelled(gestureDescription ?: return)
                }
            }
        } else null

        dispatchGesture(gesture, internalCallback, null)
    }

    /**
     * Get UI tree
     */
    suspend fun getUiTree(
        includeInvisible: Boolean = false,
        maxDepth: Int = 0
    ): UiTree {
        return withContext(Dispatchers.Default) {
            uiTreeWalker.walkTree(getRootNode(), includeInvisible, maxDepth)
        }
    }

    /**
     * Capture screenshot
     */
    suspend fun captureScreenshot(quality: ScreenshotQuality): ByteArray {
        return screenshotPipeline.capture(quality)
    }

    /**
     * Perform global action (wrapper)
     */
    fun executeGlobalAction(action: Int): Boolean {
        return performGlobalAction(action)
    }

    /**
     * Register event listener
     */
    fun registerEventListener(listener: AccessibilityEventListener) {
        eventListeners.add(listener)
    }

    /**
     * Unregister event listener
     */
    fun unregisterEventListener(listener: AccessibilityEventListener) {
        eventListeners.remove(listener)
    }

    /**
     * Check if the user has enabled NeuralBridge via the master toggle
     */
    private fun isEnabled(): Boolean {
        return getSharedPreferences("neuralbridge_prefs", Context.MODE_PRIVATE)
            .getBoolean("nb_enabled", false)
    }

    /**
     * Start MCP server and request MediaProjection — called when user turns on the master toggle
     */
    fun enable() {
        startForegroundService()
        startMcpHttpServer()
        requestMediaProjectionPermission()
    }

    /**
     * Stop MCP server and remove notification — called when user turns off the master toggle
     */
    fun disable() {
        @Suppress("DEPRECATION")
        stopForeground(true)
        mcpHttpServer?.releaseScreenWakeLock()
        serviceScope.launch {
            mcpHttpServer?.stop()
            mcpHttpServer = null
        }
    }

    /**
     * Enable or disable event streaming
     */
    fun setEventsEnabled(enabled: Boolean) {
        eventsEnabled.set(enabled)
        Log.i(TAG, "Event streaming ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if an MCP HTTP client has been active recently (within 30s window)
     */
    fun isHttpClientActive(): Boolean {
        return mcpHttpServer?.isClientActive() ?: false
    }

    /**
     * Get service uptime in milliseconds
     */
    fun getUptime(): Long {
        return if (startTime > 0) System.currentTimeMillis() - startTime else 0
    }

    /**
     * Get MCP HTTP server port (for MainActivity display)
     */
    fun getMcpHttpPort(): Int = mcpHttpServer?.getPort() ?: McpHttpServer.MCP_PORT

    /**
     * Check if MCP HTTP server is running (for MainActivity status display)
     */
    fun getMcpHttpActive(): Boolean = mcpHttpServer?.isRunning() ?: false

    /**
     * Check if MediaProjection permission is granted
     */
    fun hasMediaProjectionPermission(): Boolean {
        return if (::screenshotPipeline.isInitialized) screenshotPipeline.hasMediaProjectionPermission() else false
    }

    /**
     * Check if enough time has passed since last event (throttling)
     */
    private fun shouldThrottleEvent(): Boolean {
        val now = System.currentTimeMillis()
        val last = lastEventTime.get()
        if (now - last < MIN_EVENT_INTERVAL_MS) {
            return true // Too soon, throttle
        }
        lastEventTime.set(now)
        return false
    }

    /**
     * Handle UI change event (logged only; no broadcast since TCP was removed)
     */
    private fun sendUIChangeEvent(event: AccessibilityEvent) {
        Log.d(TAG, "UI change event: ${event.source?.hashCode() ?: "unknown"}")
    }

    /**
     * Cache toast event for MCP HTTP handler polling
     */
    private fun sendToastEvent(event: AccessibilityEvent) {
        try {
            val text = event.text.joinToString(" ")

            // Add to static toast buffer for MCP HTTP handler (max 50 entries)
            if (recentToasts.size >= 50) recentToasts.removeAt(0)
            recentToasts.add(text to System.currentTimeMillis())

            Log.d(TAG, "Toast event cached: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Error caching toast event", e)
        }
    }
}

/**
 * Interface for accessibility event listeners
 */
interface AccessibilityEventListener {
    fun onEvent(event: AccessibilityEvent)
}

/**
 * Gesture execution result callback
 */
interface GestureResultCallback {
    fun onCompleted(gesture: GestureDescription)
    fun onCancelled(gesture: GestureDescription)
}

/**
 * Screenshot quality levels
 */
enum class ScreenshotQuality(val jpegQuality: Int) {
    FULL(80),
    THUMBNAIL(40)
}

/**
 * UI tree representation
 */
data class UiTree(
    val elements: List<UiElement>,
    val foregroundApp: String,
    val totalNodes: Int,
    val captureTimestamp: Long
)

/**
 * UI element representation
 */
data class UiElement(
    val elementId: String,
    val resourceId: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: Bounds?,
    val visible: Boolean,
    val enabled: Boolean,
    val clickable: Boolean,
    val scrollable: Boolean,
    val focusable: Boolean,
    val longClickable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val semanticType: String,
    val aiDescription: String
)

/**
 * Element bounds
 */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
