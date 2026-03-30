package com.lui.app.helper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that gives LUI the ability to:
 * 1. Read what's on screen (any app)
 * 2. Find and tap UI elements by text, description, or ID
 * 3. Type text into focused fields
 * 4. Perform gestures (swipe, scroll)
 *
 * This is Tier 4 — the last resort when no API, deep link, or headless hook exists.
 * Must be manually enabled by the user in Settings > Accessibility > LUI.
 */
class LuiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LuiA11y"
        var instance: LuiAccessibilityService? = null
            private set

        val isEnabled: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        instance = this
        LuiLogger.i("A11Y", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to react to events — we query on demand
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        LuiLogger.i("A11Y", "Accessibility service disconnected")
        super.onDestroy()
    }

    // ── Screen Reading ──

    /**
     * Read the current screen hierarchy and return a text description of visible elements.
     */
    fun readScreen(maxDepth: Int = 15): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<ScreenElement>()
        traverseNode(root, elements, 0, maxDepth)
        root.recycle()
        return elements
    }

    data class ScreenElement(
        val text: String,
        val description: String,
        val className: String,
        val isClickable: Boolean,
        val bounds: Rect,
        val depth: Int
    )

    /** Public wrapper for ScreenActions to traverse arbitrary root nodes */
    fun traverseNodePublic(node: AccessibilityNodeInfo, elements: MutableList<ScreenElement>, depth: Int, maxDepth: Int) {
        traverseNode(node, elements, depth, maxDepth)
    }

    private fun traverseNode(node: AccessibilityNodeInfo, elements: MutableList<ScreenElement>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString()?.substringAfterLast(".") ?: ""

        // Collect nodes with any readable content or interactivity
        if (text.isNotBlank() || desc.isNotBlank() || node.isClickable) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            // Skip zero-size elements
            if (bounds.width() > 0 && bounds.height() > 0) {
                elements.add(ScreenElement(
                    text = text,
                    description = desc,
                    className = className,
                    isClickable = node.isClickable,
                    bounds = bounds,
                    depth = depth
                ))
            }
        }

        // Always recurse into children regardless of whether this node had content
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, elements, depth + 1, maxDepth)
            child.recycle()
        }
    }

    // ── Find and Tap ──

    /**
     * Find a UI element by text or content description and tap it.
     * Returns true if found and tapped.
     */
    fun findAndTap(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, query.lowercase())
        val result = if (node != null) {
            tapNode(node)
        } else {
            false
        }
        root.recycle()
        return result
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        // Try exact text match first
        val byText = root.findAccessibilityNodeInfosByText(query)
        if (byText.isNotEmpty()) {
            // Prefer clickable nodes
            val clickable = byText.find { it.isClickable }
            if (clickable != null) return clickable
            // Otherwise find nearest clickable parent
            for (node in byText) {
                val clickableParent = findClickableParent(node)
                if (clickableParent != null) return clickableParent
            }
            return byText[0]
        }

        // Try content description
        return findNodeByDescription(root, query)
    }

    private fun findNodeByDescription(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains(query)) {
            return if (node.isClickable) node else findClickableParent(node) ?: node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByDescription(child, query)
            if (result != null) return result
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        // Try ACTION_CLICK first
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            LuiLogger.i("A11Y", "Tapped via ACTION_CLICK: ${node.text ?: node.contentDescription}")
            return true
        }

        // Fallback: gesture tap at the center of the element
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    // ── Gesture Taps ──

    /**
     * Tap at specific screen coordinates.
     */
    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        LuiLogger.i("A11Y", "Gesture tap at ($x, $y)")
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Swipe gesture (for scrolling).
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        LuiLogger.i("A11Y", "Gesture swipe ($startX,$startY) → ($endX,$endY)")
        return dispatchGesture(gesture, null, null)
    }

    // ── Text Input ──

    /**
     * Type text into the currently focused field.
     */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditText(root)
        root.recycle()

        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            LuiLogger.i("A11Y", "Typed text: ${text.take(30)}... → $result")
            return result
        }
        return false
    }

    private fun findFocusedEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.className?.toString()?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditText(child)
            if (result != null) return result
        }
        return null
    }

    // ── Back / Home / Recents ──

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
}
