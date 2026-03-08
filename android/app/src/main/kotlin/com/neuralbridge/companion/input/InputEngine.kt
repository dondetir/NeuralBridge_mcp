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

        if (!element.isEditable) {
            Log.w(TAG, "Element is not editable")
            return false
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

        // TODO: Strategy 3: IME injection
        // - Focus element
        // - Inject key events character by character
        // - This is slower but works for all apps
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
}
