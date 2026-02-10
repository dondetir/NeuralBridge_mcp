package com.neuralbridge.companion

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Main Activity
 *
 * Permission management UI showing status of all required permissions
 * with direct shortcuts to system settings for each.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1001
        private const val TAG = "MainActivity"
    }

    // Permission status views
    private val permissionViews = mutableMapOf<String, PermissionView>()

    private lateinit var progressText: TextView
    private lateinit var overallStatusText: TextView
    private lateinit var serviceStatusText: TextView
    private lateinit var connectionInfoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createView())

        // Request POST_NOTIFICATIONS permission at runtime (Android 13+)
        requestNotificationPermissionIfNeeded()

        updateAllStatus()
    }

    override fun onResume() {
        super.onResume()
        updateAllStatus()
    }

    /**
     * Create comprehensive permission management UI
     */
    private fun createView(): View {
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // App title
        layout.addView(createTextView("NeuralBridge", 28f, true).apply {
            setPadding(0, 0, 0, 16)
        })

        // Progress indicator
        progressText = createTextView("Checking permissions...", 14f, false).apply {
            setPadding(0, 0, 0, 8)
        }
        layout.addView(progressText)

        // Overall status
        overallStatusText = createTextView("", 18f, true).apply {
            setPadding(0, 0, 0, 24)
        }
        layout.addView(overallStatusText)

        // Section: Required Permissions
        layout.addView(createTextView("REQUIRED PERMISSIONS", 16f, true).apply {
            setPadding(0, 0, 0, 12)
        })

        // 1. AccessibilityService
        permissionViews["accessibility"] = createPermissionView(
            "AccessibilityService",
            "Core automation service for UI control and observation"
        ).also { layout.addView(it.container) }

        // 2. NotificationListenerService
        permissionViews["notification_listener"] = createPermissionView(
            "Notification Listener",
            "Access full notification content (title, text, actions)"
        ).also { layout.addView(it.container) }

        // 3. POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionViews["post_notifications"] = createPermissionView(
                "Notification Permission",
                "Show foreground service notification"
            ).also { layout.addView(it.container) }
        }

        // 4. Battery optimization exemption (recommended)
        permissionViews["battery"] = createPermissionView(
            "Battery Optimization Exempt",
            "Prevent Android from killing the service (recommended)"
        ).also { layout.addView(it.container) }

        // 5. Restricted Settings (Android 15+)
        if (Build.VERSION.SDK_INT >= 35) { // Android 15+
            permissionViews["restricted_settings"] = createPermissionView(
                "Restricted Settings",
                "Allow full AccessibilityService permissions (Android 15+)"
            ).also { layout.addView(it.container) }
        }

        // Section: Service Status
        layout.addView(createTextView("SERVICE STATUS", 16f, true).apply {
            setPadding(0, 24, 0, 12)
        })

        serviceStatusText = createTextView("", 14f, false).apply {
            setPadding(0, 0, 0, 8)
        }
        layout.addView(serviceStatusText)

        connectionInfoText = createTextView("", 14f, false).apply {
            setPadding(0, 0, 0, 16)
        }
        layout.addView(connectionInfoText)

        // ADB Setup Instructions
        layout.addView(createTextView("ADB SETUP", 16f, true).apply {
            setPadding(0, 8, 0, 12)
        })

        layout.addView(createTextView(
            "$ adb forward tcp:38472 tcp:38472\n$ cargo run --release -- --auto-discover",
            12f,
            false
        ).apply {
            setPadding(16, 8, 16, 8)
            setBackgroundColor(0xFF2A2A2A.toInt())
            setTextColor(0xFF00FF00.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        })

        scrollView.addView(layout)
        return scrollView
    }

    /**
     * Create a permission view with status indicator and action button
     */
    private fun createPermissionView(title: String, description: String): PermissionView {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFFF5F5F5.toInt())
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 8)
            layoutParams = params
        }

        // Title row with status indicator
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusIndicator = TextView(this).apply {
            text = "⚠"
            textSize = 18f
            setPadding(0, 0, 12, 0)
        }
        titleRow.addView(statusIndicator)

        val titleText = TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        titleRow.addView(titleText)

        container.addView(titleRow)

        // Description
        val descriptionText = TextView(this).apply {
            text = description
            textSize = 12f
            setPadding(30, 4, 0, 8)
            setTextColor(0xFF666666.toInt())
        }
        container.addView(descriptionText)

        // Action button
        val actionButton = Button(this).apply {
            text = "GRANT"
            textSize = 12f
            setPadding(24, 8, 24, 8)
        }
        container.addView(actionButton)

        return PermissionView(container, statusIndicator, actionButton)
    }

    /**
     * Create a styled TextView
     */
    private fun createTextView(text: String, size: Float, bold: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    /**
     * Request POST_NOTIFICATIONS permission at runtime (Android 13+)
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_POST_NOTIFICATIONS
                )
            }
        }
    }

    /**
     * Handle permission request result
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            updateAllStatus()
        }
    }

    /**
     * Update all permission statuses and UI
     */
    private fun updateAllStatus() {
        // Check each permission
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val notificationListenerEnabled = isNotificationListenerEnabled()
        val postNotificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isPostNotificationsGranted()
        } else {
            true // Not required below Android 13
        }
        val batteryOptimizationExempt = isBatteryOptimizationExempt()
        val restrictedSettingsAllowed = true // Cannot programmatically check this

        // Update permission views
        updatePermissionView(
            "accessibility",
            accessibilityEnabled,
            "ENABLED",
            "ENABLE →"
        ) { openAccessibilitySettings() }

        updatePermissionView(
            "notification_listener",
            notificationListenerEnabled,
            "ENABLED",
            "ENABLE →"
        ) { openNotificationListenerSettings() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            updatePermissionView(
                "post_notifications",
                postNotificationsGranted,
                "GRANTED",
                "GRANT →"
            ) { requestNotificationPermissionIfNeeded() }
        }

        updatePermissionView(
            "battery",
            batteryOptimizationExempt,
            "EXEMPT",
            "EXEMPT →"
        ) { openBatteryOptimizationSettings() }

        if (Build.VERSION.SDK_INT >= 35) { // Android 15+
            updatePermissionView(
                "restricted_settings",
                restrictedSettingsAllowed,
                "ALLOWED",
                "OPEN →"
            ) { openAppSettings() }
        }

        // Calculate progress
        var granted = 0
        var total = 0

        total++
        if (accessibilityEnabled) granted++

        total++
        if (notificationListenerEnabled) granted++

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            total++
            if (postNotificationsGranted) granted++
        }

        // Battery optimization is optional, don't count towards required

        progressText.text = "Setup Progress: $granted/$total required permissions"

        // Update overall status
        val allGranted = (granted == total)
        if (allGranted) {
            overallStatusText.text = "✓ ALL SYSTEMS READY"
            overallStatusText.setTextColor(0xFF00AA00.toInt())
        } else {
            overallStatusText.text = "⚠ SETUP INCOMPLETE"
            overallStatusText.setTextColor(0xFFCC6600.toInt())
        }

        // Update service status
        val service = com.neuralbridge.companion.service.NeuralBridgeAccessibilityService.instance
        if (service != null) {
            serviceStatusText.text = "🟢 AccessibilityService running\n🟢 TCP Server on port 38472\n🟢 Foreground Service active"
            serviceStatusText.setTextColor(0xFF00AA00.toInt())

            // TODO: Get actual connection count from TcpServer
            connectionInfoText.text = "📡 Ready for connections"
        } else {
            serviceStatusText.text = "🔴 AccessibilityService not running"
            serviceStatusText.setTextColor(0xFFCC0000.toInt())
            connectionInfoText.text = ""
        }
    }

    /**
     * Update a permission view with status
     */
    private fun updatePermissionView(
        key: String,
        granted: Boolean,
        grantedText: String,
        notGrantedText: String,
        action: () -> Unit
    ) {
        val view = permissionViews[key] ?: return

        if (granted) {
            view.statusIndicator.text = "✓"
            view.statusIndicator.setTextColor(0xFF00AA00.toInt())
            view.actionButton.text = grantedText
            view.actionButton.isEnabled = false
            view.actionButton.alpha = 0.5f
        } else {
            view.statusIndicator.text = "✗"
            view.statusIndicator.setTextColor(0xFFCC0000.toInt())
            view.actionButton.text = notGrantedText
            view.actionButton.isEnabled = true
            view.actionButton.alpha = 1.0f
            view.actionButton.setOnClickListener { action() }
        }
    }

    // ========================================================================
    // Permission Checking Methods
    // ========================================================================

    /**
     * Check if AccessibilityService is enabled
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this,
            com.neuralbridge.companion.service.NeuralBridgeAccessibilityService::class.java)

        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    /**
     * Check if NotificationListenerService is enabled
     */
    private fun isNotificationListenerEnabled(): Boolean {
        val packageName = packageName
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabledListeners.contains(packageName)
    }

    /**
     * Check if POST_NOTIFICATIONS permission is granted (Android 13+)
     */
    private fun isPostNotificationsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Check if battery optimization is disabled for this app
     */
    private fun isBatteryOptimizationExempt(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // minSdk is 24, which is >= API 23 (M), so this is always available
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    // ========================================================================
    // Settings Intent Launchers
    // ========================================================================

    /**
     * Open accessibility settings
     */
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    /**
     * Open notification listener settings
     */
    private fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    /**
     * Open battery optimization settings
     */
    private fun openBatteryOptimizationSettings() {
        // minSdk is 24, which is >= API 23 (M), so this is always available
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    /**
     * Open app-specific settings
     */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}

/**
 * Container for permission view components
 */
private data class PermissionView(
    val container: LinearLayout,
    val statusIndicator: TextView,
    val actionButton: Button
)
