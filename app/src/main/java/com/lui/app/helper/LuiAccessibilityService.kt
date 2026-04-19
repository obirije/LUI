package com.lui.app.helper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Accessibility service that gives LUI the ability to:
 * 1. Read what's on screen (any app), as an indexed list
 * 2. Tap UI elements by their assigned screen index, or by text/resource ID
 * 3. Type text into the focused input field (caret-preserving)
 * 4. Scroll a specific scrollable container by index
 * 5. Perform gestures (raw coordinate tap, swipe)
 *
 * Tier 4 — last resort when no API, deep link, or headless hook exists.
 * Must be manually enabled by the user in Settings > Accessibility > LUI.
 *
 * The **index-first addressing scheme** (`readScreen()` returns each visible
 * element with a stable-per-snapshot integer) lets the LLM say "tap 7" without
 * fighting locale, list de-dup, or text ambiguity. The list is regenerated on
 * every `readScreen()` call so indices are only valid within that snapshot.
 */
class LuiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LuiA11y"
        private const val MIN_ELEMENT_SIZE_PX = 5
        private const val MIN_VISIBILITY_PCT = 0.01f

        var instance: LuiAccessibilityService? = null
            private set

        val isEnabled: Boolean get() = instance != null
    }

    /** Last `readScreen()` snapshot, keyed by element index. Cleared on each new read. */
    private val lastSnapshot = mutableMapOf<Int, NodeRef>()

    /** Package the current snapshot was taken from. Used to invalidate the
     *  snapshot when the foreground app changes — stale indices then fail
     *  loudly instead of resolving against the wrong UI. */
    @Volatile private var lastSnapshotPackage: String = ""

    /** Updated by `onAccessibilityEvent` whenever the active window's contents
     *  change. Used by `awaitScreenSettle` to detect when an action's effect
     *  has landed without polling the tree blindly. */
    @Volatile private var lastScreenChangeAt: Long = 0L

    /** Most recent activity className from a TYPE_WINDOW_STATE_CHANGED event. */
    @Volatile private var lastActivity: String = ""

    /** Most recent foreground package observed via window-state events. */
    @Volatile private var lastPackage: String = ""

    private data class NodeRef(
        val resourceId: String,
        val text: String,
        val description: String,
        val className: String,
        val bounds: Rect,
        val isClickable: Boolean,
        val isScrollable: Boolean,
        val isEditable: Boolean
    )

    override fun onServiceConnected() {
        instance = this
        LuiLogger.i("A11Y", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastScreenChangeAt = System.currentTimeMillis()
                event.className?.toString()?.let { lastActivity = it }
                val pkg = event.packageName?.toString() ?: ""
                if (pkg.isNotBlank() && pkg != lastPackage) {
                    lastPackage = pkg
                    // Foreground app changed — any cached indices belong to a
                    // different UI now. Drop them so stale taps fail clearly.
                    if (lastSnapshotPackage.isNotBlank() && lastSnapshotPackage != pkg) {
                        lastSnapshot.clear()
                        lastSnapshotPackage = ""
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                lastScreenChangeAt = System.currentTimeMillis()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        LuiLogger.i("A11Y", "Accessibility service disconnected")
        super.onDestroy()
    }

    // ── Screen Reading ─────────────────────────────────────────────────────

    /**
     * Index of an element within a `readScreen()` snapshot. The LLM uses these
     * to address elements (`tap 7`, `scroll 3`, etc.). Caller is responsible
     * for re-reading after any UI mutation since indices are not stable across
     * snapshots.
     */
    data class ScreenElement(
        val index: Int,
        val text: String,
        val description: String,
        val hint: String,
        val resourceId: String,         // e.g. "com.whatsapp:id/send_btn" — most stable identifier
        val stateDescription: String,   // e.g. "50%" for sliders, "On" for toggles
        val className: String,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val isChecked: Boolean,
        val isSelected: Boolean,
        val bounds: Rect,
        val depth: Int
    )

    fun readScreen(maxDepth: Int = 15): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val screenBounds = Rect()
        try {
            root.getBoundsInScreen(screenBounds) // fall back to node bounds if window unavailable
        } catch (_: Exception) {}

        val elements = mutableListOf<ScreenElement>()
        val counter = IndexCounter()
        lastSnapshot.clear()
        lastSnapshotPackage = root.packageName?.toString() ?: ""
        traverseNode(root, elements, counter, 0, maxDepth, screenBounds)
        root.recycle()
        return elements
    }

    /** Public wrapper for ScreenActions to traverse arbitrary root nodes. */
    fun traverseNodePublic(node: AccessibilityNodeInfo, elements: MutableList<ScreenElement>, depth: Int, maxDepth: Int) {
        val screenBounds = Rect()
        node.getBoundsInScreen(screenBounds)
        val counter = IndexCounter()
        // Set package context — caller may be iterating multiple windows.
        lastSnapshot.clear()
        lastSnapshotPackage = node.packageName?.toString() ?: ""
        traverseNode(node, elements, counter, depth, maxDepth, screenBounds)
    }

    private class IndexCounter { var value = 0; fun next() = value++ }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        elements: MutableList<ScreenElement>,
        counter: IndexCounter,
        depth: Int,
        maxDepth: Int,
        screenBounds: Rect
    ) {
        if (depth > maxDepth) return

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString() ?: "" else ""
        val resourceId = node.viewIdResourceName ?: ""
        val stateDesc = if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString() ?: "" else ""
        val className = node.className?.toString()?.substringAfterLast(".") ?: ""

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val hasContent = text.isNotBlank() || desc.isNotBlank() || hint.isNotBlank() ||
            stateDesc.isNotBlank() || resourceId.isNotBlank()
        val isInteractive = node.isClickable || node.isCheckable || node.isEditable || node.isScrollable
        val sizeOk = bounds.width() >= MIN_ELEMENT_SIZE_PX && bounds.height() >= MIN_ELEMENT_SIZE_PX
        val visibleEnough = sizeOk && visibilityFraction(bounds, screenBounds) >= MIN_VISIBILITY_PCT

        if (visibleEnough && (hasContent || isInteractive)) {
            val idx = counter.next()
            val element = ScreenElement(
                index = idx,
                text = text,
                description = desc,
                hint = hint,
                resourceId = resourceId,
                stateDescription = stateDesc,
                className = className,
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                isChecked = node.isChecked,
                isSelected = node.isSelected,
                bounds = Rect(bounds),
                depth = depth
            )
            elements.add(element)
            lastSnapshot[idx] = NodeRef(
                resourceId = resourceId,
                text = text,
                description = desc,
                className = className,
                bounds = Rect(bounds),
                isClickable = node.isClickable,
                isScrollable = node.isScrollable,
                isEditable = node.isEditable
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, elements, counter, depth + 1, maxDepth, screenBounds)
            child.recycle()
        }
    }

    /** Fraction of `nodeBounds` that overlaps with `screenBounds`. 0–1. */
    private fun visibilityFraction(nodeBounds: Rect, screenBounds: Rect): Float {
        if (screenBounds.isEmpty || nodeBounds.isEmpty) return 1f
        val intersection = Rect(nodeBounds)
        if (!intersection.intersect(screenBounds)) return 0f
        val nodeArea = nodeBounds.width().toLong() * nodeBounds.height().toLong()
        if (nodeArea <= 0) return 0f
        val visibleArea = intersection.width().toLong() * intersection.height().toLong()
        return visibleArea.toFloat() / nodeArea.toFloat()
    }

    // ── Window filtering ───────────────────────────────────────────────────

    /** Returns true if any IME window is currently active. Cheap check. */
    fun isKeyboardOpen(): Boolean {
        return try {
            windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        } catch (_: Exception) { false }
    }

    /** Active foreground package, or "" if unavailable. */
    fun activePackage(): String {
        return try {
            rootInActiveWindow?.let { r ->
                val p = r.packageName?.toString() ?: ""
                r.recycle(); p
            } ?: ""
        } catch (_: Exception) { "" }
    }

    /** Most recent activity className we've observed (from window-state events). */
    fun activeActivity(): String = lastActivity

    // ── Wait helpers ───────────────────────────────────────────────────────

    /**
     * Block until accessibility events stop firing (a "settled" screen) or the
     * timeout is reached. Returns true if a change was actually observed during
     * the wait window — false if the screen never reacted (e.g. tap was a no-op).
     *
     * Caller is expected to be on a background thread (Dispatchers.IO) — this
     * uses Thread.sleep().
     */
    fun awaitScreenSettle(timeoutMs: Long = 700, debounceMs: Long = 120): Boolean {
        val start = System.currentTimeMillis()
        val baseline = lastScreenChangeAt
        // Phase 1: wait for any change (or give up).
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (lastScreenChangeAt > baseline) break
            Thread.sleep(40)
        }
        if (lastScreenChangeAt == baseline) {
            LuiLogger.d("A11Y", "settle: no change in ${timeoutMs}ms")
            return false
        }
        // Phase 2: wait for the change stream to quiet down (debounce).
        val absDeadline = start + timeoutMs + debounceMs
        while (System.currentTimeMillis() < absDeadline) {
            if (System.currentTimeMillis() - lastScreenChangeAt >= debounceMs) break
            Thread.sleep(40)
        }
        LuiLogger.d("A11Y", "settle: changed after ${System.currentTimeMillis() - start}ms")
        return true
    }

    /**
     * Block until `targetPackage` is the foreground package, or the timeout is
     * reached. Returns true if the package landed in time.
     */
    fun awaitForegroundPackage(targetPackage: String, timeoutMs: Long = 3000): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (activePackage() == targetPackage) {
                // Let the activity render its initial frame.
                Thread.sleep(150)
                return true
            }
            Thread.sleep(50)
        }
        return false
    }

    // ── Tap by Index ───────────────────────────────────────────────────────

    /**
     * Tap the element at the given snapshot index. Caller must have called
     * `readScreen()` first; returns false if the index is unknown.
     *
     * Resolves the live node by re-finding the same resource ID / text / bounds
     * combination so a stale `AccessibilityNodeInfo` reference isn't held.
     */
    fun tapByIndex(index: Int): Boolean {
        val ref = lastSnapshot[index] ?: run {
            LuiLogger.w("A11Y", "tapByIndex($index): no such element in last snapshot")
            return false
        }
        val live = resolveNode(ref) ?: run {
            LuiLogger.w("A11Y", "tapByIndex($index): element disappeared, falling back to coordinates")
            return tapAt(ref.bounds.exactCenterX(), ref.bounds.exactCenterY())
        }
        val ok = tapNode(live)
        live.recycle()
        return ok
    }

    /**
     * Scroll the indexed element (must be scrollable) in the given direction.
     * `direction` ∈ {"forward", "backward"}.
     */
    fun scrollByIndex(index: Int, direction: String): Boolean {
        val ref = lastSnapshot[index] ?: return false
        val live = resolveNode(ref) ?: return false
        val action = when (direction.lowercase()) {
            "forward", "down", "next" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "backward", "back", "up", "previous" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val ok = live.performAction(action)
        live.recycle()
        return ok
    }

    /** Re-find a node matching the snapshot ref. Caller owns recycling the result. */
    private fun resolveNode(ref: NodeRef): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        try {
            // Resource ID — most stable
            if (ref.resourceId.isNotBlank()) {
                val byId = root.findAccessibilityNodeInfosByViewId(ref.resourceId)
                val match = byId.firstOrNull { sameBoundsApprox(it, ref.bounds) }
                    ?: byId.firstOrNull()
                if (match != null) return match
            }
            // Text
            if (ref.text.isNotBlank()) {
                val byText = root.findAccessibilityNodeInfosByText(ref.text)
                val match = byText.firstOrNull { sameBoundsApprox(it, ref.bounds) }
                    ?: byText.firstOrNull { it.isClickable || it.isScrollable || it.isEditable }
                    ?: byText.firstOrNull()
                if (match != null) return match
            }
        } finally {
            root.recycle()
        }
        return null
    }

    private fun sameBoundsApprox(node: AccessibilityNodeInfo, target: Rect, tolPx: Int = 8): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        return Math.abs(r.centerX() - target.centerX()) <= tolPx &&
            Math.abs(r.centerY() - target.centerY()) <= tolPx
    }

    // ── Find and Tap by Query (legacy / fallback) ──────────────────────────

    /**
     * Find a UI element by resource ID → text → contentDescription and tap it.
     * Prefer `tapByIndex` after a `readScreen()` for unambiguous targeting.
     */
    fun findAndTap(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, query) ?: run { root.recycle(); return false }
        val ok = tapNode(node)
        node.recycle()
        root.recycle()
        return ok
    }

    private fun findNode(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        // 1. Resource ID match — most stable.
        if (query.contains(":id/") || query.contains("/")) {
            val byId = root.findAccessibilityNodeInfosByViewId(query)
            val clickable = byId.find { it.isClickable } ?: byId.firstOrNull()
            if (clickable != null) return clickable
        }
        // 2. Text match.
        val byText = root.findAccessibilityNodeInfosByText(query)
        if (byText.isNotEmpty()) {
            val clickable = byText.find { it.isClickable }
            if (clickable != null) return clickable
            for (n in byText) {
                val parent = findClickableParent(n)
                if (parent != null) return parent
            }
            return byText[0]
        }
        // 3. ContentDescription match.
        return findNodeByDescription(root, query.lowercase())
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
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            LuiLogger.i("A11Y", "Tapped via ACTION_CLICK: ${node.text ?: node.contentDescription ?: node.viewIdResourceName}")
            return true
        }
        // Fall back to the nearest clickable parent.
        val parent = findClickableParent(node)
        if (parent != null && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            LuiLogger.i("A11Y", "Tapped via parent ACTION_CLICK")
            return true
        }
        // Last resort: synthesized gesture at the element center.
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return tapAt(bounds.exactCenterX(), bounds.exactCenterY())
    }

    // ── Raw Gestures ───────────────────────────────────────────────────────

    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        LuiLogger.i("A11Y", "Gesture tap at ($x, $y)")
        return dispatchGesture(gesture, null, null)
    }

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

    // ── Text Input ─────────────────────────────────────────────────────────

    /**
     * Type text into the currently focused input field. Preserves caret if
     * there's a selection, otherwise replaces the field's content. Falls back
     * to the first `isEditable` node if no node has input focus.
     */
    fun typeText(text: String): Boolean {
        val target = findInputTarget() ?: return false
        try {
            val current = target.text?.toString() ?: ""
            val isHint = Build.VERSION.SDK_INT >= 26 &&
                target.hintText?.toString().equals(current, ignoreCase = false) &&
                current.isNotEmpty()
            val baseText = if (isHint) "" else current
            val selStart = target.textSelectionStart.coerceIn(0, baseText.length)
            val selEnd = target.textSelectionEnd.coerceIn(selStart, baseText.length)

            val newText = if (selStart in 0..baseText.length && selEnd >= selStart) {
                baseText.substring(0, selStart) + text + baseText.substring(selEnd)
            } else {
                text
            }
            val newCaret = (selStart + text.length).coerceAtMost(newText.length)

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCaret)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCaret)
                }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            }
            LuiLogger.i("A11Y", "Typed ${text.length} chars → $ok")
            return ok
        } finally {
            target.recycle()
        }
    }

    /** Find the right node to receive text. Owned by caller — must be recycled. */
    private fun findInputTarget(): AccessibilityNodeInfo? {
        // 1. Whoever the system says has input focus is the most reliable choice.
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        // 2. Fall back to the first editable node visible in the active window.
        val root = rootInActiveWindow ?: return null
        try {
            return findFirstEditable(root)
        } finally {
            root.recycle()
        }
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditable(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    // ── Global Actions ─────────────────────────────────────────────────────

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
}
