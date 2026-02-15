package com.neuralbridge.companion.uitree

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.neuralbridge.companion.service.Bounds
import com.neuralbridge.companion.service.UiElement
import com.neuralbridge.companion.service.UiTree
import java.security.MessageDigest

/**
 * UI Tree Walker
 *
 * Walks the accessibility node tree and extracts semantic information
 * optimized for AI agent consumption.
 *
 * Features:
 * - Generates stable element IDs (hash-based)
 * - Classifies elements into semantic types (button, input, text, etc.)
 * - Creates human-readable AI descriptions
 * - Filters invisible/unimportant elements
 * - Maintains parent-child relationships
 */
class UiTreeWalker(
    private val accessibilityService: AccessibilityService
) {
    companion object {
        private const val TAG = "UiTreeWalker"
        private const val MAX_RECURSION_DEPTH = 50

        // Semantic type classifications
        private val BUTTON_CLASSES = setOf(
            "android.widget.Button",
            "android.widget.ImageButton",
            "androidx.appcompat.widget.AppCompatButton",
            "com.google.android.material.button.MaterialButton"
        )

        private val INPUT_CLASSES = setOf(
            "android.widget.EditText",
            "androidx.appcompat.widget.AppCompatEditText",
            "com.google.android.material.textfield.TextInputEditText"
        )

        private val TEXT_CLASSES = setOf(
            "android.widget.TextView",
            "android.widget.TextClock",
            "androidx.appcompat.widget.AppCompatTextView"
        )

        private val IMAGE_CLASSES = setOf(
            "android.widget.ImageView",
            "androidx.appcompat.widget.AppCompatImageView"
        )

        private val LIST_CLASSES = setOf(
            "android.widget.ListView",
            "android.widget.RecyclerView",
            "androidx.recyclerview.widget.RecyclerView"
        )

        private val SCROLL_CLASSES = setOf(
            "android.widget.ScrollView",
            "android.widget.HorizontalScrollView",
            "androidx.core.widget.NestedScrollView"
        )
    }

    /**
     * Walk the UI tree and extract all elements
     *
     * @param rootNode Root accessibility node (typically from rootInActiveWindow)
     * @param includeInvisible Include invisible elements
     * @param maxDepth Maximum tree depth (0 = unlimited)
     * @return UI tree with all elements
     */
    suspend fun walkTree(
        rootNode: AccessibilityNodeInfo?,
        includeInvisible: Boolean = false,
        maxDepth: Int = 0
    ): UiTree {
        val startTime = System.currentTimeMillis()
        val elements = mutableListOf<UiElement>()
        var totalNodes = 0

        if (rootNode == null) {
            Log.w(TAG, "Root node is null, returning empty tree")
            return UiTree(
                elements = emptyList(),
                foregroundApp = "unknown",
                totalNodes = 0,
                captureTimestamp = startTime
            )
        }

        // Get foreground app package
        val foregroundApp = rootNode.packageName?.toString() ?: "unknown"

        Log.d(TAG, "Walking UI tree for app: $foregroundApp")

        // Recursive tree walk
        walkNode(
            node = rootNode,
            parentId = null,
            depth = 0,
            maxDepth = maxDepth,
            includeInvisible = includeInvisible,
            elements = elements,
            totalNodes = { totalNodes++ }
        )

        val elapsedMs = System.currentTimeMillis() - startTime
        Log.i(TAG, "UI tree walk complete: ${elements.size} elements, $totalNodes nodes, ${elapsedMs}ms")

        return UiTree(
            elements = elements,
            foregroundApp = foregroundApp,
            totalNodes = totalNodes,
            captureTimestamp = startTime
        )
    }

    /**
     * Recursively walk node tree
     */
    private fun walkNode(
        node: AccessibilityNodeInfo,
        parentId: String?,
        depth: Int,
        maxDepth: Int,
        includeInvisible: Boolean,
        elements: MutableList<UiElement>,
        totalNodes: () -> Unit
    ) {
        totalNodes()

        // Enforce maximum recursion depth for security
        if (depth >= MAX_RECURSION_DEPTH) {
            Log.w(TAG, "Maximum recursion depth ($MAX_RECURSION_DEPTH) reached, stopping tree walk")
            return
        }

        // Check depth limit
        if (maxDepth > 0 && depth >= maxDepth) {
            return
        }

        // Skip invisible nodes unless requested
        if (!includeInvisible && !node.isVisibleToUser) {
            return
        }

        // Extract element information
        val element = extractElement(node, parentId, depth)

        // Add to list
        elements.add(element)

        // Walk children
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue

            try {
                walkNode(
                    node = child,
                    parentId = element.elementId,
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    includeInvisible = includeInvisible,
                    elements = elements,
                    totalNodes = totalNodes
                )
            } finally {
                // Note: recycle() is deprecated but still necessary for memory management
                // on API levels < 34. Safe to call on all versions.
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
    }

    /**
     * Extract element information from node
     */
    private fun extractElement(
        node: AccessibilityNodeInfo,
        parentId: String?,
        depth: Int
    ): UiElement {
        // Get basic properties
        val className = node.className?.toString() ?: ""
        val resourceId = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        // Get bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val elementBounds = Bounds(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom
        )

        // Generate stable element ID
        val elementId = generateElementId(
            resourceId = resourceId,
            className = className,
            text = text,
            bounds = elementBounds,
            parentId = parentId
        )

        // Classify semantic type
        val semanticType = classifySemanticType(node, className)

        // Generate AI description
        val aiDescription = generateAiDescription(
            node = node,
            semanticType = semanticType,
            text = text,
            contentDesc = contentDesc,
            className = className
        )

        return UiElement(
            elementId = elementId,
            resourceId = resourceId.ifEmpty { null },
            className = className.ifEmpty { null },
            text = text.ifEmpty { null },
            contentDescription = contentDesc.ifEmpty { null },
            bounds = elementBounds,
            visible = node.isVisibleToUser,
            enabled = node.isEnabled,
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            focusable = node.isFocusable,
            longClickable = node.isLongClickable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            semanticType = semanticType,
            aiDescription = aiDescription
        )
    }

    /**
     * Generate stable element ID
     *
     * Uses hash of key properties to ensure consistency across UI tree retrievals.
     */
    private fun generateElementId(
        resourceId: String,
        className: String,
        text: String,
        bounds: Bounds,
        parentId: String?
    ): String {
        // Round bounds to reduce sensitivity to minor position changes
        val roundedBounds = Bounds(
            left = (bounds.left / 10) * 10,
            top = (bounds.top / 10) * 10,
            right = (bounds.right / 10) * 10,
            bottom = (bounds.bottom / 10) * 10
        )

        // Combine properties
        val combined = "$resourceId|$className|$text|$roundedBounds|$parentId"

        // Hash to create stable ID
        return hashString(combined)
    }

    /**
     * Hash string to create stable ID
     */
    private fun hashString(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    /**
     * Classify element into semantic type
     */
    private fun classifySemanticType(
        node: AccessibilityNodeInfo,
        className: String
    ): String {
        return when {
            className in BUTTON_CLASSES || node.isClickable -> "button"
            className in INPUT_CLASSES || node.isEditable -> "input"
            className in TEXT_CLASSES -> "text"
            className in IMAGE_CLASSES -> "image"
            className in LIST_CLASSES -> "list"
            className in SCROLL_CLASSES || node.isScrollable -> "scroll"
            node.isCheckable -> "checkbox"
            else -> "container"
        }
    }

    /**
     * Generate human-readable AI description
     */
    private fun generateAiDescription(
        node: AccessibilityNodeInfo,
        semanticType: String,
        text: String,
        contentDesc: String,
        className: String
    ): String {
        val parts = mutableListOf<String>()

        // Add semantic type
        parts.add(semanticType.capitalize())

        // Add text or content description
        when {
            text.isNotEmpty() -> parts.add("\"$text\"")
            contentDesc.isNotEmpty() -> parts.add("\"$contentDesc\"")
        }

        // Add interaction hints
        when {
            node.isClickable -> parts.add("(clickable)")
            node.isEditable -> parts.add("(editable)")
            node.isCheckable -> parts.add(if (node.isChecked) "(checked)" else "(unchecked)")
            node.isScrollable -> parts.add("(scrollable)")
        }

        // Add state
        if (!node.isEnabled) {
            parts.add("[disabled]")
        }
        if (!node.isVisibleToUser) {
            parts.add("[hidden]")
        }

        return parts.joinToString(" ")
    }

    /**
     * Find elements matching criteria
     */
    fun findElements(
        tree: UiTree,
        text: String? = null,
        resourceId: String? = null,
        contentDesc: String? = null,
        className: String? = null,
        visibleOnly: Boolean = true
    ): List<UiElement> {
        return tree.elements.filter { element ->
            // Apply filters
            if (visibleOnly && !element.visible) return@filter false

            if (text != null && !(element.text?.contains(text, ignoreCase = true) == true)) {
                return@filter false
            }

            if (resourceId != null && !(element.resourceId?.endsWith(resourceId) == true)) {
                return@filter false
            }

            if (contentDesc != null && !(element.contentDescription?.contains(contentDesc, ignoreCase = true) == true)) {
                return@filter false
            }

            if (className != null && !(element.className?.endsWith(className) == true)) {
                return@filter false
            }

            true
        }
    }
}

/**
 * Extension: Capitalize first character
 */
private fun String.capitalize(): String {
    return if (isEmpty()) this else this[0].uppercase() + this.substring(1)
}
