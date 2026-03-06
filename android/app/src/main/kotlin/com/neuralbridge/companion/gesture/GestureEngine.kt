package com.neuralbridge.companion.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.neuralbridge.companion.service.GestureResultCallback

/**
 * Gesture Engine
 *
 * Executes all gesture types using AccessibilityService.dispatchGesture().
 * Provides high-level gesture primitives with <50ms execution time.
 *
 * Supported gestures:
 * - Tap (50ms single stroke)
 * - Long press (1000ms+ single stroke)
 * - Double tap (two 50ms strokes with 100ms gap)
 * - Swipe (linear path with duration)
 * - Fling (fast swipe <200ms)
 * - Pinch (two simultaneous strokes)
 * - Drag (long press then move)
 */
class GestureEngine(
    private val accessibilityService: AccessibilityService
) {
    companion object {
        private const val TAG = "GestureEngine"

        // Gesture timing constants
        private const val TAP_DURATION_MS = 50L
        private const val LONG_PRESS_DURATION_MS = 1000L
        private const val DOUBLE_TAP_GAP_MS = 100L
        private const val SWIPE_DURATION_MS = 300L
        private const val FLING_DURATION_MS = 150L

        // Maximum simultaneous strokes (Android limit: 10)
        private const val MAX_STROKES = 10
    }

    /**
     * Execute a tap gesture
     *
     * @param x X coordinate in pixels
     * @param y Y coordinate in pixels
     * @param callback Optional callback for gesture result
     */
    fun executeTap(
        x: Float,
        y: Float,
        callback: GestureResultCallback? = null
    ) {
        Log.d(TAG, "Executing tap at ($x, $y)")

        val path = Path().apply {
            moveTo(x, y)
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            TAP_DURATION_MS
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        executeGesture(gesture, callback)
    }

    /**
     * Execute a long press gesture
     *
     * @param x X coordinate in pixels
     * @param y Y coordinate in pixels
     * @param durationMs Press duration (default: 1000ms)
     * @param callback Optional callback for gesture result
     */
    fun executeLongPress(
        x: Float,
        y: Float,
        durationMs: Long = LONG_PRESS_DURATION_MS,
        callback: GestureResultCallback? = null
    ) {
        Log.d(TAG, "Executing long press at ($x, $y) for ${durationMs}ms")

        val path = Path().apply {
            moveTo(x, y)
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            durationMs
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        executeGesture(gesture, callback)
    }

    /**
     * Execute a double tap gesture
     *
     * @param x X coordinate in pixels
     * @param y Y coordinate in pixels
     * @param callback Optional callback for gesture result
     */
    fun executeDoubleTap(
        x: Float,
        y: Float,
        callback: GestureResultCallback? = null
    ) {
        Log.d(TAG, "Executing double tap at ($x, $y)")

        // First tap
        val firstTap = createTapPath(x, y)
        val firstStroke = GestureDescription.StrokeDescription(
            firstTap,
            0,
            TAP_DURATION_MS
        )

        // Second tap (after gap)
        val secondTap = createTapPath(x, y)
        val secondStroke = GestureDescription.StrokeDescription(
            secondTap,
            TAP_DURATION_MS + DOUBLE_TAP_GAP_MS,
            TAP_DURATION_MS
        )

        val gesture = GestureDescription.Builder()
            .addStroke(firstStroke)
            .addStroke(secondStroke)
            .build()

        executeGesture(gesture, callback)
    }

    /**
     * Execute a swipe gesture
     *
     * @param startX Start X coordinate
     * @param startY Start Y coordinate
     * @param endX End X coordinate
     * @param endY End Y coordinate
     * @param durationMs Swipe duration (default: 300ms, <200ms = fling)
     * @param callback Optional callback for gesture result
     */
    fun executeSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = SWIPE_DURATION_MS,
        callback: GestureResultCallback? = null
    ) {
        Log.d(TAG, "Executing swipe from ($startX, $startY) to ($endX, $endY) in ${durationMs}ms")

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            durationMs
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        executeGesture(gesture, callback)
    }

    /**
     * Execute a fling gesture (fast swipe)
     *
     * @param direction Fling direction
     * @param startX Start X coordinate
     * @param startY Start Y coordinate
     * @param distance Fling distance in pixels
     * @param callback Optional callback for gesture result
     */
    fun executeFling(
        direction: FlingDirection,
        startX: Float,
        startY: Float,
        distance: Float = 500f,
        callback: GestureResultCallback? = null
    ) {
        Log.d(TAG, "Executing fling: direction=$direction, distance=$distance")

        val (endX, endY) = when (direction) {
            FlingDirection.UP -> startX to (startY - distance)
            FlingDirection.DOWN -> startX to (startY + distance)
            FlingDirection.LEFT -> (startX - distance) to startY
            FlingDirection.RIGHT -> (startX + distance) to startY
        }

        executeSwipe(startX, startY, endX, endY, FLING_DURATION_MS, callback)
    }

    /**
     * Execute a pinch gesture (zoom in/out)
     *
     * @param centerX Center X coordinate
     * @param centerY Center Y coordinate
     * @param scale Scale factor (>1.0 = zoom in, <1.0 = zoom out)
     * @param durationMs Pinch duration (default: 300ms)
     * @param callback Optional callback for gesture result
     */
    fun executePinch(
        centerX: Float,
        centerY: Float,
        scale: Float,
        durationMs: Long = SWIPE_DURATION_MS,
        callback: GestureResultCallback? = null
    ) {
        Log.d(TAG, "Executing pinch at ($centerX, $centerY) with scale=$scale")

        // Calculate start and end positions for two fingers
        val startDistance = 100f // Starting distance between fingers
        val endDistance = startDistance * scale

        // Finger 1: top-left to center or vice versa
        val finger1StartX = centerX - (if (scale > 1.0f) startDistance else endDistance) / 2
        val finger1StartY = centerY - (if (scale > 1.0f) startDistance else endDistance) / 2
        val finger1EndX = centerX - (if (scale > 1.0f) endDistance else startDistance) / 2
        val finger1EndY = centerY - (if (scale > 1.0f) endDistance else startDistance) / 2

        // Finger 2: bottom-right to center or vice versa
        val finger2StartX = centerX + (if (scale > 1.0f) startDistance else endDistance) / 2
        val finger2StartY = centerY + (if (scale > 1.0f) startDistance else endDistance) / 2
        val finger2EndX = centerX + (if (scale > 1.0f) endDistance else startDistance) / 2
        val finger2EndY = centerY + (if (scale > 1.0f) endDistance else startDistance) / 2

        // Create paths for both fingers
        val path1 = Path().apply {
            moveTo(finger1StartX, finger1StartY)
            lineTo(finger1EndX, finger1EndY)
        }

        val path2 = Path().apply {
            moveTo(finger2StartX, finger2StartY)
            lineTo(finger2EndX, finger2EndY)
        }

        val stroke1 = GestureDescription.StrokeDescription(path1, 0, durationMs)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, durationMs)

        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()

        executeGesture(gesture, callback)
    }

    /**
     * Execute a drag gesture (long press + move)
     *
     * @param fromX Start X coordinate
     * @param fromY Start Y coordinate
     * @param toX End X coordinate
     * @param toY End Y coordinate
     * @param durationMs Total duration including initial press (default: 1000ms)
     * @param callback Optional callback for gesture result
     */
    fun executeDrag(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 1000L,
        callback: GestureResultCallback? = null
    ) {
        Log.d(TAG, "Executing drag from ($fromX, $fromY) to ($toX, $toY)")

        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }

        // Drag is a continuous stroke from start to end with longer duration
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            durationMs
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        executeGesture(gesture, callback)
    }

    /**
     * Execute a custom multi-touch gesture
     *
     * @param strokes List of stroke descriptions (max 10)
     * @param callback Optional callback for gesture result
     */
    fun executeMultiTouch(
        strokes: List<GestureDescription.StrokeDescription>,
        callback: GestureResultCallback? = null
    ) {
        if (strokes.isEmpty()) {
            Log.w(TAG, "No strokes provided for multi-touch gesture")
            return
        }

        if (strokes.size > MAX_STROKES) {
            Log.w(TAG, "Too many strokes: ${strokes.size} (max: $MAX_STROKES)")
            return
        }

        Log.d(TAG, "Executing multi-touch gesture with ${strokes.size} strokes")

        val builder = GestureDescription.Builder()
        strokes.forEach { stroke -> builder.addStroke(stroke) }

        val gesture = builder.build()
        executeGesture(gesture, callback)
    }

    /**
     * Execute gesture via AccessibilityService
     *
     * This is the core method that dispatches gestures to the system.
     */
    fun executeGesture(
        gesture: GestureDescription,
        callback: GestureResultCallback? = null
    ) {
        val gestureCallback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Gesture completed successfully")
                callback?.onCompleted(gestureDescription ?: gesture)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesture was cancelled")
                callback?.onCancelled(gestureDescription ?: gesture)
            }
        }

        val dispatched = accessibilityService.dispatchGesture(
            gesture,
            gestureCallback,
            null // Handler (null = main thread)
        )

        if (!dispatched) {
            Log.e(TAG, "Failed to dispatch gesture")
            callback?.onCancelled(gesture)
        }
    }

    /**
     * Helper: Create path for tap at point
     */
    private fun createTapPath(x: Float, y: Float): Path {
        return Path().apply {
            moveTo(x, y)
        }
    }
}

/**
 * Fling direction enum
 */
enum class FlingDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT
}
