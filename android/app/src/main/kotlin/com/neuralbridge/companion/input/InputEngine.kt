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
     * Set clipboard content
     */
    fun setClipboardText(text: String) {
        val clip = ClipData.newPlainText("", text)
        clipboardManager.setPrimaryClip(clip)
        Log.d(TAG, "Clipboard set: ${text.length} chars")
    }

    /**
     * Press a key by name.
     *
     * Uses accessibility actions only (shell "input keyevent" requires INJECT_EVENTS permission).
     * Supported: enter, delete/backspace, select_all, cut, copy, paste, escape.
     * For arbitrary text, use inputText() instead.
     */
    fun pressKey(keyName: String, focusedNode: AccessibilityNodeInfo?): Boolean {
        Log.d(TAG, "Pressing key: $keyName")
        val key = keyName.lowercase()

        // Enter via ACTION_IME_ENTER (API 30+)
        if (key == "enter" && focusedNode != null) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                if (focusedNode.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    )) {
                    Log.d(TAG, "Enter pressed via ACTION_IME_ENTER")
                    return true
                }
            }
            // Pre-API 30 fallback: inject newline character
            val currentText = focusedNode.text?.toString() ?: ""
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$currentText\n")
            }
            if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Log.d(TAG, "Enter simulated via newline append")
                return true
            }
        }

        // Delete/Backspace via text truncation
        if ((key == "delete" || key == "backspace") && focusedNode != null) {
            val currentText = focusedNode.text?.toString() ?: ""
            if (currentText.isNotEmpty()) {
                val newText = currentText.dropLast(1)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                }
                if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    Log.d(TAG, "Delete performed via ACTION_SET_TEXT")
                    return true
                }
            } else {
                Log.d(TAG, "Nothing to delete, field is empty")
                return true
            }
        }

        // Clipboard actions on focused node
        if (focusedNode != null) {
            val clipAction = when (key) {
                "select_all" -> {
                    val text = focusedNode.text?.toString()
                    if (text != null) {
                        val args = Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                        }
                        focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
                    } else false
                }
                "cut" -> focusedNode.performAction(AccessibilityNodeInfo.ACTION_CUT)
                "copy" -> focusedNode.performAction(AccessibilityNodeInfo.ACTION_COPY)
                "paste" -> focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                else -> null
            }
            if (clipAction != null) {
                Log.d(TAG, "Clipboard action '$key': $clipAction")
                return clipAction
            }
        }

        // Escape -> GLOBAL_ACTION_BACK (closest equivalent)
        if (key == "escape") {
            val result = accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Log.d(TAG, "Escape via GLOBAL_ACTION_BACK: $result")
            return result
        }

        // Tab -> move focus forward
        if (key == "tab" && focusedNode != null) {
            val args = Bundle().apply {
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
                )
            }
            // Try ACTION_NEXT_AT_MOVEMENT_GRANULARITY, then fall back to finding next focusable
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
                                @Suppress("DEPRECATION")
                                child.recycle()
                                @Suppress("DEPRECATION")
                                parent.recycle()
                                return result
                            }
                            if (child == focusedNode) foundCurrent = true
                            @Suppress("DEPRECATION")
                            child.recycle()
                        }
                    }
                    @Suppress("DEPRECATION")
                    parent.recycle()
                }
            }
            Log.w(TAG, "Tab: could not find next focusable element")
            return false
        }

        // Space -> type a space character into focused node
        if (key == "space" && focusedNode != null) {
            val currentText = focusedNode.text?.toString() ?: ""
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$currentText ")
            }
            if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Log.d(TAG, "Space inserted via ACTION_SET_TEXT")
                return true
            }
        }

        Log.w(TAG, "Key '$keyName' not supported via accessibility actions")
        return false
    }
}
