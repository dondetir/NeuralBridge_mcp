package com.neuralbridge.companion.service

import android.os.Bundle
import android.util.Log
import com.neuralbridge.companion.gesture.GestureEngine
import com.neuralbridge.companion.input.InputEngine
import com.neuralbridge.companion.uitree.UiTreeWalker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import neuralbridge.Neuralbridge
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Command Handler
 *
 * Routes incoming protobuf Request messages to appropriate execution engines
 * and builds Response messages with proper error handling and latency tracking.
 *
 * Phase 1 implementation includes:
 * - get_ui_tree: Walk accessibility tree and convert to protobuf
 * - tap: Coordinate-based tap gesture (no selectors yet)
 * - swipe: Linear swipe gesture
 * - input_text: Text input via InputEngine
 */
class CommandHandler(
    private val accessibilityService: NeuralBridgeAccessibilityService,
    private val gestureEngine: GestureEngine,
    private val uiTreeWalker: UiTreeWalker,
    private val inputEngine: InputEngine
) {
    companion object {
        private const val TAG = "CommandHandler"
        private const val DEFAULT_GESTURE_TIMEOUT_MS = 5000L
    }

    /**
     * Handle incoming request and produce response
     *
     * @param request Protobuf request message
     * @return Protobuf response message
     */
    suspend fun handleRequest(request: Neuralbridge.Request): Neuralbridge.Response {
        val startTime = System.currentTimeMillis()
        val requestId = request.requestId

        Log.d(TAG, "Handling request: $requestId, command: ${request.commandCase}")

        return try {
            val response = when (request.commandCase) {
                Neuralbridge.Request.CommandCase.GET_UI_TREE -> {
                    handleGetUiTree(requestId, request.getUiTree)
                }
                Neuralbridge.Request.CommandCase.SCREENSHOT -> {
                    handleScreenshot(requestId, request.screenshot)
                }
                Neuralbridge.Request.CommandCase.TAP -> {
                    handleTap(requestId, request.tap)
                }
                Neuralbridge.Request.CommandCase.SWIPE -> {
                    handleSwipe(requestId, request.swipe)
                }
                Neuralbridge.Request.CommandCase.INPUT_TEXT -> {
                    handleInputText(requestId, request.inputText)
                }
                Neuralbridge.Request.CommandCase.LONG_PRESS -> {
                    handleLongPress(requestId, request.longPress)
                }
                Neuralbridge.Request.CommandCase.PRESS_KEY -> {
                    handlePressKey(requestId, request.pressKey)
                }
                Neuralbridge.Request.CommandCase.GLOBAL_ACTION -> {
                    handleGlobalAction(requestId, request.globalAction)
                }
                Neuralbridge.Request.CommandCase.WAIT_FOR_ELEMENT -> {
                    handleWaitForElement(requestId, request.waitForElement)
                }
                Neuralbridge.Request.CommandCase.WAIT_FOR_IDLE -> {
                    handleWaitForIdle(requestId, request.waitForIdle)
                }
                Neuralbridge.Request.CommandCase.WAIT_FOR_GONE -> {
                    handleWaitForGone(requestId, request.waitForGone)
                }
                Neuralbridge.Request.CommandCase.FIND_ELEMENTS -> {
                    handleFindElements(requestId, request.findElements)
                }
                Neuralbridge.Request.CommandCase.GET_FOREGROUND_APP -> {
                    handleGetForegroundApp(requestId, request.getForegroundApp)
                }
                Neuralbridge.Request.CommandCase.LAUNCH_APP -> {
                    handleLaunchApp(requestId, request.launchApp)
                }
                Neuralbridge.Request.CommandCase.OPEN_URL -> {
                    handleOpenUrl(requestId, request.openUrl)
                }
                Neuralbridge.Request.CommandCase.ENABLE_EVENTS -> {
                    handleEnableEvents(requestId, request.enableEvents)
                }
                Neuralbridge.Request.CommandCase.GET_NOTIFICATIONS -> {
                    handleGetNotifications(requestId, request.getNotifications)
                }
                Neuralbridge.Request.CommandCase.DOUBLE_TAP -> {
                    handleDoubleTap(requestId, request.doubleTap)
                }
                Neuralbridge.Request.CommandCase.PINCH -> {
                    handlePinch(requestId, request.pinch)
                }
                Neuralbridge.Request.CommandCase.DRAG -> {
                    handleDrag(requestId, request.drag)
                }
                Neuralbridge.Request.CommandCase.FLING -> {
                    handleFling(requestId, request.fling)
                }
                Neuralbridge.Request.CommandCase.SET_CLIPBOARD -> {
                    handleSetClipboard(requestId, request.setClipboard)
                }
                Neuralbridge.Request.CommandCase.CLOSE_APP -> {
                    handleCloseApp(requestId, request.closeApp)
                }
                else -> {
                    buildErrorResponse(
                        requestId = requestId,
                        errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                        errorMessage = "Unsupported command: ${request.commandCase}"
                    )
                }
            }

            // Add latency to response
            val latencyMs = System.currentTimeMillis() - startTime
            response.toBuilder()
                .setLatencyMs(latencyMs)
                .build()

        } catch (e: Exception) {
            Log.e(TAG, "Error handling request: $requestId", e)
            val latencyMs = System.currentTimeMillis() - startTime
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                errorMessage = "Internal error: ${e.message}",
                latencyMs = latencyMs
            )
        }
    }

    /**
     * Handle get_ui_tree request
     *
     * Walks accessibility tree and converts to protobuf UITree message
     */
    private suspend fun handleGetUiTree(
        requestId: String,
        request: Neuralbridge.GetUITreeRequest
    ): Neuralbridge.Response {
        Log.d(TAG, "Getting UI tree (includeInvisible=${request.includeInvisible}, maxDepth=${request.maxDepth})")

        val rootNode = accessibilityService.rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "Root node is null")
            return buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                errorMessage = "No active window available"
            )
        }

        // Walk the tree
        val uiTree = uiTreeWalker.walkTree(
            rootNode = rootNode,
            includeInvisible = request.includeInvisible,
            maxDepth = request.maxDepth
        )

        // Convert to protobuf
        val protoTree = convertToProtoTree(uiTree)

        return Neuralbridge.Response.newBuilder()
            .setRequestId(requestId)
            .setSuccess(true)
            .setUiTree(protoTree)
            .build()
    }

    /**
     * Handle screenshot request
     *
     * Captures screenshot via MediaProjection and returns JPEG-encoded bytes
     */
    private suspend fun handleScreenshot(
        requestId: String,
        request: Neuralbridge.ScreenshotRequest
    ): Neuralbridge.Response {
        val startTime = System.currentTimeMillis()

        // Convert protobuf quality to internal enum
        val quality = when (request.quality) {
            Neuralbridge.ScreenshotQuality.FULL -> ScreenshotQuality.FULL
            Neuralbridge.ScreenshotQuality.THUMBNAIL -> ScreenshotQuality.THUMBNAIL
            else -> ScreenshotQuality.FULL // Default to FULL
        }

        Log.d(TAG, "Capturing screenshot (quality=${quality}, useAdbFallback=${request.useAdbFallback})")

        // If useAdbFallback is requested, skip MediaProjection and tell MCP server to use ADB
        if (request.useAdbFallback) {
            return buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.UNSUPPORTED_OPERATION,
                errorMessage = "ADB fallback requested - MCP server should use: adb exec-out screencap -p"
            )
        }

        return try {
            // Capture screenshot via ScreenshotPipeline
            val jpegBytes = accessibilityService.captureScreenshot(quality)

            // Get screen dimensions
            val displayMetrics = accessibilityService.resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels

            val captureTime = System.currentTimeMillis() - startTime

            Log.i(TAG, "Screenshot captured: ${jpegBytes.size} bytes, ${width}x${height}, ${captureTime}ms")

            // Build ScreenshotResult
            val result = Neuralbridge.ScreenshotResult.newBuilder()
                .setImageData(com.google.protobuf.ByteString.copyFrom(jpegBytes))
                .setWidth(width)
                .setHeight(height)
                .setFormat(Neuralbridge.ImageFormat.JPEG)
                .setCaptureTimeMs(captureTime)
                .build()

            Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setScreenshotResult(result)
                .build()

        } catch (e: NotImplementedError) {
            // MediaProjection not implemented yet or consent not granted
            Log.e(TAG, "Screenshot failed: ${e.message}")
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.PERMISSION_DENIED,
                errorMessage = "MediaProjection requires user consent. MCP server should use ADB fallback: adb exec-out screencap -p"
            )
        } catch (e: SecurityException) {
            // MediaProjection consent denied
            Log.e(TAG, "MediaProjection permission denied", e)
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.PERMISSION_DENIED,
                errorMessage = "MediaProjection permission denied. MCP server should use ADB fallback: adb exec-out screencap -p"
            )
        } catch (e: Exception) {
            // Other screenshot failures - suggest ADB fallback
            Log.e(TAG, "Screenshot capture failed", e)
            val errorMessage = if (e.message?.contains("MediaProjection") == true ||
                                  e.message?.contains("consent") == true) {
                "MediaProjection failed: ${e.message}. MCP server should use ADB fallback: adb exec-out screencap -p"
            } else {
                "Screenshot capture failed: ${e.message}"
            }
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.PERMISSION_DENIED,
                errorMessage = errorMessage
            )
        }
    }

    /**
     * Handle tap request
     *
     * Executes tap gesture using either coordinates or selector
     */
    private suspend fun handleTap(
        requestId: String,
        request: Neuralbridge.TapRequest
    ): Neuralbridge.Response {
        // Resolve target to coordinates
        val point = when (request.targetCase) {
            Neuralbridge.TapRequest.TargetCase.COORDINATES -> {
                request.coordinates
            }
            Neuralbridge.TapRequest.TargetCase.SELECTOR -> {
                val resolved = resolveSelector(request.selector)
                if (!resolved.success) {
                    return resolved.error!!
                }
                resolved.centerPoint!!
            }
            else -> {
                return buildErrorResponse(
                    requestId = requestId,
                    errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                    errorMessage = "Tap target not specified"
                )
            }
        }

        Log.d(TAG, "Tapping at (${point.x}, ${point.y})")

        // Execute gesture and wait for completion
        val success = executeGestureAndWait {
            gestureEngine.executeTap(
                x = point.x.toFloat(),
                y = point.y.toFloat(),
                callback = it
            )
        }

        return if (success) {
            Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()
        } else {
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.GESTURE_FAILED,
                errorMessage = "Tap gesture failed or was cancelled"
            )
        }
    }

    /**
     * Handle swipe request
     *
     * Executes swipe gesture from start to end point
     */
    private suspend fun handleSwipe(
        requestId: String,
        request: Neuralbridge.SwipeRequest
    ): Neuralbridge.Response {
        val start = request.start
        val end = request.end
        val durationMs = if (request.durationMs > 0) request.durationMs.toLong() else 300L

        Log.d(TAG, "Swiping from (${start.x}, ${start.y}) to (${end.x}, ${end.y}) in ${durationMs}ms")

        val success = executeGestureAndWait {
            gestureEngine.executeSwipe(
                startX = start.x.toFloat(),
                startY = start.y.toFloat(),
                endX = end.x.toFloat(),
                endY = end.y.toFloat(),
                durationMs = durationMs,
                callback = it
            )
        }

        return if (success) {
            Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()
        } else {
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.GESTURE_FAILED,
                errorMessage = "Swipe gesture failed or was cancelled"
            )
        }
    }

    /**
     * Handle double_tap request
     *
     * Executes double tap gesture using either coordinates or selector
     */
    private suspend fun handleDoubleTap(
        requestId: String,
        request: Neuralbridge.DoubleTapRequest
    ): Neuralbridge.Response {
        // Resolve target to coordinates
        val point = when (request.targetCase) {
            Neuralbridge.DoubleTapRequest.TargetCase.COORDINATES -> {
                request.coordinates
            }
            Neuralbridge.DoubleTapRequest.TargetCase.SELECTOR -> {
                val resolved = resolveSelector(request.selector)
                if (!resolved.success) {
                    return resolved.error!!
                }
                resolved.centerPoint!!
            }
            else -> {
                return buildErrorResponse(
                    requestId = requestId,
                    errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                    errorMessage = "Double tap target not specified"
                )
            }
        }

        Log.d(TAG, "Double tapping at (${point.x}, ${point.y})")

        // Execute gesture and wait for completion
        val success = executeGestureAndWait {
            gestureEngine.executeDoubleTap(
                x = point.x.toFloat(),
                y = point.y.toFloat(),
                callback = it
            )
        }

        return if (success) {
            Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()
        } else {
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.GESTURE_FAILED,
                errorMessage = "Double tap gesture failed or was cancelled"
            )
        }
    }

    /**
     * Handle pinch request
     *
     * Executes pinch zoom gesture (scale >1.0 = zoom in, <1.0 = zoom out)
     */
    private suspend fun handlePinch(
        requestId: String,
        request: Neuralbridge.PinchRequest
    ): Neuralbridge.Response {
        val center = request.center
        val scale = request.scale
        val durationMs = if (request.durationMs > 0) request.durationMs.toLong() else 300L

        Log.d(TAG, "Pinching at (${center.x}, ${center.y}) with scale=$scale, duration=${durationMs}ms")

        val success = executeGestureAndWait {
            gestureEngine.executePinch(
                centerX = center.x.toFloat(),
                centerY = center.y.toFloat(),
                scale = scale,
                durationMs = durationMs,
                callback = it
            )
        }

        return if (success) {
            Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()
        } else {
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.GESTURE_FAILED,
                errorMessage = "Pinch gesture failed or was cancelled"
            )
        }
    }

    /**
     * Handle drag request
     *
     * Executes drag gesture from one point to another
     */
    private suspend fun handleDrag(
        requestId: String,
        request: Neuralbridge.DragRequest
    ): Neuralbridge.Response {
        val from = request.from
        val to = request.to
        val durationMs = if (request.durationMs > 0) request.durationMs.toLong() else 1000L

        Log.d(TAG, "Dragging from (${from.x}, ${from.y}) to (${to.x}, ${to.y}) in ${durationMs}ms")

        val success = executeGestureAndWait {
            gestureEngine.executeDrag(
                fromX = from.x.toFloat(),
                fromY = from.y.toFloat(),
                toX = to.x.toFloat(),
                toY = to.y.toFloat(),
                durationMs = durationMs,
                callback = it
            )
        }

        return if (success) {
            Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()
        } else {
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.GESTURE_FAILED,
                errorMessage = "Drag gesture failed or was cancelled"
            )
        }
    }

    /**
     * Handle fling request
     *
     * Executes fling gesture in specified direction from screen center
     */
    private suspend fun handleFling(
        requestId: String,
        request: Neuralbridge.FlingRequest
    ): Neuralbridge.Response {
        // Map protobuf Direction to GestureEngine FlingDirection
        val direction = when (request.direction) {
            Neuralbridge.Direction.UP -> com.neuralbridge.companion.gesture.FlingDirection.UP
            Neuralbridge.Direction.DOWN -> com.neuralbridge.companion.gesture.FlingDirection.DOWN
            Neuralbridge.Direction.LEFT -> com.neuralbridge.companion.gesture.FlingDirection.LEFT
            Neuralbridge.Direction.RIGHT -> com.neuralbridge.companion.gesture.FlingDirection.RIGHT
            else -> {
                return buildErrorResponse(
                    requestId = requestId,
                    errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                    errorMessage = "Invalid fling direction"
                )
            }
        }

        // Use screen center as start point
        val rootNode = accessibilityService.rootInActiveWindow
        if (rootNode == null) {
            return buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                errorMessage = "No active window available"
            )
        }

        val bounds = android.graphics.Rect()
        rootNode.getBoundsInScreen(bounds)
        val centerX = (bounds.left + bounds.right) / 2f
        val centerY = (bounds.top + bounds.bottom) / 2f

        Log.d(TAG, "Flinging $direction from center ($centerX, $centerY)")

        val success = executeGestureAndWait {
            gestureEngine.executeFling(
                direction = direction,
                startX = centerX,
                startY = centerY,
                distance = 500f,  // Default distance
                callback = it
            )
        }

        return if (success) {
            Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()
        } else {
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.GESTURE_FAILED,
                errorMessage = "Fling gesture failed or was cancelled"
            )
        }
    }

    /**
     * Handle set_clipboard request
     *
     * Sets clipboard content
     */
    private suspend fun handleSetClipboard(
        requestId: String,
        request: Neuralbridge.SetClipboardRequest
    ): Neuralbridge.Response {
        Log.d(TAG, "Setting clipboard: ${request.text.length} chars")

        return try {
            val clipboardManager = accessibilityService.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("NeuralBridge", request.text)
            clipboardManager.setPrimaryClip(clip)

            Log.d(TAG, "Clipboard set successfully")

            Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set clipboard", e)
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                errorMessage = "Failed to set clipboard: ${e.message}"
            )
        }
    }

    /**
     * Handle input_text request
     *
     * Inputs text into element identified by selector, coordinates, or currently focused element
     */
    private suspend fun handleInputText(
        requestId: String,
        request: Neuralbridge.InputTextRequest
    ): Neuralbridge.Response {
        Log.d(TAG, "Inputting text: ${request.text.length} chars, append=${request.append}")

        // Find target element
        val rootNode = accessibilityService.rootInActiveWindow
        if (rootNode == null) {
            return buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                errorMessage = "No active window available"
            )
        }

        // If selector or coordinates specified, tap first to focus the element
        if (request.targetCase == Neuralbridge.InputTextRequest.TargetCase.SELECTOR) {
            val resolved = resolveSelector(request.selector)
            if (!resolved.success) {
                return resolved.error!!
            }
            // Tap to focus
            val tapSuccess = executeGestureAndWait {
                gestureEngine.executeTap(
                    x = resolved.centerPoint!!.x.toFloat(),
                    y = resolved.centerPoint!!.y.toFloat(),
                    callback = it
                )
            }
            if (!tapSuccess) {
                return buildErrorResponse(
                    requestId = requestId,
                    errorCode = Neuralbridge.ErrorCode.GESTURE_FAILED,
                    errorMessage = "Failed to tap element for input"
                )
            }
            // Wait a bit for focus to settle
            kotlinx.coroutines.delay(100)
        } else if (request.targetCase == Neuralbridge.InputTextRequest.TargetCase.COORDINATES) {
            // Tap to focus
            val tapSuccess = executeGestureAndWait {
                gestureEngine.executeTap(
                    x = request.coordinates.x.toFloat(),
                    y = request.coordinates.y.toFloat(),
                    callback = it
                )
            }
            if (!tapSuccess) {
                return buildErrorResponse(
                    requestId = requestId,
                    errorCode = Neuralbridge.ErrorCode.GESTURE_FAILED,
                    errorMessage = "Failed to tap element for input"
                )
            }
            // Wait a bit for focus to settle
            kotlinx.coroutines.delay(100)
        }

        // Now get the focused element
        val targetNode = rootNode.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)

        if (targetNode == null) {
            return buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.ELEMENT_NOT_FOUND,
                errorMessage = "No editable element found. Tap an input field first or use a selector."
            )
        }

        // Input text
        val success = inputEngine.inputText(
            element = targetNode,
            text = request.text,
            append = request.append
        )

        return if (success) {
            Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()
        } else {
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.INPUT_FAILED,
                errorMessage = "Failed to input text into element"
            )
        }
    }

    /**
     * Handle long_press request
     *
     * Executes long press gesture using either coordinates or selector
     */
    private suspend fun handleLongPress(
        requestId: String,
        request: Neuralbridge.LongPressRequest
    ): Neuralbridge.Response {
        // Resolve target to coordinates
        val point = when (request.targetCase) {
            Neuralbridge.LongPressRequest.TargetCase.COORDINATES -> {
                request.coordinates
            }
            Neuralbridge.LongPressRequest.TargetCase.SELECTOR -> {
                val resolved = resolveSelector(request.selector)
                if (!resolved.success) {
                    return resolved.error!!
                }
                resolved.centerPoint!!
            }
            else -> {
                return buildErrorResponse(
                    requestId = requestId,
                    errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                    errorMessage = "Long press target not specified"
                )
            }
        }

        val durationMs = if (request.durationMs > 0) request.durationMs.toLong() else 1000L

        Log.d(TAG, "Long pressing at (${point.x}, ${point.y}) for ${durationMs}ms")

        val success = executeGestureAndWait {
            gestureEngine.executeLongPress(
                x = point.x.toFloat(),
                y = point.y.toFloat(),
                durationMs = durationMs,
                callback = it
            )
        }

        return if (success) {
            Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()
        } else {
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.GESTURE_FAILED,
                errorMessage = "Long press gesture failed or was cancelled"
            )
        }
    }

    /**
     * Handle press_key request
     *
     * Injects key event or performs global action
     */
    private suspend fun handlePressKey(
        requestId: String,
        request: Neuralbridge.PressKeyRequest
    ): Neuralbridge.Response {
        Log.d(TAG, "Pressing key: ${request.keyCode}")

        // Map KeyCode to Android key event or global action
        val success = when (request.keyCode) {
            Neuralbridge.KeyCode.BACK -> {
                accessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }
            Neuralbridge.KeyCode.HOME -> {
                accessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            }
            Neuralbridge.KeyCode.MENU -> {
                // Menu key - not supported via accessibility service
                Log.w(TAG, "MENU key not supported via AccessibilityService")
                false
            }
            else -> {
                // For other keys (ENTER, DELETE, TAB, SPACE, etc.)
                // These are typically handled as actions on focused element
                val rootNode = accessibilityService.rootInActiveWindow
                if (rootNode != null) {
                    val focusedNode = rootNode.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focusedNode != null) {
                        // Handle specific keys - must return boolean
                        when (request.keyCode) {
                            Neuralbridge.KeyCode.ENTER -> {
                                // Enter key - try IME action first (for single-line fields), then newline
                                // Check if field supports IME action (e.g., "Done", "Go", "Send")
                                val actions = focusedNode.actionList
                                val hasImeAction = actions.any {
                                    it.id == android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                                }

                                val result = if (hasImeAction) {
                                    // Single-line field with IME action - perform it
                                    val imeResult = focusedNode.performAction(
                                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                                    )
                                    Log.d(TAG, "ENTER key: performed IME action, result=$imeResult")
                                    imeResult
                                } else {
                                    // Multi-line field or no IME action - add newline
                                    val currentText = focusedNode.text?.toString() ?: ""
                                    val newText = currentText + "\n"
                                    val args = Bundle().apply {
                                        putCharSequence(
                                            android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                            newText
                                        )
                                    }
                                    val setTextResult = focusedNode.performAction(
                                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                                        args
                                    )
                                    Log.d(TAG, "ENTER key: added newline, result=$setTextResult")
                                    setTextResult
                                }
                                result
                            }
                            Neuralbridge.KeyCode.DELETE -> {
                                // Delete key - backspace (ACTION_DELETE or clear selection)
                                val text = focusedNode.text?.toString() ?: ""
                                if (text.isNotEmpty()) {
                                    val newText = text.dropLast(1)
                                    val args = Bundle().apply {
                                        putCharSequence(
                                            android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                            newText
                                        )
                                    }
                                    focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                                } else {
                                    false
                                }
                            }
                            else -> {
                                // For other keys, return false (unsupported)
                                Log.w(TAG, "Key code not yet supported: ${request.keyCode}")
                                false
                            }
                        }
                    } else {
                        Log.w(TAG, "No focused element for key press")
                        false
                    }
                } else {
                    Log.w(TAG, "No active window for key press")
                    false
                }
            }
        }

        return if (success) {
            Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()
        } else {
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.INPUT_FAILED,
                errorMessage = "Key press failed: ${request.keyCode}"
            )
        }
    }

    /**
     * Handle global_action request
     *
     * Performs global accessibility action (back, home, recents, etc.)
     */
    private suspend fun handleGlobalAction(
        requestId: String,
        request: Neuralbridge.GlobalActionRequest
    ): Neuralbridge.Response {
        Log.d(TAG, "Performing global action: ${request.action}")

        // Map protobuf GlobalAction to Android global action constant
        val androidAction = when (request.action) {
            Neuralbridge.GlobalAction.GLOBAL_BACK ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            Neuralbridge.GlobalAction.GLOBAL_HOME ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            Neuralbridge.GlobalAction.GLOBAL_RECENTS ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            Neuralbridge.GlobalAction.GLOBAL_NOTIFICATIONS ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            Neuralbridge.GlobalAction.GLOBAL_QUICK_SETTINGS ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            Neuralbridge.GlobalAction.GLOBAL_LOCK_SCREEN ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            Neuralbridge.GlobalAction.GLOBAL_TAKE_SCREENSHOT ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> {
                return buildErrorResponse(
                    requestId = requestId,
                    errorCode = Neuralbridge.ErrorCode.UNSUPPORTED_OPERATION,
                    errorMessage = "Unsupported global action: ${request.action}"
                )
            }
        }

        val success = accessibilityService.performGlobalAction(androidAction)

        return if (success) {
            Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()
        } else {
            buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.INPUT_FAILED,
                errorMessage = "Global action failed: ${request.action}"
            )
        }
    }

    /**
     * Handle wait_for_element request
     *
     * Waits for element matching selector to appear, using event-driven detection
     */
    private suspend fun handleWaitForElement(
        requestId: String,
        request: Neuralbridge.WaitForElementRequest
    ): Neuralbridge.Response {
        val timeoutMs = if (request.timeoutMs > 0) request.timeoutMs.toLong() else 5000L
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "Waiting for element: ${selectorToString(request.selector)}, timeout=${timeoutMs}ms")

        // Try immediate resolution first
        val initialResolution = resolveSelector(request.selector)
        if (initialResolution.success) {
            Log.d(TAG, "Element found immediately")
            return Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()
        }

        // Event-driven wait: Check after each UI change
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Wait for next UI change (or 300ms timeout)
            kotlinx.coroutines.delay(300)

            // Try resolution again
            val resolution = resolveSelector(request.selector)
            if (resolution.success) {
                val elapsedMs = System.currentTimeMillis() - startTime
                Log.d(TAG, "Element appeared after ${elapsedMs}ms")
                return Neuralbridge.Response.newBuilder()
                    .setRequestId(requestId)
                    .setSuccess(true)
                    .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                    .build()
            }
        }

        // Timeout
        return buildErrorResponse(
            requestId = requestId,
            errorCode = Neuralbridge.ErrorCode.TIMEOUT,
            errorMessage = "Element did not appear within ${timeoutMs}ms: ${selectorToString(request.selector)}\n" +
                    "Suggestions:\n" +
                    "- Increase timeout value\n" +
                    "- Verify element selector is correct\n" +
                    "- Check if app is loading or showing progress indicator"
        )
    }

    /**
     * Handle wait_for_idle request
     *
     * Waits for UI to become stable (no changes for 300ms)
     */
    private suspend fun handleWaitForIdle(
        requestId: String,
        request: Neuralbridge.WaitForIdleRequest
    ): Neuralbridge.Response {
        val timeoutMs = if (request.timeoutMs > 0) request.timeoutMs.toLong() else 5000L
        val idleThresholdMs = 200L  // UI considered idle after 200ms of no changes
        val pollIntervalMs = 50L     // Check every 50ms

        Log.d(TAG, "Waiting for UI idle (timeout=${timeoutMs}ms, threshold=${idleThresholdMs}ms)")

        val startTime = System.currentTimeMillis()

        // Track UI tree stability by computing simple hash
        var previousTreeHash: Int? = null
        var stableStartTime: Long? = null

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Get current UI tree hash
            val rootNode = accessibilityService.rootInActiveWindow
            val currentHash = if (rootNode != null) computeTreeHash(rootNode) else 0

            if (currentHash == previousTreeHash) {
                // Tree hasn't changed
                if (stableStartTime == null) {
                    stableStartTime = System.currentTimeMillis()
                } else {
                    val stableDuration = System.currentTimeMillis() - stableStartTime
                    if (stableDuration >= idleThresholdMs) {
                        // UI has been stable for threshold duration
                        Log.d(TAG, "UI idle detected after ${System.currentTimeMillis() - startTime}ms")
                        return Neuralbridge.Response.newBuilder()
                            .setRequestId(requestId)
                            .setSuccess(true)
                            .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                            .build()
                    }
                }
            } else {
                // Tree changed, reset stability timer
                previousTreeHash = currentHash
                stableStartTime = null
            }

            kotlinx.coroutines.delay(pollIntervalMs)
        }

        // Timeout
        Log.d(TAG, "Wait for idle timed out after ${timeoutMs}ms")
        return buildErrorResponse(
            requestId = requestId,
            errorCode = Neuralbridge.ErrorCode.TIMEOUT,
            errorMessage = "UI did not become idle within ${timeoutMs}ms"
        )
    }

    /**
     * Compute simple hash of UI tree for change detection
     */
    private fun computeTreeHash(node: android.view.accessibility.AccessibilityNodeInfo): Int {
        var hash = 17
        hash = 31 * hash + (node.className?.hashCode() ?: 0)
        hash = 31 * hash + (node.text?.hashCode() ?: 0)
        hash = 31 * hash + node.childCount
        // Don't traverse children (too expensive), just use root-level signature
        return hash
    }

    /**
     * Handle wait_for_gone request
     *
     * Waits for element matching selector to disappear
     */
    private suspend fun handleWaitForGone(
        requestId: String,
        request: Neuralbridge.WaitForGoneRequest
    ): Neuralbridge.Response {
        val timeoutMs = if (request.timeoutMs > 0) request.timeoutMs.toLong() else 5000L
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "Waiting for element to disappear: ${selectorToString(request.selector)}, timeout=${timeoutMs}ms")

        // Event-driven wait: Check after each UI change
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Try resolution
            val resolution = resolveSelector(request.selector)
            if (!resolution.success) {
                // Element not found (i.e., it's gone)
                val elapsedMs = System.currentTimeMillis() - startTime
                Log.d(TAG, "Element disappeared after ${elapsedMs}ms")
                return Neuralbridge.Response.newBuilder()
                    .setRequestId(requestId)
                    .setSuccess(true)
                    .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                    .build()
            }

            // Wait for next UI change (or 300ms timeout)
            kotlinx.coroutines.delay(300)
        }

        // Timeout
        return buildErrorResponse(
            requestId = requestId,
            errorCode = Neuralbridge.ErrorCode.TIMEOUT,
            errorMessage = "Element still present after ${timeoutMs}ms: ${selectorToString(request.selector)}"
        )
    }

    /**
     * Handle find_elements request
     *
     * Finds all elements matching selector
     */
    private suspend fun handleFindElements(
        requestId: String,
        request: Neuralbridge.FindElementsRequest
    ): Neuralbridge.Response {
        Log.d(TAG, "Finding elements: ${selectorToString(request.selector)}, findAll=${request.findAll}")

        // Validate selector is not empty
        val selector = request.selector
        if (selector.text.isEmpty() &&
            selector.resourceId.isEmpty() &&
            selector.contentDesc.isEmpty() &&
            selector.className.isEmpty() &&
            selector.elementId.isEmpty() &&
            !selector.hasClickable() &&
            !selector.hasScrollable() &&
            !selector.hasFocusable() &&
            !selector.hasLongClickable() &&
            !selector.hasCheckable() &&
            !selector.hasChecked()) {
            return buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                errorMessage = "Selector is empty - must specify at least one criterion (text, resource_id, content_desc, class_name, element_id, or boolean filters)"
            )
        }

        val rootNode = accessibilityService.rootInActiveWindow
        if (rootNode == null) {
            return buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                errorMessage = "No active window available"
            )
        }

        // Walk the tree
        val uiTree = uiTreeWalker.walkTree(
            rootNode = rootNode,
            includeInvisible = !request.visibleOnly,
            maxDepth = -1
        )

        // Filter elements by selector
        val matches = filterElementsBySelector(uiTree.elements, request.selector, request.visibleOnly)

        // Build result
        val elements = if (request.findAll) {
            matches
        } else {
            if (matches.isNotEmpty()) listOf(matches[0]) else emptyList()
        }

        val elementList = Neuralbridge.ElementList.newBuilder()
            .setTotalMatches(matches.size)
            .addAllElements(elements.map { convertToProtoElement(it) })
            .build()

        return Neuralbridge.Response.newBuilder()
            .setRequestId(requestId)
            .setSuccess(true)
            .setElementList(elementList)
            .build()
    }

    /**
     * Handle get_foreground_app request
     *
     * Returns package name and activity of foreground app
     */
    private suspend fun handleGetForegroundApp(
        requestId: String,
        request: Neuralbridge.GetForegroundAppRequest
    ): Neuralbridge.Response {
        Log.d(TAG, "Getting foreground app")

        val rootNode = accessibilityService.rootInActiveWindow
        if (rootNode == null) {
            return buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                errorMessage = "No active window available"
            )
        }

        val packageName = rootNode.packageName?.toString() ?: ""

        // Try to extract activity name from window title or other info
        // Note: AccessibilityService doesn't directly provide activity name
        // We can only get package name reliably
        val activityName = "" // Cannot determine via AccessibilityService alone

        val appInfo = Neuralbridge.AppInfo.newBuilder()
            .setPackageName(packageName)
            .setActivityName(activityName)
            .setIsLauncher(false)
            .build()

        return Neuralbridge.Response.newBuilder()
            .setRequestId(requestId)
            .setSuccess(true)
            .setAppInfo(appInfo)
            .build()
    }

    /**
     * Handle launch_app request
     *
     * Launches app by package name or activity
     */
    private suspend fun handleLaunchApp(
        requestId: String,
        request: Neuralbridge.LaunchAppRequest
    ): Neuralbridge.Response {
        val packageName = when (request.targetCase) {
            Neuralbridge.LaunchAppRequest.TargetCase.PACKAGE_NAME -> request.packageName
            Neuralbridge.LaunchAppRequest.TargetCase.ACTIVITY -> {
                // Extract package from activity (format: com.example.app/.MainActivity)
                val activity = request.activity
                if (activity.contains("/")) {
                    activity.substringBefore("/")
                } else {
                    activity
                }
            }
            else -> {
                return buildErrorResponse(
                    requestId = requestId,
                    errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                    errorMessage = "Launch target not specified"
                )
            }
        }

        Log.d(TAG, "Launching app: $packageName, clearTask=${request.clearTask}")

        try {
            val packageManager = accessibilityService.packageManager
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

            if (launchIntent == null) {
                return buildErrorResponse(
                    requestId = requestId,
                    errorCode = Neuralbridge.ErrorCode.APP_NOT_INSTALLED,
                    errorMessage = "App not installed: $packageName"
                )
            }

            if (request.clearTask) {
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            } else {
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            accessibilityService.startActivity(launchIntent)

            Log.d(TAG, "App launched successfully: $packageName")

            return Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()

        } catch (e: Exception) {
            return buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                errorMessage = "Failed to launch app: ${e.message}"
            )
        }
    }

    /**
     * Handle close_app request
     *
     * Closes app gracefully via killBackgroundProcesses
     */
    private suspend fun handleCloseApp(
        requestId: String,
        request: Neuralbridge.CloseAppRequest
    ): Neuralbridge.Response {
        val packageName = request.packageName

        Log.d(TAG, "Closing app: $packageName, force=${request.force}")

        // If force=true, it should be handled by MCP server via ADB
        // This handler only does graceful close
        if (request.force) {
            return buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.INTERNAL_ERROR,
                errorMessage = "Force close should be handled by MCP server via ADB"
            )
        }

        try {
            // Get ActivityManager
            val activityManager = accessibilityService.getSystemService(
                android.content.Context.ACTIVITY_SERVICE
            ) as android.app.ActivityManager

            // Kill background processes (graceful close)
            activityManager.killBackgroundProcesses(packageName)

            Log.d(TAG, "App closed successfully: $packageName")

            return Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()

        } catch (e: Exception) {
            return buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.INTERNAL_ERROR,
                errorMessage = "Failed to close app: ${e.message}"
            )
        }
    }

    /**
     * Handle open_url request
     *
     * Opens URL in browser
     */
    private suspend fun handleOpenUrl(
        requestId: String,
        request: Neuralbridge.OpenUrlRequest
    ): Neuralbridge.Response {
        Log.d(TAG, "Opening URL: ${request.url}")

        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse(request.url)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK

                // Use specific browser if specified
                if (request.browserPackage.isNotEmpty()) {
                    setPackage(request.browserPackage)
                }
            }

            accessibilityService.startActivity(intent)

            Log.d(TAG, "URL opened successfully")

            return Neuralbridge.Response.newBuilder()
                .setRequestId(requestId)
                .setSuccess(true)
                .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
                .build()

        } catch (e: Exception) {
            return buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                errorMessage = "Failed to open URL: ${e.message}"
            )
        }
    }

    /**
     * Handle enable_events request
     *
     * Enable or disable event streaming to connected clients
     */
    private suspend fun handleEnableEvents(
        requestId: String,
        request: Neuralbridge.EnableEventsRequest
    ): Neuralbridge.Response {
        Log.d(TAG, "Setting event streaming: enable=${request.enable}")

        accessibilityService.setEventsEnabled(request.enable)

        return Neuralbridge.Response.newBuilder()
            .setRequestId(requestId)
            .setSuccess(true)
            .setEmpty(Neuralbridge.EmptyResult.getDefaultInstance())
            .build()
    }

    /**
     * Handle get_notifications request
     *
     * Returns list of active notifications from NotificationListenerService
     */
    private suspend fun handleGetNotifications(
        requestId: String,
        request: Neuralbridge.GetNotificationsRequest
    ): Neuralbridge.Response {
        Log.d(TAG, "Getting notifications (activeOnly=${request.activeOnly})")

        // Get NotificationListener instance
        val notificationListener = com.neuralbridge.companion.notification.NotificationListener.instance
        if (notificationListener == null) {
            return buildErrorResponse(
                requestId = requestId,
                errorCode = Neuralbridge.ErrorCode.PERMISSION_DENIED,
                errorMessage = "NotificationListenerService not bound. " +
                        "This typically happens after APK deployment when services need re-enablement.\n\n" +
                        "Quick Fix:\n" +
                        "  adb shell cmd notification allow_listener com.neuralbridge.companion/.notification.NotificationListener\n\n" +
                        "Or run the post-deployment script:\n" +
                        "  mcp-server/tests/scripts/enable_services.sh\n\n" +
                        "Manual Fix:\n" +
                        "  Settings → Notifications → Notification access → Toggle NeuralBridge off/on"
            )
        }

        // Get active notifications
        val notifications = notificationListener.getActiveNotificationsList()

        Log.d(TAG, "Found ${notifications.size} active notifications")

        // Convert to protobuf
        val notificationList = Neuralbridge.NotificationList.newBuilder()
            .addAllNotifications(notifications.map { convertToProtoNotification(it) })
            .build()

        return Neuralbridge.Response.newBuilder()
            .setRequestId(requestId)
            .setSuccess(true)
            .setNotificationList(notificationList)
            .build()
    }

    /**
     * Convert NotificationInfo to protobuf NotificationInfo
     */
    private fun convertToProtoNotification(
        notification: com.neuralbridge.companion.notification.NotificationInfo
    ): Neuralbridge.NotificationInfo {
        return Neuralbridge.NotificationInfo.newBuilder()
            .setPackageName(notification.packageName)
            .setTitle(notification.title)
            .setText(notification.text)
            .setPostTime(notification.postTime)
            .setOngoing(notification.ongoing)
            .setClearable(notification.clearable)
            .build()
    }

    /**
     * Filter elements by selector criteria
     */
    private fun filterElementsBySelector(
        elements: List<UiElement>,
        selector: Neuralbridge.Selector,
        visibleOnly: Boolean
    ): List<UiElement> {
        var filtered = elements.toList()

        // Apply visibility filter
        if (visibleOnly || selector.visibleOnly) {
            filtered = filtered.filter { it.visible }
        }

        // Apply enabled filter
        if (selector.enabledOnly) {
            filtered = filtered.filter { it.enabled }
        }

        // Filter by boolean properties
        if (selector.hasClickable()) {
            filtered = filtered.filter { it.clickable == selector.clickable }
        }

        if (selector.hasScrollable()) {
            filtered = filtered.filter { it.scrollable == selector.scrollable }
        }

        if (selector.hasFocusable()) {
            filtered = filtered.filter { it.focusable == selector.focusable }
        }

        if (selector.hasLongClickable()) {
            filtered = filtered.filter { it.longClickable == selector.longClickable }
        }

        if (selector.hasCheckable()) {
            filtered = filtered.filter { it.checkable == selector.checkable }
        }

        if (selector.hasChecked()) {
            filtered = filtered.filter { it.checked == selector.checked }
        }

        // Filter by element_id
        if (selector.elementId.isNotEmpty()) {
            filtered = filtered.filter { it.elementId == selector.elementId }
        }

        // Filter by resource_id
        if (selector.resourceId.isNotEmpty()) {
            filtered = filtered.filter { it.resourceId?.endsWith(selector.resourceId) == true }
        }

        // Filter by text
        if (selector.text.isNotEmpty()) {
            filtered = if (selector.exactMatch) {
                filtered.filter { it.text == selector.text }
            } else {
                filtered.filter { it.text?.contains(selector.text, ignoreCase = true) == true }
            }
        }

        // Filter by content_desc
        if (selector.contentDesc.isNotEmpty()) {
            filtered = filtered.filter {
                it.contentDescription?.contains(selector.contentDesc, ignoreCase = true) == true
            }
        }

        // Filter by class_name
        if (selector.className.isNotEmpty()) {
            filtered = filtered.filter { it.className?.endsWith(selector.className) == true }
        }

        return filtered
    }

    /**
     * Execute gesture and wait for completion
     *
     * @param executor Lambda that executes gesture with callback
     * @return true if gesture completed successfully, false otherwise
     */
    private suspend fun executeGestureAndWait(
        executor: (GestureResultCallback) -> Unit
    ): Boolean = suspendCoroutine { continuation ->
        val callback = object : GestureResultCallback {
            override fun onCompleted(gesture: android.accessibilityservice.GestureDescription) {
                Log.d(TAG, "Gesture completed")
                continuation.resume(true)
            }

            override fun onCancelled(gesture: android.accessibilityservice.GestureDescription) {
                Log.w(TAG, "Gesture cancelled")
                continuation.resume(false)
            }
        }

        // Execute gesture with callback
        executor(callback)
    }

    /**
     * Resolve selector to element and coordinates
     *
     * Resolution priority:
     * 1. Direct element_id lookup
     * 2. Exact resource_id match
     * 3. Exact text match
     * 4. Partial text match
     * 5. Content description match
     * 6. Class name match
     * 7. Fuzzy text match (Levenshtein distance < 3)
     *
     * @return SelectorResolution with success flag, center point, element ID, or error response
     */
    private suspend fun resolveSelector(selector: Neuralbridge.Selector): SelectorResolution {
        // Validate selector is not empty
        if (selector.text.isEmpty() &&
            selector.resourceId.isEmpty() &&
            selector.contentDesc.isEmpty() &&
            selector.className.isEmpty() &&
            selector.elementId.isEmpty() &&
            !selector.hasClickable() &&
            !selector.hasScrollable() &&
            !selector.hasFocusable() &&
            !selector.hasLongClickable() &&
            !selector.hasCheckable() &&
            !selector.hasChecked()) {
            return SelectorResolution(
                success = false,
                error = buildErrorResponse(
                    requestId = "",
                    errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                    errorMessage = "Selector is empty - must specify at least one criterion (text, resource_id, content_desc, class_name, element_id, or boolean filters)"
                )
            )
        }

        val rootNode = accessibilityService.rootInActiveWindow
        if (rootNode == null) {
            return SelectorResolution(
                success = false,
                error = buildErrorResponse(
                    requestId = "",
                    errorCode = Neuralbridge.ErrorCode.ERROR_UNSPECIFIED,
                    errorMessage = "No active window available"
                )
            )
        }

        // Walk the tree
        val uiTree = uiTreeWalker.walkTree(
            rootNode = rootNode,
            includeInvisible = !selector.visibleOnly,
            maxDepth = -1
        )

        // Filter candidates based on selector criteria
        var candidates = uiTree.elements.toList()

        // Apply visibility filter
        if (selector.visibleOnly) {
            candidates = candidates.filter { it.visible }
        }

        // Apply enabled filter
        if (selector.enabledOnly) {
            candidates = candidates.filter { it.enabled }
        }

        // Priority 1: Direct element_id lookup
        if (selector.elementId.isNotEmpty()) {
            val match = candidates.find { it.elementId == selector.elementId }
            if (match != null) {
                return createSuccessResolution(match)
            }
        }

        // Priority 2: Exact resource_id match (suffix)
        if (selector.resourceId.isNotEmpty()) {
            val matches = candidates.filter { element ->
                element.resourceId?.endsWith(selector.resourceId) == true
            }
            if (matches.size == 1) {
                return createSuccessResolution(matches[0])
            } else if (matches.size > 1) {
                return createAmbiguousResolution(matches, "resource_id: ${selector.resourceId}")
            }
        }

        // Priority 3: Exact text match
        if (selector.text.isNotEmpty() && selector.exactMatch) {
            val matches = candidates.filter { it.text == selector.text }
            if (matches.size == 1) {
                return createSuccessResolution(matches[0])
            } else if (matches.size > 1) {
                return createAmbiguousResolution(matches, "exact text: ${selector.text}")
            }
        }

        // Priority 4: Partial text match (also try contentDescription as fallback)
        if (selector.text.isNotEmpty() && !selector.exactMatch) {
            val matches = candidates.filter { element ->
                element.text?.contains(selector.text, ignoreCase = true) == true ||
                element.contentDescription?.contains(selector.text, ignoreCase = true) == true
            }
            if (matches.size == 1) {
                return createSuccessResolution(matches[0])
            } else if (matches.size > 1) {
                return createAmbiguousResolution(matches, "partial text: ${selector.text}")
            }
        }

        // Priority 5: Content description match
        if (selector.contentDesc.isNotEmpty()) {
            val matches = candidates.filter { element ->
                element.contentDescription?.contains(selector.contentDesc, ignoreCase = true) == true
            }
            if (matches.size == 1) {
                return createSuccessResolution(matches[0])
            } else if (matches.size > 1) {
                return createAmbiguousResolution(matches, "content_desc: ${selector.contentDesc}")
            }
        }

        // Priority 6: Class name match
        if (selector.className.isNotEmpty()) {
            val matches = candidates.filter { element ->
                element.className?.endsWith(selector.className) == true
            }
            if (matches.size == 1) {
                return createSuccessResolution(matches[0])
            } else if (matches.size > 1) {
                return createAmbiguousResolution(matches, "class_name: ${selector.className}")
            }
        }

        // Priority 7: Fuzzy text match (Levenshtein distance < 3)
        if (selector.text.isNotEmpty()) {
            val matches = candidates.filter { element ->
                element.text?.let { text ->
                    levenshteinDistance(text.lowercase(), selector.text.lowercase()) < 3
                } ?: false
            }
            if (matches.size == 1) {
                return createSuccessResolution(matches[0])
            } else if (matches.size > 1) {
                return createAmbiguousResolution(matches, "fuzzy text: ${selector.text}")
            }
        }

        // No matches found - provide debugging information
        val totalElements = candidates.size
        val visibleElements = candidates.count { it.visible }
        val sampleElements = candidates.take(5).mapNotNull { element ->
            val text = element.text ?: element.contentDescription ?: element.resourceId?.substringAfterLast('/') ?: element.className?.substringAfterLast('.')
            if (text != null) "\"$text\"" else null
        }.joinToString(", ")

        return SelectorResolution(
            success = false,
            error = buildErrorResponse(
                requestId = "",
                errorCode = Neuralbridge.ErrorCode.ELEMENT_NOT_FOUND,
                errorMessage = "Element not found for selector: ${selectorToString(selector)}\n" +
                        "UI Tree Summary: $totalElements total elements, $visibleElements visible\n" +
                        "Sample elements: $sampleElements\n" +
                        "Suggestions:\n" +
                        "- Capture screenshot to verify element is visible\n" +
                        "- Use get_ui_tree to see all available elements\n" +
                        "- Try partial text match or resource_id instead\n" +
                        "- Check if element uses contentDescription instead of text"
            )
        )
    }

    /**
     * Create success resolution from matched element
     */
    private fun createSuccessResolution(element: UiElement): SelectorResolution {
        val bounds = element.bounds
        if (bounds == null) {
            return SelectorResolution(
                success = false,
                error = buildErrorResponse(
                    requestId = "",
                    errorCode = Neuralbridge.ErrorCode.ELEMENT_NOT_VISIBLE,
                    errorMessage = "Element found but has no bounds (not visible)"
                )
            )
        }

        val centerX = (bounds.left + bounds.right) / 2
        val centerY = (bounds.top + bounds.bottom) / 2

        return SelectorResolution(
            success = true,
            centerPoint = Neuralbridge.Point.newBuilder()
                .setX(centerX)
                .setY(centerY)
                .build(),
            elementId = element.elementId
        )
    }

    /**
     * Create ambiguous resolution (multiple matches)
     */
    private fun createAmbiguousResolution(matches: List<UiElement>, criteria: String): SelectorResolution {
        val matchDescriptions = matches.take(5).joinToString("\n") { element ->
            "- ${element.text ?: element.contentDescription ?: element.className} " +
                    "(id=${element.elementId}, bounds=${element.bounds})"
        }

        return SelectorResolution(
            success = false,
            error = buildErrorResponse(
                requestId = "",
                errorCode = Neuralbridge.ErrorCode.ELEMENT_AMBIGUOUS,
                errorMessage = "Multiple elements found for $criteria (${matches.size} matches):\n" +
                        matchDescriptions +
                        (if (matches.size > 5) "\n... and ${matches.size - 5} more" else "") +
                        "\n\nSuggestions:\n" +
                        "- Use more specific selector (e.g., resource_id or element_id)\n" +
                        "- Use get_ui_tree to inspect elements and choose unique identifier"
            )
        )
    }


    /**
     * Calculate Levenshtein distance between two strings
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[len1][len2]
    }

    /**
     * Convert selector to human-readable string
     */
    private fun selectorToString(selector: Neuralbridge.Selector): String {
        val parts = mutableListOf<String>()
        if (selector.text.isNotEmpty()) parts.add("text='${selector.text}'")
        if (selector.resourceId.isNotEmpty()) parts.add("resource_id='${selector.resourceId}'")
        if (selector.contentDesc.isNotEmpty()) parts.add("content_desc='${selector.contentDesc}'")
        if (selector.className.isNotEmpty()) parts.add("class_name='${selector.className}'")
        if (selector.elementId.isNotEmpty()) parts.add("element_id='${selector.elementId}'")
        return parts.joinToString(", ")
    }

    /**
     * Convert internal UiTree to protobuf UITree
     */
    private fun convertToProtoTree(tree: UiTree): Neuralbridge.UITree {
        val builder = Neuralbridge.UITree.newBuilder()
            .setForegroundApp(tree.foregroundApp)
            .setTotalNodes(tree.totalNodes)
            .setCaptureTimestamp(tree.captureTimestamp)

        // Convert elements
        tree.elements.forEach { element ->
            builder.addElements(convertToProtoElement(element))
        }

        return builder.build()
    }

    /**
     * Convert internal UiElement to protobuf UIElement
     */
    private fun convertToProtoElement(element: UiElement): Neuralbridge.UIElement {
        val builder = Neuralbridge.UIElement.newBuilder()
            .setElementId(element.elementId)
            .setVisible(element.visible)
            .setEnabled(element.enabled)
            .setClickable(element.clickable)
            .setScrollable(element.scrollable)
            .setFocusable(element.focusable)
            .setLongClickable(element.longClickable)
            .setCheckable(element.checkable)
            .setChecked(element.checked)
            .setSemanticType(element.semanticType)
            .setAiDescription(element.aiDescription)

        // Optional fields
        element.resourceId?.let { builder.setResourceId(it) }
        element.className?.let { builder.setClassName(it) }
        element.text?.let { builder.setText(it) }
        element.contentDescription?.let { builder.setContentDescription(it) }
        element.bounds?.let { bounds ->
            builder.setBounds(
                Neuralbridge.Bounds.newBuilder()
                    .setLeft(bounds.left)
                    .setTop(bounds.top)
                    .setRight(bounds.right)
                    .setBottom(bounds.bottom)
                    .build()
            )
        }

        return builder.build()
    }

    /**
     * Build error response
     */
    private fun buildErrorResponse(
        requestId: String,
        errorCode: Neuralbridge.ErrorCode,
        errorMessage: String,
        latencyMs: Long = 0
    ): Neuralbridge.Response {
        return Neuralbridge.Response.newBuilder()
            .setRequestId(requestId)
            .setSuccess(false)
            .setErrorCode(errorCode)
            .setErrorMessage(errorMessage)
            .setLatencyMs(latencyMs)
            .build()
    }
}

/**
 * Selector resolution result
 *
 * Contains either a successful resolution (center point + element ID)
 * or an error response to return to the caller.
 */
private data class SelectorResolution(
    val success: Boolean,
    val centerPoint: Neuralbridge.Point? = null,
    val elementId: String? = null,
    val error: Neuralbridge.Response? = null
)
