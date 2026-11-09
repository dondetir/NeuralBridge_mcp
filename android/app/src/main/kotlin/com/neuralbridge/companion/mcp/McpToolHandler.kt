package com.neuralbridge.companion.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.neuralbridge.companion.gesture.GestureEngine
import com.neuralbridge.companion.input.InputEngine
import com.neuralbridge.companion.notification.NotificationListener
import com.neuralbridge.companion.screenshot.ScreenshotPipeline
import com.neuralbridge.companion.service.AccessibilityEventListener
import com.neuralbridge.companion.service.GestureResultCallback
import com.neuralbridge.companion.service.NeuralBridgeAccessibilityService
import com.neuralbridge.companion.service.ScreenshotQuality
import com.neuralbridge.companion.service.UiElement
import com.neuralbridge.companion.service.UiTree
import com.neuralbridge.companion.uitree.UiTreeWalker
import com.neuralbridge.companion.web.WebViewController
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import android.accessibilityservice.GestureDescription
import kotlin.coroutines.resume

class McpToolHandler(
    private val service: NeuralBridgeAccessibilityService,
    private val gestureEngine: GestureEngine,
    private val uiTreeWalker: UiTreeWalker,
    private val inputEngine: InputEngine,
    private val screenshotPipeline: ScreenshotPipeline,
    private val webViewController: WebViewController
) {
    companion object {
        private const val TAG = "McpToolHandler"
        private const val DEFAULT_TIMEOUT_MS = 10000L
        private const val POLL_INTERVAL_MS = 300L
    }

    suspend fun handleToolCall(toolName: String, arguments: JsonObject?): McpToolCallResult {
        val args = arguments ?: JsonObject(emptyMap())
        return try {
            when (toolName) {
                // OBSERVE
                "android_get_ui_tree" -> handleGetUiTree(args)
                "android_screenshot" -> handleScreenshot(args)
                "android_find_elements" -> handleFindElements(args)
                "android_get_screen_context" -> handleGetScreenContext(args)
                "android_get_notifications" -> handleGetNotifications(args)
                "android_accessibility_audit" -> handleAccessibilityAudit(args)
                "android_screenshot_diff" -> handleScreenshotDiff(args)
                "android_get_recent_toasts" -> handleGetRecentToasts(args)

                // ACT
                "android_tap" -> handleTap(args)
                "android_long_press" -> handleLongPress(args)
                "android_double_tap" -> handleDoubleTap(args)
                "android_swipe" -> handleSwipe(args)
                "android_pinch" -> handlePinch(args)
                "android_drag" -> handleDrag(args)
                "android_input_text" -> handleInputText(args)
                "android_press_key" -> handlePressKey(args)
                "android_global_action" -> handleGlobalAction(args)

                // MANAGE
                "android_launch_app" -> handleLaunchApp(args)
                "android_close_app" -> handleCloseApp(args)
                "android_open_url" -> handleOpenUrl(args)
                "android_set_clipboard" -> handleSetClipboard(args)
                "android_list_apps" -> handleListApps(args)

                // INTENT / IPC
                "android_start_activity" -> handleStartActivity(args)
                "android_send_broadcast" -> handleSendBroadcast(args)
                "android_start_service" -> handleStartService(args, foreground = false)
                "android_start_foreground_service" -> handleStartService(args, foreground = true)
                "android_query_content" -> handleQueryContent(args)

                // SHELL (app-UID; NOT adb shell — see tool description)
                "android_shell" -> handleShell(args)

                // WAIT
                "android_wait_for_element" -> handleWaitForElement(args)
                "android_wait_for_gone" -> handleWaitForGone(args)
                "android_wait_for_idle" -> handleWaitForIdle(args)
                "android_scroll_to_element" -> handleScrollToElement(args)

                // DEVICE (stubs — we are the device)
                "android_list_devices" -> handleListDevices()
                "android_select_device" -> textResult("{\"success\":true,\"message\":\"Already connected to this device\"}")

                // META
                "android_search_tools" -> handleSearchTools(args)
                "android_describe_tools" -> handleDescribeTools(args)

                // TEST
                "android_enable_events" -> handleEnableEvents(args)
                "android_get_device_info" -> handleGetDeviceInfo()

                // WEB (in-process WebView driver — operates only on the app's own WebView)
                "web_navigate" -> handleWebNavigate(args)
                "web_current_url" -> handleWebCurrentUrl()
                "web_eval_js" -> handleWebEvalJs(args)
                "web_query" -> handleWebQuery(args)
                "web_click" -> handleWebClick(args)
                "web_type" -> handleWebType(args)
                "web_get_dom" -> handleWebGetDom(args)
                "web_get_text" -> handleWebGetText(args)
                "web_wait_for" -> handleWebWaitFor(args)
                "web_screenshot" -> handleWebScreenshot(args)

                else -> errorResult("Unknown tool: $toolName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool error: $toolName", e)
            errorResult("Tool error: ${e.message}")
        }
    }

    // =====================================================================
    // GESTURE BRIDGE: callback → suspend
    // =====================================================================

    private suspend fun executeGestureAndWait(block: (GestureResultCallback) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            block(object : GestureResultCallback {
                override fun onCompleted(gesture: GestureDescription) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gesture: GestureDescription) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            // AccessibilityService gestures cannot be cancelled once dispatched;
            // the callback guards with isActive so the result is silently discarded.
            cont.invokeOnCancellation { /* no-op: gesture runs to completion */ }
        }

    // =====================================================================
    // SELECTOR RESOLUTION
    // =====================================================================

    private fun resolveSelector(tree: UiTree, text: String?, resourceId: String?, contentDesc: String?): UiElement? {
        return tree.elements.firstOrNull { e ->
            (text != null && e.text?.contains(text, ignoreCase = true) == true) ||
            (resourceId != null && (e.resourceId?.endsWith(resourceId) == true || e.resourceId == resourceId)) ||
            (contentDesc != null && e.contentDescription?.contains(contentDesc, ignoreCase = true) == true)
        }
    }

    // =====================================================================
    // OBSERVE TOOLS
    // =====================================================================

    private suspend fun handleGetUiTree(args: JsonObject): McpToolCallResult {
        val includeInvisible = args["include_invisible"]?.jsonPrimitive?.booleanOrNull ?: false
        val maxDepth = args["max_depth"]?.jsonPrimitive?.intOrNull ?: 0
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull ?: "interactive"

        val rootNode = service.rootInActiveWindow
            ?: return errorResult("No active window available")

        val tree = uiTreeWalker.walkTree(rootNode, includeInvisible, maxDepth)

        val filtered = when (filter) {
            "all" -> tree.elements
            "text" -> tree.elements.filter { !it.text.isNullOrEmpty() || !it.contentDescription.isNullOrEmpty() }
            else -> tree.elements.filter { it.clickable || it.focusable || it.scrollable || it.checkable || !it.text.isNullOrEmpty() || !it.contentDescription.isNullOrEmpty() }
        }

        val table = buildString {
            append("IDX | resource_id | text | desc | flags | bounds\n")
            filtered.forEachIndexed { idx, e ->
                val flags = buildString {
                    if (e.clickable) append("c")
                    if (e.focusable) append("f")
                    if (e.scrollable) append("s")
                    if (e.checkable) append("k")
                }
                val bounds = e.bounds?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: ""
                append("$idx | ${e.resourceId ?: ""} | ${e.text ?: ""} | ${e.contentDescription ?: ""} | $flags | $bounds\n")
            }
        }

        val result = buildJsonObject {
            put("format", "compact")
            put("app", tree.foregroundApp)
            put("total", tree.totalNodes)
            put("shown", filtered.size)
            put("filter", filter)
            put("elements", table.trimEnd())
        }
        return textResult(result.toString())
    }

    private suspend fun handleScreenshot(args: JsonObject): McpToolCallResult {
        val quality = if (args["quality"]?.jsonPrimitive?.contentOrNull == "thumbnail")
            ScreenshotQuality.THUMBNAIL else ScreenshotQuality.FULL

        val jpegBytes = screenshotPipeline.capture(quality)
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val dm = service.resources.displayMetrics
        val meta = buildJsonObject {
            put("width", dm.widthPixels)
            put("height", dm.heightPixels)
            put("format", "jpeg")
        }.toString()
        return McpToolCallResult(content = listOf(
            McpContentBlock(type = "image", data = base64, mimeType = "image/jpeg"),
            McpContentBlock(type = "text", text = meta)
        ))
    }

    private suspend fun handleFindElements(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
        val className = args["class_name"]?.jsonPrimitive?.contentOrNull
        val findAll = args["find_all"]?.jsonPrimitive?.booleanOrNull ?: false

        if (text == null && resourceId == null && contentDesc == null && className == null) {
            return errorResult("At least one selector (text, resource_id, content_desc, class_name) required")
        }

        val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
        val tree = uiTreeWalker.walkTree(rootNode)

        val matches = tree.elements.filter { e ->
            (text != null && e.text?.contains(text, ignoreCase = true) == true) ||
            (resourceId != null && (e.resourceId?.endsWith(resourceId) == true)) ||
            (contentDesc != null && e.contentDescription?.contains(contentDesc, ignoreCase = true) == true) ||
            (className != null && e.className?.contains(className) == true)
        }.let { if (findAll) it else if (it.isNotEmpty()) listOf(it.first()) else it }

        val result = buildJsonObject {
            putJsonArray("elements") {
                matches.forEach { e ->
                    addJsonObject {
                        put("elementId", e.elementId)
                        e.resourceId?.let { put("resourceId", it) }
                        e.text?.let { put("text", it) }
                        e.contentDescription?.let { put("contentDescription", it) }
                        e.bounds?.let { b -> put("bounds", "[${b.left},${b.top},${b.right},${b.bottom}]") }
                        put("clickable", e.clickable)
                    }
                }
            }
            put("total_matches", matches.size)
        }
        return textResult(result.toString())
    }

    private suspend fun handleGetScreenContext(args: JsonObject): McpToolCallResult {
        val includeAll = args["include_all_elements"]?.jsonPrimitive?.booleanOrNull ?: false
        val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
        val tree = uiTreeWalker.walkTree(rootNode)
        val jpegBytes = screenshotPipeline.capture(ScreenshotQuality.THUMBNAIL)
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val filtered = if (includeAll) tree.elements
        else tree.elements.filter { it.clickable || it.focusable || it.checkable || it.scrollable || !it.text.isNullOrEmpty() }

        val meta = buildJsonObject {
            putJsonObject("app_info") { put("package_name", tree.foregroundApp) }
            putJsonObject("ui_tree") {
                put("total_elements", tree.totalNodes)
                put("filtered_elements", filtered.size)
                putJsonArray("elements") {
                    filtered.forEach { e ->
                        addJsonObject {
                            e.resourceId?.let { put("resourceId", it) }
                            e.text?.let { put("text", it) }
                            e.bounds?.let { b ->
                                put("center_x", (b.left + b.right) / 2)
                                put("center_y", (b.top + b.bottom) / 2)
                                put("bounds", "[${b.left},${b.top},${b.right},${b.bottom}]")
                            }
                        }
                    }
                }
            }
        }.toString()

        return McpToolCallResult(content = listOf(
            McpContentBlock(type = "text", text = meta),
            McpContentBlock(type = "image", data = base64, mimeType = "image/jpeg")
        ))
    }

    private fun handleGetNotifications(args: JsonObject): McpToolCallResult {
        val notifications = NotificationListener.instance?.getActiveNotificationsList() ?: emptyList()
        val result = buildJsonObject {
            putJsonArray("notifications") {
                notifications.forEach { n ->
                    addJsonObject {
                        put("package_name", n.packageName)
                        put("title", n.title)
                        put("text", n.text)
                        put("post_time", n.postTime)
                        put("ongoing", n.ongoing)
                        put("clearable", n.clearable)
                    }
                }
            }
            put("count", notifications.size)
        }
        return textResult(result.toString())
    }

    private suspend fun handleAccessibilityAudit(args: JsonObject): McpToolCallResult {
        val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
        val tree = uiTreeWalker.walkTree(rootNode, includeInvisible = false)

        val issues = mutableListOf<String>()
        tree.elements.forEach { e ->
            if (e.clickable && e.contentDescription.isNullOrEmpty() && e.text.isNullOrEmpty()) {
                val bounds = e.bounds?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "unknown"
                issues.add("Missing content description on clickable element (${e.className ?: "unknown"}) at $bounds")
            }
            e.bounds?.let { b ->
                val width = b.right - b.left
                val height = b.bottom - b.top
                val dm = service.resources.displayMetrics
                val minPx = (48 * dm.density).toInt()
                if (e.clickable && (width < minPx || height < minPx)) {
                    issues.add("Small touch target ${width}x${height}px (min ${minPx}px) on ${e.text ?: e.contentDescription ?: e.className ?: "element"}")
                }
            }
        }

        val result = buildJsonObject {
            put("issues_found", issues.size)
            put("pass", issues.isEmpty())
            putJsonArray("issues") { issues.forEach { add(JsonPrimitive(it)) } }
        }
        return textResult(result.toString())
    }

    private suspend fun handleScreenshotDiff(args: JsonObject): McpToolCallResult {
        val referenceBase64 = args["reference_base64"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("reference_base64 is required")
        val threshold = args["threshold"]?.jsonPrimitive?.doubleOrNull ?: 0.95

        val currentBytes = screenshotPipeline.capture(ScreenshotQuality.THUMBNAIL)
        val referenceBytes = Base64.decode(referenceBase64, Base64.DEFAULT)

        val currentBitmap = android.graphics.BitmapFactory.decodeByteArray(currentBytes, 0, currentBytes.size)
            ?: return errorResult("Failed to decode current screenshot as bitmap")
        val referenceBitmap = android.graphics.BitmapFactory.decodeByteArray(referenceBytes, 0, referenceBytes.size)
            ?: return errorResult("Failed to decode reference_base64 as bitmap")

        // Scale reference to current dimensions if needed
        val scaledRef = if (referenceBitmap.width != currentBitmap.width ||
                            referenceBitmap.height != currentBitmap.height) {
            android.graphics.Bitmap.createScaledBitmap(
                referenceBitmap, currentBitmap.width, currentBitmap.height, true)
        } else referenceBitmap

        val width = currentBitmap.width
        val height = currentBitmap.height
        val currentPixels = IntArray(width * height)
        val refPixels = IntArray(width * height)
        currentBitmap.getPixels(currentPixels, 0, width, 0, 0, width, height)
        scaledRef.getPixels(refPixels, 0, width, 0, 0, width, height)

        // Count pixels within ±10 per channel (RGB)
        var matching = 0L
        for (i in currentPixels.indices) {
            val c1 = currentPixels[i]; val c2 = refPixels[i]
            val rDiff = ((c1 shr 16 and 0xFF) - (c2 shr 16 and 0xFF)).let { if (it < 0) -it else it }
            val gDiff = ((c1 shr 8 and 0xFF) - (c2 shr 8 and 0xFF)).let { if (it < 0) -it else it }
            val bDiff = ((c1 and 0xFF) - (c2 and 0xFF)).let { if (it < 0) -it else it }
            if (rDiff + gDiff + bDiff <= 30) matching++
        }
        val similarity = if (currentPixels.isNotEmpty()) matching.toDouble() / currentPixels.size else 0.0
        val matches = similarity >= threshold

        val result = buildJsonObject {
            put("similarity", similarity)
            put("threshold", threshold)
            put("matches", matches)
        }
        return textResult(result.toString())
    }

    private fun handleGetRecentToasts(args: JsonObject): McpToolCallResult {
        val sinceMs = args["since_ms"]?.jsonPrimitive?.longOrNull ?: 5000L
        val cutoff = System.currentTimeMillis() - sinceMs
        val toasts = NeuralBridgeAccessibilityService.recentToasts.filter { it.second >= cutoff }
        val result = buildJsonObject {
            putJsonArray("toasts") {
                toasts.forEach { (text, time) ->
                    addJsonObject {
                        put("text", text)
                        put("timestamp", time)
                    }
                }
            }
        }
        return textResult(result.toString())
    }

    // =====================================================================
    // ACT TOOLS
    // =====================================================================

    private suspend fun handleTap(args: JsonObject): McpToolCallResult {
        val x = args["x"]?.jsonPrimitive?.intOrNull
        val y = args["y"]?.jsonPrimitive?.intOrNull
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull

        val (tapX, tapY) = if (x != null && y != null) {
            x.toFloat() to y.toFloat()
        } else {
            val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
            val tree = uiTreeWalker.walkTree(rootNode)
            val element = resolveSelector(tree, text, resourceId, contentDesc)
                ?: return errorResult("Element not found: text=$text, resource_id=$resourceId, content_desc=$contentDesc")
            val b = element.bounds ?: return errorResult("Element has no bounds")
            ((b.left + b.right) / 2).toFloat() to ((b.top + b.bottom) / 2).toFloat()
        }

        val success = withTimeoutOrNull(5000L) {
            executeGestureAndWait { cb -> gestureEngine.executeTap(tapX, tapY, cb) }
        } ?: false

        return if (success) textResult("{\"latency_ms\":0}")
        else errorResult("Tap gesture was cancelled or timed out")
    }

    private suspend fun handleLongPress(args: JsonObject): McpToolCallResult {
        val x = args["x"]?.jsonPrimitive?.intOrNull?.toFloat()
        val y = args["y"]?.jsonPrimitive?.intOrNull?.toFloat()
        val durationMs = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 1000L

        val (lx, ly) = if (x != null && y != null) {
            x to y
        } else {
            val text = args["text"]?.jsonPrimitive?.contentOrNull
            val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
            val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
            val tree = uiTreeWalker.walkTree(rootNode)
            val element = resolveSelector(tree, text, resourceId, null)
                ?: return errorResult("Element not found")
            val b = element.bounds ?: return errorResult("Element has no bounds")
            ((b.left + b.right) / 2).toFloat() to ((b.top + b.bottom) / 2).toFloat()
        }

        val success = withTimeoutOrNull(durationMs + 2000L) {
            executeGestureAndWait { cb -> gestureEngine.executeLongPress(lx, ly, durationMs, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Long press cancelled")
    }

    private suspend fun handleDoubleTap(args: JsonObject): McpToolCallResult {
        val x = args["x"]?.jsonPrimitive?.intOrNull?.toFloat()
        val y = args["y"]?.jsonPrimitive?.intOrNull?.toFloat()

        val (dx, dy) = if (x != null && y != null) {
            x to y
        } else {
            val text = args["text"]?.jsonPrimitive?.contentOrNull
            val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
            val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
            val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")
            val tree = uiTreeWalker.walkTree(rootNode)
            val element = resolveSelector(tree, text, resourceId, contentDesc)
                ?: return errorResult("Element not found")
            val b = element.bounds ?: return errorResult("Element has no bounds")
            ((b.left + b.right) / 2).toFloat() to ((b.top + b.bottom) / 2).toFloat()
        }

        val success = withTimeoutOrNull(5000L) {
            executeGestureAndWait { cb -> gestureEngine.executeDoubleTap(dx, dy, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Double tap cancelled")
    }

    private suspend fun handleSwipe(args: JsonObject): McpToolCallResult {
        val startX = args["start_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("start_x required")
        val startY = args["start_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("start_y required")
        val endX = args["end_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("end_x required")
        val endY = args["end_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("end_y required")
        val durationMs = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 300L

        // Clamp the swipe path out of the system gesture-inset zones so a horizontal
        // scroll (e.g. an app toolbar) is not captured by the back/recents/home
        // gestures. (Bug B4) Without this, swipes near the left/right edges or the
        // bottom strip bubble to the system overview instead of the app window.
        val (cStartX, cStartY) = clampOutOfGestureInsets(startX, startY)
        val (cEndX, cEndY) = clampOutOfGestureInsets(endX, endY)

        val success = withTimeoutOrNull(durationMs + 2000L) {
            executeGestureAndWait { cb -> gestureEngine.executeSwipe(cStartX, cStartY, cEndX, cEndY, durationMs, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Swipe cancelled")
    }

    /**
     * Clamp a point inward so it avoids Android's system gesture-inset regions:
     *  - left/right edges  → back gesture
     *  - bottom strip      → home/recents gesture
     *  - top strip         → status bar / notification pull
     * Uses a conservative inset derived from display density (~24dp edges, ~48dp bottom).
     */
    private fun clampOutOfGestureInsets(x: Float, y: Float): Pair<Float, Float> {
        val dm = service.resources.displayMetrics
        val edge = (24 * dm.density)
        val bottom = (48 * dm.density)
        val top = (24 * dm.density)
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val cx = x.coerceIn(edge, (w - edge).coerceAtLeast(edge))
        val cy = y.coerceIn(top, (h - bottom).coerceAtLeast(top))
        return cx to cy
    }

    private suspend fun handlePinch(args: JsonObject): McpToolCallResult {
        val centerX = args["center_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("center_x required")
        val centerY = args["center_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("center_y required")
        val scale = args["scale"]?.jsonPrimitive?.floatOrNull ?: return errorResult("scale required")
        val durationMs = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 300L

        val success = withTimeoutOrNull(durationMs + 2000L) {
            executeGestureAndWait { cb -> gestureEngine.executePinch(centerX, centerY, scale, durationMs, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Pinch cancelled")
    }

    private suspend fun handleDrag(args: JsonObject): McpToolCallResult {
        val fromX = args["from_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("from_x required")
        val fromY = args["from_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("from_y required")
        val toX = args["to_x"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("to_x required")
        val toY = args["to_y"]?.jsonPrimitive?.intOrNull?.toFloat() ?: return errorResult("to_y required")
        val durationMs = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 1000L

        val success = withTimeoutOrNull(durationMs + 2000L) {
            executeGestureAndWait { cb -> gestureEngine.executeDrag(fromX, fromY, toX, toY, durationMs, cb) }
        } ?: false
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Drag cancelled")
    }

    private suspend fun handleInputText(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return errorResult("text required")
        val append = args["append"]?.jsonPrimitive?.booleanOrNull ?: false
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val elementText = args["element_text"]?.jsonPrimitive?.contentOrNull

        val rootNode = service.rootInActiveWindow ?: return errorResult("No active window")

        // Resolve the target editable node.
        val nodeInfo: android.view.accessibility.AccessibilityNodeInfo = if (resourceId != null || elementText != null) {
            // Selector given: resolve via UI tree, then re-locate the concrete node.
            val tree = uiTreeWalker.walkTree(rootNode)
            val target = resolveSelector(tree, elementText, resourceId, null)
                ?: return errorResult("Element not found: resource_id=$resourceId, text=$elementText")
            findNodeByElement(rootNode, target.resourceId, target.text)
                ?: return errorResult("Could not get node reference for element: resource_id=$resourceId, text=$elementText")
        } else {
            // No selector: target the actually-focused input field directly.
            // This handles id-less / text-less EditTexts (e.g. terminal/IME-backed inputs)
            // that cannot be re-located by resource_id or text. (Bug B1)
            rootNode.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                ?: run {
                    // Fallback: first editable-looking node in the tree.
                    val tree = uiTreeWalker.walkTree(rootNode)
                    val guess = tree.elements.firstOrNull { e ->
                        e.focusable && (
                            e.className?.contains("EditText") == true ||
                            e.className?.contains("TextField") == true ||
                            e.className == "android.widget.AutoCompleteTextView" ||
                            e.className?.contains("SearchView") == true
                        )
                    }
                    if (guess != null) findNodeByElement(rootNode, guess.resourceId, guess.text) else null
                }
                ?: return errorResult("No focused input field found. Tap the field first, or specify resource_id or element_text.")
        }

        val success = inputEngine.inputText(nodeInfo, text, append)
        @Suppress("DEPRECATION")
        nodeInfo.recycle()
        return if (success) textResult("{\"latency_ms\":0}") else errorResult("Input text failed (field may reject programmatic input, e.g. a secure IME)")
    }

    private fun findNodeByElement(
        root: android.view.accessibility.AccessibilityNodeInfo,
        resourceId: String?,
        text: String?
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (resourceId != null) {
            val results = root.findAccessibilityNodeInfosByViewId(resourceId)
            if (results.isNotEmpty()) return results.first()
        }
        if (text != null) {
            val results = root.findAccessibilityNodeInfosByText(text)
            if (results.isNotEmpty()) return results.first()
        }
        return null
    }

    private fun handlePressKey(args: JsonObject): McpToolCallResult {
        val key = args["key"]?.jsonPrimitive?.contentOrNull ?: return errorResult("key required")

        // Global actions first (don't need a focused node)
        val globalAction = when (key.lowercase()) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "power" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> null
        }
        if (globalAction != null) {
            service.performGlobalAction(globalAction)
            return textResult("{\"key\":\"$key\",\"latency_ms\":0}")
        }

        // For other keys, delegate to InputEngine
        val focusedNode = service.rootInActiveWindow?.findFocus(
            android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
        )

        // Keys that require a focused input field.
        val focusRequiredKeys = setOf(
            "enter", "delete", "backspace", "tab", "escape", "space",
            "select_all", "cut", "copy", "paste"
        )
        if (key.lowercase() in focusRequiredKeys && focusedNode == null) {
            return errorResult(
                "Key '$key' requires a focused input field, but none is focused. " +
                "Tap the input field first. Note: a secure IME (e.g. a password/terminal " +
                "secure keyboard) may not expose focus to accessibility, in which case key " +
                "injection is not possible — use set_clipboard + the app's paste affordance."
            )
        }

        val result = inputEngine.pressKeyDetailed(key, focusedNode)
        @Suppress("DEPRECATION")
        focusedNode?.recycle()
        if (!result.success) {
            return errorResult(
                "Key '$key' failed (${result.mechanism}): ${result.note ?: "rejected by focused field"}. " +
                "Global keys: back, home, recents, notifications, power. " +
                "Focused-field keys: enter, delete/backspace, tab, escape, space, select_all, cut, copy, paste."
            )
        }
        // Report honestly: 'verified' distinguishes a confirmed effect from a
        // fire-and-forget dispatch (e.g. ACTION_IME_ENTER). (Bug N3)
        val json = buildJsonObject {
            put("key", key)
            put("mechanism", result.mechanism)
            put("verified", result.verified)
            result.note?.let { put("note", it) }
            put("latency_ms", 0)
        }
        return textResult(json.toString())
    }

    private fun handleGlobalAction(args: JsonObject): McpToolCallResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return errorResult("action required")
        val globalAction = when (action.lowercase()) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return errorResult("Unknown action: $action. Valid: back, home, recents, notifications, quick_settings")
        }
        val success = service.performGlobalAction(globalAction)
        return if (success) textResult("{\"action\":\"$action\",\"latency_ms\":0}")
        else errorResult("Global action '$action' failed")
    }

    // =====================================================================
    // MANAGE TOOLS
    // =====================================================================

    private fun handleLaunchApp(args: JsonObject): McpToolCallResult {
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull ?: return errorResult("package_name required")
        val clearTask = args["clear_task"]?.jsonPrimitive?.booleanOrNull ?: false

        val pm = service.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: return errorResult("No launch intent found for package: $packageName")
        if (clearTask) launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        else launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(launchIntent)
        return textResult("{\"package_name\":\"$packageName\",\"latency_ms\":0}")
    }

    private fun handleCloseApp(args: JsonObject): McpToolCallResult {
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        return textResult("{\"success\":true,\"note\":\"Moved to background via HOME.\"}")
    }

    private fun handleOpenUrl(args: JsonObject): McpToolCallResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return errorResult("url required")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        service.startActivity(intent)
        return textResult("{\"url\":\"$url\",\"latency_ms\":0}")
    }

    // =====================================================================
    // INTENT / IPC TOOLS (run at the app's own UID/permissions)
    // =====================================================================

    /** Build an Intent from common args: action, data(uri), mime_type, package, component, categories[], extras{}, flags[]. */
    private fun buildIntentFromArgs(args: JsonObject): Intent {
        val intent = Intent()
        args["action"]?.jsonPrimitive?.contentOrNull?.let { intent.action = it }
        val data = args["data"]?.jsonPrimitive?.contentOrNull
        val mime = args["mime_type"]?.jsonPrimitive?.contentOrNull
        if (data != null && mime != null) intent.setDataAndType(Uri.parse(data), mime)
        else if (data != null) intent.data = Uri.parse(data)
        else if (mime != null) intent.type = mime
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull
        val component = args["component"]?.jsonPrimitive?.contentOrNull
        if (component != null) {
            // "pkg/cls" or "pkg/.cls"
            val parts = component.split("/")
            if (parts.size == 2) {
                val p = parts[0]
                val c = if (parts[1].startsWith(".")) p + parts[1] else parts[1]
                intent.setClassName(p, c)
            }
        } else if (pkg != null) {
            intent.setPackage(pkg)
        }
        args["categories"]?.jsonArray?.forEach { c ->
            c.jsonPrimitive.contentOrNull?.let { intent.addCategory(it) }
        }
        // flags: array of ints (OR'd together)
        args["flags"]?.jsonArray?.forEach { f ->
            f.jsonPrimitive.intOrNull?.let { intent.addFlags(it) }
        }
        applyExtras(intent, args["extras"] as? JsonObject)
        return intent
    }

    /** Apply typed extras. Each entry is {"type":"string|int|long|float|bool","value":...}, or a bare primitive (inferred). */
    private fun applyExtras(intent: Intent, extras: JsonObject?) {
        extras ?: return
        for ((key, v) in extras) {
            when (v) {
                is JsonObject -> {
                    val type = v["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    val value = v["value"]?.jsonPrimitive
                    when (type) {
                        "string" -> intent.putExtra(key, value?.contentOrNull)
                        "int" -> value?.intOrNull?.let { intent.putExtra(key, it) }
                        "long" -> value?.longOrNull?.let { intent.putExtra(key, it) }
                        "float" -> value?.floatOrNull?.let { intent.putExtra(key, it) }
                        "bool", "boolean" -> value?.booleanOrNull?.let { intent.putExtra(key, it) }
                        else -> intent.putExtra(key, value?.contentOrNull)
                    }
                }
                is JsonPrimitive -> {
                    when {
                        v.booleanOrNull != null && (v.content == "true" || v.content == "false") -> intent.putExtra(key, v.boolean)
                        v.intOrNull != null -> intent.putExtra(key, v.int)
                        v.longOrNull != null -> intent.putExtra(key, v.long)
                        v.floatOrNull != null -> intent.putExtra(key, v.float)
                        else -> intent.putExtra(key, v.contentOrNull)
                    }
                }
                else -> {}
            }
        }
    }

    private fun handleStartActivity(args: JsonObject): McpToolCallResult {
        return try {
            val intent = buildIntentFromArgs(args)
            // Starting from a service context requires NEW_TASK.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            textResult("{\"success\":true,\"action\":\"${intent.action ?: ""}\"}")
        } catch (e: Exception) {
            errorResult("start_activity failed: ${e.message} (target may not be exported, require a permission the app lacks, or be blocked for background-activity-launch)")
        }
    }

    private fun handleSendBroadcast(args: JsonObject): McpToolCallResult {
        return try {
            val intent = buildIntentFromArgs(args)
            val permission = args["permission"]?.jsonPrimitive?.contentOrNull
            if (permission != null) service.sendBroadcast(intent, permission)
            else service.sendBroadcast(intent)
            textResult("{\"success\":true,\"action\":\"${intent.action ?: ""}\"}")
        } catch (e: Exception) {
            errorResult("send_broadcast failed: ${e.message}")
        }
    }

    private fun handleStartService(args: JsonObject, foreground: Boolean): McpToolCallResult {
        return try {
            val intent = buildIntentFromArgs(args)
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                service.startForegroundService(intent)
            } else {
                service.startService(intent)
            }
            textResult("{\"success\":true,\"foreground\":$foreground}")
        } catch (e: Exception) {
            errorResult("start_service failed: ${e.message} (target service may not be exported or may require a permission the app lacks)")
        }
    }

    private fun handleQueryContent(args: JsonObject): McpToolCallResult {
        val uriStr = args["uri"]?.jsonPrimitive?.contentOrNull ?: return errorResult("uri required (e.g. content://...)")
        val projection = args["projection"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toTypedArray()
        val selection = args["selection"]?.jsonPrimitive?.contentOrNull
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50
        return try {
            val cursor = service.contentResolver.query(Uri.parse(uriStr), projection, selection, null, null)
                ?: return errorResult("query returned null (provider not accessible, not found, or permission denied)")
            cursor.use { c ->
                val cols = c.columnNames
                val rows = buildJsonArray {
                    var n = 0
                    while (c.moveToNext() && n < limit) {
                        add(buildJsonObject {
                            for (i in cols.indices) {
                                put(cols[i], try { c.getString(i) ?: "" } catch (e: Exception) { "<non-text>" })
                            }
                        })
                        n++
                    }
                }
                val result = buildJsonObject {
                    putJsonArray("columns") { cols.forEach { add(JsonPrimitive(it)) } }
                    put("rows", rows)
                    put("returned", rows.size)
                }
                textResult(result.toString())
            }
        } catch (e: SecurityException) {
            errorResult("query_content denied: ${e.message} (the app lacks permission to read this provider)")
        } catch (e: Exception) {
            errorResult("query_content failed: ${e.message}")
        }
    }

    // =====================================================================
    // SHELL TOOL
    // =====================================================================

    /**
     * Run a command at the APP's UID (NOT the shell/root UID). This is an unprivileged
     * app sandbox: `am`, `pm`, `settings`, `input`, `dumpsys`, reading other apps' data,
     * etc. will fail with permission denied. For a real Android shell (shell UID) use
     * `adb shell` over the device connection instead. Returned: stdout, stderr, exit code.
     */
    private suspend fun handleShell(args: JsonObject): McpToolCallResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: return errorResult("command required")
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 10000L
        return withContext(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(false)
                    .start()
                val out = StringBuilder(); val err = StringBuilder()
                val oT = Thread { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
                val eT = Thread { proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
                oT.start(); eT.start()
                val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) { proc.destroyForcibly(); return@withContext errorResult("shell command timed out after ${timeoutMs}ms") }
                oT.join(1000); eT.join(1000)
                val code = proc.exitValue()
                val cap = 8000
                val result = buildJsonObject {
                    put("exit_code", code)
                    put("stdout", out.toString().let { if (it.length > cap) it.substring(0, cap) + "\n<truncated>" else it }.trimEnd())
                    put("stderr", err.toString().let { if (it.length > cap) it.substring(0, cap) + "\n<truncated>" else it }.trimEnd())
                    put("uid_note", "runs as app UID (unprivileged); am/pm/settings/input require adb shell")
                }
                textResult(result.toString())
            } catch (e: Exception) {
                errorResult("shell exec failed: ${e.message}")
            }
        }
    }

    private fun handleSetClipboard(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return errorResult("text required")
        val verified = inputEngine.setClipboardText(text)
        return if (verified) {
            textResult("{\"success\":true,\"verified\":true}")
        } else {
            // Honest failure: the OS likely rejected a background clipboard write.
            errorResult(
                "Clipboard write could not be confirmed (read-back mismatch). On Android 10+, " +
                "the system restricts clipboard writes from apps that are not the focused app / " +
                "default IME, so a stale value may remain. Bring the field to focus, or type " +
                "directly with input_text instead of relying on clipboard+paste."
            )
        }
    }

    private fun handleListApps(args: JsonObject): McpToolCallResult {
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull ?: "all"
        val pm = service.packageManager
        val packages = pm.getInstalledPackages(0)
        val filtered = when (filter) {
            "system" -> packages.filter { (it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 }
            "third_party" -> packages.filter { (it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            else -> packages
        }
        val result = buildJsonObject {
            putJsonArray("apps") {
                filtered.forEach { pkg ->
                    addJsonObject {
                        put("package_name", pkg.packageName)
                        put("version_name", pkg.versionName ?: "")
                        put("is_system", (pkg.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                    }
                }
            }
            put("count", filtered.size)
        }
        return textResult(result.toString())
    }

    // =====================================================================
    // WAIT TOOLS
    // =====================================================================

    private suspend fun handleWaitForElement(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                val tree = uiTreeWalker.walkTree(rootNode)
                val found = resolveSelector(tree, text, resourceId, contentDesc)
                if (found != null) {
                    return textResult("{\"found\":true,\"element\":{\"text\":\"${found.text}\",\"bounds\":\"${found.bounds}\"}}")
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        return textResult("{\"found\":false,\"timeout_ms\":$timeoutMs}")
    }

    private suspend fun handleWaitForGone(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val rootNode = service.rootInActiveWindow
            if (rootNode == null) return textResult("{\"found\":false}")
            val tree = uiTreeWalker.walkTree(rootNode)
            val found = resolveSelector(tree, text, resourceId, contentDesc)
            if (found == null) return textResult("{\"found\":false}")
            delay(POLL_INTERVAL_MS)
        }
        return textResult("{\"found\":true,\"note\":\"Element still present after ${timeoutMs}ms\"}")
    }

    private suspend fun handleWaitForIdle(args: JsonObject): McpToolCallResult {
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L
        var lastEventTime = System.currentTimeMillis()
        val idleThreshold = 500L

        val listener = object : AccessibilityEventListener {
            override fun onEvent(event: android.view.accessibility.AccessibilityEvent) {
                lastEventTime = System.currentTimeMillis()
            }
        }
        service.registerEventListener(listener)
        return try {
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    delay(idleThreshold)
                    if (System.currentTimeMillis() - lastEventTime >= idleThreshold) break
                }
            }
            textResult("{\"idle\":true}")
        } finally {
            // Always unregister — covers normal exit, timeout, and coroutine cancellation
            service.unregisterEventListener(listener)
        }
    }

    private suspend fun handleScrollToElement(args: JsonObject): McpToolCallResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        val resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull
        val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
        val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
        val maxScrolls = args["max_scrolls"]?.jsonPrimitive?.intOrNull ?: 20

        val dm = service.resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val startY = if (direction == "down") dm.heightPixels * 0.7f else dm.heightPixels * 0.3f
        val endY = if (direction == "down") dm.heightPixels * 0.3f else dm.heightPixels * 0.7f

        repeat(maxScrolls) { scrollCount ->
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                val tree = uiTreeWalker.walkTree(rootNode)
                val found = resolveSelector(tree, text, resourceId, contentDesc)
                if (found != null) return textResult("{\"found\":true,\"scrolls\":$scrollCount}")
            }
            withTimeoutOrNull(1000L) {
                executeGestureAndWait { cb -> gestureEngine.executeSwipe(centerX, startY, centerX, endY, 300L, cb) }
            }
            delay(200)
        }
        return textResult("{\"found\":false,\"scrolls\":$maxScrolls}")
    }

    // =====================================================================
    // DEVICE / META / TEST TOOLS
    // =====================================================================

    private fun handleListDevices(): McpToolCallResult {
        val dm = service.resources.displayMetrics
        val result = buildJsonObject {
            putJsonArray("devices") {
                addJsonObject {
                    put("device_id", "local")
                    put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("android_version", Build.VERSION.RELEASE)
                    put("sdk_int", Build.VERSION.SDK_INT)
                    put("screen_width", dm.widthPixels)
                    put("screen_height", dm.heightPixels)
                    put("status", "connected")
                    put("all_permissions_ready", true)
                }
            }
        }
        return textResult(result.toString())
    }

    private fun handleSearchTools(args: JsonObject): McpToolCallResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return errorResult("query required")
        val category = args["category"]?.jsonPrimitive?.contentOrNull
        val allTools = McpToolRegistry.getAllTools()
        val matches = allTools.filter { tool ->
            (tool.name.contains(query, ignoreCase = true) || tool.description.contains(query, ignoreCase = true)) &&
            (category == null || tool.name.contains(category, ignoreCase = true))
        }
        val result = buildJsonObject {
            putJsonArray("tools") {
                matches.forEach { t ->
                    addJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                    }
                }
            }
            put("count", matches.size)
        }
        return textResult(result.toString())
    }

    private fun handleDescribeTools(args: JsonObject): McpToolCallResult {
        val toolNames = args["tools"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val result = buildJsonObject {
            putJsonArray("tools") {
                toolNames.forEach { name ->
                    val tool = McpToolRegistry.getTool(name)
                    if (tool != null) {
                        addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                        }
                    }
                }
            }
        }
        return textResult(result.toString())
    }

    private fun handleEnableEvents(args: JsonObject): McpToolCallResult {
        val enable = args["enable"]?.jsonPrimitive?.booleanOrNull ?: return errorResult("enable (boolean) required")
        service.setEventsEnabled(enable)
        return textResult("{\"events_enabled\":$enable}")
    }

    private fun handleGetDeviceInfo(): McpToolCallResult {
        val dm = service.resources.displayMetrics
        val real = realDisplaySize()
        val result = buildJsonObject {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            // App-content area (excludes system bars).
            put("screen_width", dm.widthPixels)
            put("screen_height", dm.heightPixels)
            // Real full display frame — this is the coordinate space that UI-tree
            // bounds (getBoundsInScreen) and tap/swipe gestures (dispatchGesture)
            // actually use. Use these for pixel-ratio hit-testing. (Bug B5)
            put("display_width", real.first)
            put("display_height", real.second)
            put("density_dpi", dm.densityDpi)
            put("density", dm.density)
        }
        return textResult(result.toString())
    }

    /**
     * Real full-display size in pixels, including system decoration — the frame that
     * accessibility bounds and dispatchGesture coordinates are expressed in.
     */
    private fun realDisplaySize(): Pair<Int, Int> {
        return try {
            val wm = service.getSystemService(android.view.WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val dm = service.resources.displayMetrics
                dm.widthPixels to dm.heightPixels
            }
        } catch (e: Exception) {
            val dm = service.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    // =====================================================================
    // WEB TOOLS (in-process WebView — operates only on the app's own WebView)
    // =====================================================================

    private suspend fun handleWebNavigate(args: JsonObject): McpToolCallResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return errorResult("url required")
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 30000L
        val result = webViewController.navigate(url, timeoutMs)
        val json = buildJsonObject {
            put("success", result.success)
            put("url", result.url ?: url)
            result.errorMessage?.let { put("error", it) }
            put("title", webViewController.title() ?: "")
        }
        return if (result.success) textResult(json.toString())
        else McpToolCallResult(content = listOf(McpContentBlock(type = "text", text = json.toString())), isError = true)
    }

    private suspend fun handleWebCurrentUrl(): McpToolCallResult {
        val result = buildJsonObject {
            put("url", webViewController.currentUrl() ?: "")
            put("title", webViewController.title() ?: "")
        }
        return textResult(result.toString())
    }

    private suspend fun handleWebEvalJs(args: JsonObject): McpToolCallResult {
        val script = args["script"]?.jsonPrimitive?.contentOrNull ?: return errorResult("script required")
        val raw = webViewController.evaluate(script)
        val result = buildJsonObject { put("result", raw) }
        return textResult(result.toString())
    }

    private suspend fun handleWebQuery(args: JsonObject): McpToolCallResult {
        val selector = args["selector"]?.jsonPrimitive?.contentOrNull ?: return errorResult("selector required")
        val all = args["all"]?.jsonPrimitive?.booleanOrNull ?: false
        val elementsJson = webViewController.query(selector, all)
        // elementsJson is already a JSON array string produced in-page; embed verbatim.
        return textResult("{\"selector\":${jsonString(selector)},\"elements\":$elementsJson}")
    }

    private suspend fun handleWebClick(args: JsonObject): McpToolCallResult {
        val selector = args["selector"]?.jsonPrimitive?.contentOrNull ?: return errorResult("selector required")
        val clicked = webViewController.click(selector)
        return textResult("{\"found\":$clicked}")
    }

    private suspend fun handleWebType(args: JsonObject): McpToolCallResult {
        val selector = args["selector"]?.jsonPrimitive?.contentOrNull ?: return errorResult("selector required")
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return errorResult("text required")
        val ok = webViewController.type(selector, text)
        return if (ok) textResult("{\"success\":true}") else errorResult("Element not found or not editable: $selector")
    }

    private suspend fun handleWebGetDom(args: JsonObject): McpToolCallResult {
        val selector = args["selector"]?.jsonPrimitive?.contentOrNull
        val maxChars = args["max_chars"]?.jsonPrimitive?.intOrNull ?: 20000
        val dom = webViewController.getDom(selector, maxChars)
            ?: return errorResult("No element matched selector: $selector")
        return textResult(dom)
    }

    private suspend fun handleWebGetText(args: JsonObject): McpToolCallResult {
        val selector = args["selector"]?.jsonPrimitive?.contentOrNull
        val maxChars = args["max_chars"]?.jsonPrimitive?.intOrNull ?: 20000
        val text = webViewController.getText(selector, maxChars)
            ?: return errorResult("No element matched selector: $selector")
        return textResult(text)
    }

    private suspend fun handleWebWaitFor(args: JsonObject): McpToolCallResult {
        val selector = args["selector"]?.jsonPrimitive?.contentOrNull ?: return errorResult("selector required")
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L
        val found = webViewController.waitFor(selector, timeoutMs)
        return textResult("{\"found\":$found,\"timeout_ms\":$timeoutMs}")
    }

    private suspend fun handleWebScreenshot(args: JsonObject): McpToolCallResult {
        val quality = args["quality"]?.jsonPrimitive?.intOrNull ?: 80
        val bytes = webViewController.screenshot(quality)
            ?: return errorResult("WebView not initialized or capture failed")
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return McpToolCallResult(content = listOf(
            McpContentBlock(type = "image", data = base64, mimeType = "image/jpeg"),
            McpContentBlock(type = "text", text = "{\"format\":\"jpeg\"}")
        ))
    }

    /** Encode a Kotlin string as a JSON string literal for safe embedding in a response. */
    private fun jsonString(s: String): String = JsonPrimitive(s).toString()

}
