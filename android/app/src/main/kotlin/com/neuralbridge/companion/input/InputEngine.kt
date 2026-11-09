package com.neuralbridge.companion.input

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Input Engine
 *
 * Handles text input, text selection, and clipboard operations.
 *
 * Input strategies:
 * 1. Accessibility actions (ACTION_SET_TEXT) - fastest
 * 2. IME injection - fallback for apps that don't support accessibility actions
 * 3. Clipboard paste - for long text
 */
class InputEngine(
    private val accessibilityService: AccessibilityService
) {
    companion object {
        private const val TAG = "InputEngine"
        private const val MAX_TEXT_LENGTH = 10000
    }

    private val clipboardManager: ClipboardManager by lazy {
        accessibilityService.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    /**
     * Input text into element
     *
     * @param element Target element (must be editable)
     * @param text Text to input
     * @param append Append to existing text (default: false = replace)
     * @return Success status
     */
    fun inputText(
        element: AccessibilityNodeInfo,
        text: String,
        append: Boolean = false
    ): Boolean {
        // Validate text length
        if (text.length > MAX_TEXT_LENGTH) {
            Log.e(TAG, "Text length ${text.length} exceeds maximum of $MAX_TEXT_LENGTH")
            return false
        }

        if (!element.isFocused) {
            element.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            element.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (!element.isEditable) {
            Log.w(TAG, "Element is not marked editable, attempting input anyway")
        }

        Log.d(TAG, "Inputting text: ${text.length} chars, append=$append")

        // Strategy 1: Try accessibility ACTION_SET_TEXT
        if (element.isAccessibilityFocused || element.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    if (append && element.text != null) {
                        "${element.text}$text"
                    } else {
                        text
                    }
                )
            }

            if (element.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                Log.d(TAG, "Text set via ACTION_SET_TEXT")
                return true
            }
        }

        // Strategy 2: Fallback to clipboard paste (for long text OR if ACTION_SET_TEXT failed)
        Log.w(TAG, "ACTION_SET_TEXT failed, falling back to clipboard paste")
        return inputViaClipboard(element, text, append)
    }

    /**
     * Input text via clipboard paste
     */
    private fun inputViaClipboard(
        element: AccessibilityNodeInfo,
        text: String,
        append: Boolean
    ): Boolean {
        Log.d(TAG, "Inputting text via clipboard paste")

        // Clear existing text if not appending
        if (!append) {
            element.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            // Select all text (ACTION_SET_SELECTION with full range)
            val existingText = element.text
            if (existingText != null) {
                val args = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existingText.length)
                }
                element.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            }
        }

        // Set clipboard
        val clip = ClipData.newPlainText("", text)
        clipboardManager.setPrimaryClip(clip)

        // Paste
        val pasted = element.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted) {
            Log.d(TAG, "Text pasted successfully")
            return true
        }

        Log.w(TAG, "Failed to paste text")
        return false
    }

    /**
     * Select text in element
     *
     * @param element Target element
     * @param start Start index
     * @param end End index
     * @return Success status
     */
    fun selectText(
        element: AccessibilityNodeInfo,
        start: Int,
        end: Int
    ): Boolean {
        Log.d(TAG, "Selecting text: $start to $end")

        if (!element.isEditable) {
            Log.w(TAG, "Element is not editable")
            return false
        }

        // Focus element
        if (!element.isAccessibilityFocused) {
            element.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        // Set selection
        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }

        val selected = element.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
        if (selected) {
            Log.d(TAG, "Text selected successfully")
            return true
        }

        Log.w(TAG, "Failed to select text")
        return false
    }

    /**
     * Clear text in element
     */
    fun clearText(element: AccessibilityNodeInfo): Boolean {
        return inputText(element, "", append = false)
    }

    /**
     * Get clipboard content
     */
    fun getClipboardText(): String? {
        val clip = clipboardManager.primaryClip
        if (clip != null && clip.itemCount > 0) {
            return clip.getItemAt(0).text?.toString()
        }
        return null
    }

    /**
     * Set clipboard content.
     *
     * On Android 10+ (API 29+) the system may silently reject clipboard writes from an
     * app that is not the current input focus / default IME. We write on the main thread
     * and read back to detect that case so callers aren't misled. Returns true only if
     * the write was observable via a read-back. (Bug N1)
     */
    fun setClipboardText(text: String): Boolean {
        val clip = ClipData.newPlainText("", text)
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { clipboardManager.setPrimaryClip(clip) } catch (e: Exception) {
                Log.w(TAG, "setPrimaryClip threw: ${e.message}")
            } finally { latch.countDown() }
        }
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        val readBack = getClipboardText()
        val ok = readBack == text
        if (!ok) {
            Log.w(TAG, "Clipboard write not confirmed on read-back (OS may restrict background clipboard writes). wanted=${text.length}chars got=${readBack?.length ?: -1}chars")
        } else {
            Log.d(TAG, "Clipboard set and verified: ${text.length} chars")
        }
        return ok
    }

    /**
     * Result of a key press. [success] means the action was accepted/dispatched;
     * [verified] means we could confirm an observable effect (false = fire-and-forget,
     * e.g. ACTION_IME_ENTER which the target app may map to a different editor action).
     */
    data class KeyResult(val success: Boolean, val mechanism: String, val verified: Boolean, val note: String? = null)

    fun pressKey(keyName: String, focusedNode: AccessibilityNodeInfo?): Boolean =
        pressKeyDetailed(keyName, focusedNode).success

    /**
     * Press a key by name, returning a detailed [KeyResult].
     *
     * Uses accessibility actions only (shell "input keyevent" requires INJECT_EVENTS permission).
     */
    fun pressKeyDetailed(keyName: String, focusedNode: AccessibilityNodeInfo?): KeyResult {
        Log.d(TAG, "Pressing key: $keyName")
        val key = keyName.lowercase()

        // Enter via ACTION_IME_ENTER (API 30+)
        if (key == "enter" && focusedNode != null) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                if (focusedNode.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    )) {
                    Log.d(TAG, "Enter dispatched via ACTION_IME_ENTER")
                    // IMPORTANT: acceptance of the action does NOT guarantee the app
                    // committed a newline/submit — many composers bind IME_ACTION_SEND,
                    // IME_ACTION_GO, etc. We cannot observe the downstream effect. (Bug N3)
                    return KeyResult(
                        success = true,
                        mechanism = "ACTION_IME_ENTER",
                        verified = false,
                        note = "Dispatched; delivery not verifiable. The target field may bind a different IME action (send/go/next) so a newline/submit is not guaranteed."
                    )
                }
            }
            // Pre-API 30 fallback: inject newline character (this IS verifiable via text change)
            val currentText = focusedNode.text?.toString() ?: ""
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$currentText\n")
            }
            if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Log.d(TAG, "Enter simulated via newline append")
                return KeyResult(true, "ACTION_SET_TEXT(newline)", verified = true)
            }
            return KeyResult(false, "enter", verified = false, note = "No enter mechanism accepted by the focused field.")
        }

        // Delete/Backspace via text truncation (verifiable)
        if ((key == "delete" || key == "backspace") && focusedNode != null) {
            val currentText = focusedNode.text?.toString() ?: ""
            if (currentText.isNotEmpty()) {
                val newText = currentText.dropLast(1)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                }
                if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    Log.d(TAG, "Delete performed via ACTION_SET_TEXT")
                    return KeyResult(true, "ACTION_SET_TEXT(truncate)", verified = true)
                }
            } else {
                Log.d(TAG, "Nothing to delete, field is empty")
                return KeyResult(true, "noop(empty)", verified = true, note = "Field already empty.")
            }
            return KeyResult(false, "delete", verified = false)
        }

        // Clipboard / selection actions on focused node
        if (focusedNode != null) {
            when (key) {
                "select_all" -> {
                    val text = focusedNode.text?.toString()
                    return if (!text.isNullOrEmpty()) {
                        val args = Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                        }
                        val ok = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
                        KeyResult(ok, "ACTION_SET_SELECTION", verified = ok,
                            note = if (!ok) "Field rejected selection." else null)
                    } else {
                        KeyResult(false, "select_all", verified = false,
                            note = "No readable text in focused field — nothing to select.")
                    }
                }
                "cut" -> { val ok = focusedNode.performAction(AccessibilityNodeInfo.ACTION_CUT); return KeyResult(ok, "ACTION_CUT", ok) }
                "copy" -> { val ok = focusedNode.performAction(AccessibilityNodeInfo.ACTION_COPY); return KeyResult(ok, "ACTION_COPY", ok) }
                "paste" -> { val ok = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE); return KeyResult(ok, "ACTION_PASTE", ok) }
            }
        }

        // Escape -> GLOBAL_ACTION_BACK (closest equivalent)
        if (key == "escape") {
            val result = accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Log.d(TAG, "Escape via GLOBAL_ACTION_BACK: $result")
            return KeyResult(result, "GLOBAL_ACTION_BACK", verified = result)
        }

        // Tab -> move focus forward
        if (key == "tab" && focusedNode != null) {
            if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
                val parent = focusedNode.parent
                if (parent != null) {
                    var foundCurrent = false
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChild(i)
                        if (child != null) {
                            if (foundCurrent && child.isFocusable) {
                                val result = child.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                Log.d(TAG, "Tab: moved focus to next sibling: $result")
                                @Suppress("DEPRECATION") child.recycle()
                                @Suppress("DEPRECATION") parent.recycle()
                                return KeyResult(result, "ACTION_FOCUS(next)", verified = result)
                            }
                            if (child == focusedNode) foundCurrent = true
                            @Suppress("DEPRECATION") child.recycle()
                        }
                    }
                    @Suppress("DEPRECATION") parent.recycle()
                }
            }
            Log.w(TAG, "Tab: could not find next focusable element")
            return KeyResult(false, "tab", verified = false, note = "No next focusable element found.")
        }

        // Space -> type a space character into focused node (verifiable)
        if (key == "space" && focusedNode != null) {
            val currentText = focusedNode.text?.toString() ?: ""
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$currentText ")
            }
            if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Log.d(TAG, "Space inserted via ACTION_SET_TEXT")
                return KeyResult(true, "ACTION_SET_TEXT(space)", verified = true)
            }
            return KeyResult(false, "space", verified = false)
        }

        Log.w(TAG, "Key '$keyName' not supported via accessibility actions")
        return KeyResult(false, "unsupported", verified = false, note = "Key not supported via accessibility actions.")
    }
}
