package com.neuralbridge.companion

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.neuralbridge.companion.adapter.LogAdapter
import com.neuralbridge.companion.log.CommandLog
import com.neuralbridge.companion.mcp.McpHttpServer
import com.neuralbridge.companion.mcp.McpNetworkUtils
import com.neuralbridge.companion.service.NeuralBridgeAccessibilityService

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_POST_NOTIFICATIONS = 1001
        private const val PREFS_NAME = "neuralbridge_prefs"
        private const val KEY_ENABLED = "nb_enabled"
    }

    // Tab views
    private lateinit var tabStatus: TextView
    private lateinit var tabSetup: TextView
    private lateinit var tabLogs: TextView
    private lateinit var tabIndicator: View
    private lateinit var tabStatusContent: View
    private lateinit var tabSetupContent: View
    private lateinit var tabLogsContent: View

    // Header
    private lateinit var statusDot: View
    private lateinit var overallStatusText: TextView

    // Master toggle
    private lateinit var masterToggle: Switch

    // Status tab views
    private lateinit var connectionStatusIcon: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var connectionDetailText: TextView
    private lateinit var accessibilityStatusBar: View
    private lateinit var accessibilityStatusTextView: TextView
    private lateinit var mcpStatusBar: View
    private lateinit var mcpStatusText: TextView
    private lateinit var screenshotStatusBar: View
    private lateinit var screenshotStatusText: TextView
    private lateinit var deviceInfoText: TextView
    private lateinit var perfP50: TextView
    private lateinit var perfP95: TextView
    private lateinit var perfP99: TextView
    private lateinit var perfCount: TextView

    // Setup tab views
    private lateinit var permissionProgressLabel: TextView
    private lateinit var permissionProgressBar: ProgressBar
    private lateinit var permissionCardsContainer: LinearLayout

    // Logs tab views
    private lateinit var logRecyclerView: RecyclerView
    private lateinit var logEmptyText: TextView
    private lateinit var logAdapter: LogAdapter
    private var logsPaused = false
    private var logFilter: String = "ALL"

    // Polling
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            updateServiceStatus()
            updatePerformanceStats()
            if (!logsPaused) updateLogEntries()
            statusHandler.postDelayed(this, 1000)
        }
    }

    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViews()
        setupTabs()
        setupMasterToggle()
        setupSetupTab()
        setupLogsTab()
        updateDeviceInfo()

        updateAllPermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        NeuralBridgeAccessibilityService.instance?.tryConsumeMediaProjectionConsent()
        updateAllPermissionStatus()
        statusHandler.post(statusRunnable)
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusRunnable)
    }

    private fun findViews() {
        // Tab bar
        tabStatus = findViewById(R.id.tabStatus)
        tabSetup = findViewById(R.id.tabSetup)
        tabLogs = findViewById(R.id.tabLogs)
        tabIndicator = findViewById(R.id.tabIndicator)
        tabStatusContent = findViewById(R.id.tabStatusContent)
        tabSetupContent = findViewById(R.id.tabSetupContent)
        tabLogsContent = findViewById(R.id.tabLogsContent)

        // Header
        statusDot = findViewById(R.id.statusDot)
        overallStatusText = findViewById(R.id.overallStatusText)

        // Master toggle
        masterToggle = findViewById(R.id.masterToggle)

        // Status tab
        connectionStatusIcon = findViewById(R.id.connectionStatusIcon)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        connectionDetailText = findViewById(R.id.connectionDetailText)
        accessibilityStatusBar = findViewById(R.id.accessibilityStatusBar)
        accessibilityStatusTextView = findViewById(R.id.accessibilityStatusText)
        mcpStatusBar = findViewById(R.id.mcpStatusBar)
        mcpStatusText = findViewById(R.id.mcpStatusText)
        screenshotStatusBar = findViewById(R.id.screenshotStatusBar)
        screenshotStatusText = findViewById(R.id.screenshotStatusText)
        deviceInfoText = findViewById(R.id.deviceInfoText)
        perfP50 = findViewById(R.id.perfP50)
        perfP95 = findViewById(R.id.perfP95)
        perfP99 = findViewById(R.id.perfP99)
        perfCount = findViewById(R.id.perfCount)

        // Setup tab
        permissionProgressLabel = findViewById(R.id.permissionProgressLabel)
        permissionProgressBar = findViewById(R.id.permissionProgressBar)
        permissionCardsContainer = findViewById(R.id.permissionCardsContainer)

        // Logs tab
        logRecyclerView = findViewById(R.id.logRecyclerView)
        logEmptyText = findViewById(R.id.logEmptyText)
    }

    // ========================================================================
    // Tab Switching
    // ========================================================================

    private fun setupTabs() {
        tabStatus.setOnClickListener { switchTab(0) }
        tabSetup.setOnClickListener { switchTab(1) }
        tabLogs.setOnClickListener { switchTab(2) }

        // Set initial tab indicator width
        tabIndicator.post {
            val tabWidth = tabStatus.width
            tabIndicator.layoutParams = tabIndicator.layoutParams.apply {
                width = tabWidth
            }
            tabIndicator.requestLayout()
        }
    }

    private fun switchTab(index: Int) {
        currentTab = index
        val tabs = listOf(tabStatusContent, tabSetupContent, tabLogsContent)
        val tabLabels = listOf(tabStatus, tabSetup, tabLogs)

        tabs.forEachIndexed { i, view ->
            view.visibility = if (i == index) View.VISIBLE else View.GONE
        }

        tabLabels.forEachIndexed { i, tv ->
            tv.setTextColor(getColor(if (i == index) R.color.wave_blue else R.color.text_medium_emphasis))
            tv.setTypeface(null, if (i == index) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        // Animate tab indicator
        val tabWidth = tabStatus.width.toFloat()
        tabIndicator.animate()
            .translationX(tabWidth * index)
            .setDuration(200)
            .start()
    }

    // ========================================================================
    // Master Toggle
    // ========================================================================

    private fun setupMasterToggle() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        masterToggle.isChecked = enabled

        masterToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_ENABLED, isChecked).apply()
            if (isChecked) {
                requestNotificationPermissionIfNeeded()
                NeuralBridgeAccessibilityService.instance?.enable()
            } else {
                NeuralBridgeAccessibilityService.instance?.disable()
            }
            updateServiceStatus()
            updateAllPermissionStatus()
        }
    }

    // ========================================================================
    // Status Tab
    // ========================================================================

    private fun updateServiceStatus() {
        val enabled = masterToggle.isChecked
        val service = NeuralBridgeAccessibilityService.instance
        val isRunning = service != null
        val httpActive = if (enabled) service?.isHttpClientActive() ?: false else false

        // Header
        if (!enabled) {
            statusDot.setBackgroundColor(getColor(R.color.status_inactive))
            overallStatusText.text = "DISABLED"
        } else if (isRunning && isAccessibilityServiceEnabled()) {
            statusDot.setBackgroundResource(R.drawable.bg_status_dot)
            overallStatusText.text = if (httpActive) "ALL SYSTEMS READY" else "WAITING FOR CONNECTION"
        } else {
            statusDot.setBackgroundColor(getColor(R.color.status_error))
            overallStatusText.text = "SETUP INCOMPLETE"
        }

        // Connection hero card
        val httpPort = service?.getMcpHttpPort() ?: McpHttpServer.MCP_PORT
        val wifiIp = McpNetworkUtils.getWifiIpAddress(this) ?: "device-ip"
        if (!enabled) {
            connectionStatusIcon.text = "⬡"
            connectionStatusText.text = "NEURALBRIDGE IS OFF"
            connectionStatusText.setTextColor(getColor(R.color.text_medium_emphasis))
            connectionDetailText.text = "Toggle to enable"
        } else if (httpActive) {
            connectionStatusIcon.text = "📡"
            connectionStatusText.text = "CONNECTED"
            connectionStatusText.setTextColor(getColor(R.color.success))
            connectionDetailText.text = "MCP: http://$wifiIp:$httpPort/mcp"
        } else {
            connectionStatusIcon.text = "📡"
            connectionStatusText.text = "WAITING FOR CONNECTION"
            connectionStatusText.setTextColor(getColor(R.color.text_medium_emphasis))
            connectionDetailText.text = "MCP: http://$wifiIp:$httpPort/mcp"
        }

        // 3-up status grid
        val accessibilityOn = isAccessibilityServiceEnabled()
        accessibilityStatusBar.setBackgroundColor(getColor(if (accessibilityOn) R.color.status_active else R.color.status_inactive))
        accessibilityStatusTextView.text = if (accessibilityOn) "ACTIVE" else "OFF"
        accessibilityStatusTextView.setTextColor(getColor(if (accessibilityOn) R.color.success else R.color.status_error))

        val mcpServerRunning = service?.getMcpHttpActive() ?: false
        mcpStatusBar.setBackgroundColor(getColor(if (mcpServerRunning) R.color.status_active else R.color.status_inactive))
        mcpStatusText.text = if (mcpServerRunning) "RUNNING" else "OFF"
        mcpStatusText.setTextColor(getColor(if (mcpServerRunning) R.color.success else R.color.status_error))

        val screenshotReady = service?.hasMediaProjectionPermission() ?: false
        screenshotStatusBar.setBackgroundColor(getColor(if (screenshotReady) R.color.status_active else R.color.status_inactive))
        screenshotStatusText.text = if (screenshotReady) "FAST" else "ADB"
        screenshotStatusText.setTextColor(getColor(if (screenshotReady) R.color.success else R.color.warning))
    }

    private fun updateDeviceInfo() {
        val dm = resources.displayMetrics
        val info = buildString {
            append("Model: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Screen: ${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi\n")
            append("Density: ${dm.density}x")
            val wifiIp3 = McpNetworkUtils.getWifiIpAddress(this@MainActivity) ?: "no wifi"
            append("\nMCP: http://$wifiIp3:${McpHttpServer.MCP_PORT}/mcp")
        }
        deviceInfoText.text = info
    }

    private fun updatePerformanceStats() {
        val stats = CommandLog.getPerformanceStats()
        if (stats.count > 0) {
            perfP50.text = "${stats.p50}ms"
            perfP95.text = "${stats.p95}ms"
            perfP99.text = "${stats.p99}ms"
            perfCount.text = "${stats.count}"
        } else {
            perfP50.text = "--"
            perfP95.text = "--"
            perfP99.text = "--"
            perfCount.text = "0"
        }
    }

    // ========================================================================
    // Setup Tab
    // ========================================================================

    private fun setupSetupTab() {
        // Create permission cards
        addPermissionCard("AccessibilityService", "Core automation service for UI control and observation") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addPermissionCard("Notification Listener", "Access full notification content") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addPermissionCard("Post Notifications", "Show foreground service notification") {
                requestNotificationPermissionIfNeeded()
            }
        }
        addPermissionCard("Battery Optimization", "Prevent Android from killing the service") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {
                Log.w("MainActivity", "Battery optimization settings not available", e)
            }
        }
        addPermissionCard("MediaProjection", "Enable fast screenshot capture (60ms)") {
            startActivity(Intent(this, com.neuralbridge.companion.screenshot.ScreenshotConsentActivity::class.java))
        }

    }

    private data class PermissionCardViews(
        val container: View,
        val statusIcon: TextView,
        val button: Button
    )

    private val permissionCards = mutableListOf<PermissionCardViews>()

    private fun addPermissionCard(title: String, description: String, action: () -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_permission_card, permissionCardsContainer, false)
        view.findViewById<TextView>(R.id.permissionTitle).text = title
        view.findViewById<TextView>(R.id.permissionDescription).text = description
        val btn = view.findViewById<Button>(R.id.permissionButton)
        btn.setOnClickListener { action() }
        val icon = view.findViewById<TextView>(R.id.permissionStatusIcon)
        permissionCards.add(PermissionCardViews(view, icon, btn))
        permissionCardsContainer.addView(view)
    }

    private fun updateAllPermissionStatus() {
        val statuses = buildList {
            add(isAccessibilityServiceEnabled())
            add(isNotificationListenerEnabled())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(isPostNotificationsGranted())
            add(isBatteryOptimizationExempt())
            add(NeuralBridgeAccessibilityService.instance?.hasMediaProjectionPermission() ?: false)
        }

        val granted = statuses.count { it }
        val total = statuses.size

        permissionProgressLabel.text = "PERMISSIONS $granted/$total"
        permissionProgressBar.max = total
        permissionProgressBar.progress = granted

        permissionCards.forEachIndexed { i, card ->
            val isGranted = statuses[i]
            card.statusIcon.text = if (isGranted) "✓" else "✗"
            card.statusIcon.setTextColor(getColor(if (isGranted) R.color.success else R.color.status_error))
            card.button.text = if (isGranted) "GRANTED" else "GRANT"
            card.button.isEnabled = !isGranted
            card.button.alpha = if (isGranted) 0.5f else 1.0f
        }
    }

    // ========================================================================
    // Logs Tab
    // ========================================================================

    private fun setupLogsTab() {
        logAdapter = LogAdapter()
        logRecyclerView.layoutManager = LinearLayoutManager(this)
        logRecyclerView.adapter = logAdapter

        // Filter spinner
        val spinner = findViewById<Spinner>(R.id.logFilterSpinner)
        val filters = arrayOf("ALL", "GESTURE", "OBSERVE", "MANAGE", "WAIT", "INPUT")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, filters)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                logFilter = filters[pos]
                updateLogEntries()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Clear button
        findViewById<Button>(R.id.btnClearLogs).setOnClickListener {
            CommandLog.clear()
            updateLogEntries()
        }

        // Pause/Resume button
        val pauseBtn = findViewById<Button>(R.id.btnPauseLogs)
        pauseBtn.setOnClickListener {
            logsPaused = !logsPaused
            pauseBtn.text = if (logsPaused) "RESUME" else "PAUSE"
        }
    }

    private fun updateLogEntries() {
        var entries = CommandLog.getRecent(50)
        if (logFilter != "ALL") {
            val category = try { CommandLog.Category.valueOf(logFilter) } catch (e: Exception) { null }
            if (category != null) {
                entries = entries.filter { it.category == category }
            }
        }
        logAdapter.updateEntries(entries)

        val hasEntries = entries.isNotEmpty()
        logRecyclerView.visibility = if (hasEntries) View.VISIBLE else View.GONE
        logEmptyText.visibility = if (hasEntries) View.GONE else View.VISIBLE

        // Auto-scroll to top (newest entries first)
        if (hasEntries && !logsPaused) {
            logRecyclerView.scrollToPosition(0)
        }
    }

    // ========================================================================
    // Permission Checking
    // ========================================================================

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this,
            NeuralBridgeAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val enabledService = ComponentName.unflattenFromString(colonSplitter.next())
            if (enabledService == expectedComponentName) return true
        }
        return false
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners") ?: return false
        return enabledListeners.contains(packageName)
    }

    private fun isPostNotificationsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun isBatteryOptimizationExempt(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_POST_NOTIFICATIONS)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            updateAllPermissionStatus()
        }
    }
}
