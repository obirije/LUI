package com.lui.app.interceptor.actions

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.lui.app.helper.LuiAccessibilityService
import com.lui.app.helper.LuiLogger
import org.json.JSONArray
import org.json.JSONObject

object ScreenActions {

    private val genericContainers = setOf(
        "FrameLayout", "LinearLayout", "RelativeLayout", "ConstraintLayout",
        "ViewGroup", "View", "ScrollView", "NestedScrollView"
    )

    private fun ensureEnabled(context: Context): ActionResult? {
        if (LuiAccessibilityService.isEnabled) return null
        return try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Failure("I need accessibility access. Please enable LUI in the settings that just opened.")
        } catch (e: Exception) {
            ActionResult.Failure("I need accessibility access. Enable it in Settings > Accessibility > LUI.")
        }
    }

    /**
     * Snapshot the most useful window's elements. Used by `read_screen` and by
     * the post-action re-snapshot on tap/type/scroll.
     *
     * Returns (packageName, elements). Empty elements means nothing readable.
     *
     * Filters out IME windows and system UI so the agent never tries to tap
     * keyboard keys or navigation shade items thinking they're app controls.
     * `rootInActiveWindow` can return the IME window when the keyboard has
     * input focus — we detect that by package name as a fallback for devices
     * where window-type info isn't available from a bare root node.
     */
    private fun snapshotBestWindow(context: Context): Pair<String, List<LuiAccessibilityService.ScreenElement>> {
        val service = LuiAccessibilityService.instance ?: return "" to emptyList()
        val skipPackages = setOf(context.packageName, "com.android.systemui")

        var bestElements = mutableListOf<LuiAccessibilityService.ScreenElement>()
        var bestPkg = ""

        // The active window is usually what we want — unless it's the IME.
        val activeRoot = service.rootInActiveWindow
        if (activeRoot != null) {
            val activePkg = activeRoot.packageName?.toString() ?: ""
            if (activePkg !in skipPackages && !isImePackage(activePkg)) {
                val elements = mutableListOf<LuiAccessibilityService.ScreenElement>()
                service.traverseNodePublic(activeRoot, elements, 0, 15)
                if (elements.size > bestElements.size) {
                    bestElements = elements
                    bestPkg = activePkg
                }
            }
            activeRoot.recycle()
        }

        // Fall back to other windows. Use AccessibilityWindowInfo.type to
        // skip IMEs authoritatively (covers all keyboard packages, not just
        // Gboard).
        if (bestElements.size < 3) {
            val windows = try { service.windows } catch (_: Exception) { null }
            if (windows != null) {
                for (window in windows) {
                    if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM ||
                        window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                    val root = window.root ?: continue
                    val pkg = root.packageName?.toString() ?: ""
                    if (pkg in skipPackages || isImePackage(pkg)) { root.recycle(); continue }
                    val elements = mutableListOf<LuiAccessibilityService.ScreenElement>()
                    service.traverseNodePublic(root, elements, 0, 15)
                    root.recycle()
                    if (elements.size > bestElements.size) {
                        bestElements = elements
                        bestPkg = pkg
                    }
                }
            }
        }
        return bestPkg to bestElements
    }

    /** Heuristic: does this package look like an input method? */
    private fun isImePackage(pkg: String): Boolean =
        pkg.contains("inputmethod", ignoreCase = true) ||
        pkg.contains("ime", ignoreCase = true) ||
        pkg == "com.touchtype.swiftkey" ||
        pkg == "com.samsung.android.honeyboard" ||
        pkg == "com.fb.fb.gboard"

    /**
     * Render the snapshot as a numbered list the LLM can address by index.
     * Header carries the package + activity + keyboard state so the agent has
     * positional context without guessing.
     */
    private fun renderSnapshot(context: Context, pkg: String, elements: List<LuiAccessibilityService.ScreenElement>): String {
        val service = LuiAccessibilityService.instance
        val activity = service?.activeActivity()?.substringAfterLast('.').orEmpty()
        val keyboard = service?.isKeyboardOpen() == true
        val header = buildString {
            append("Screen (")
            append(pkg.ifBlank { "unknown" })
            if (activity.isNotBlank()) append(" · ").append(activity)
            if (keyboard) append(" · keyboard")
            append("):")
        }

        // Screen geometry for position hints (top/middle/bottom).
        val metrics = context.resources.displayMetrics
        val screenH = metrics.heightPixels
        val screenW = metrics.widthPixels

        val sb = StringBuilder()
        sb.appendLine(header)
        var rendered = 0
        for (el in elements) {
            if (rendered >= 50) break
            val label = bestLabelFor(el)
            // Skip elements that are pure noise (generic container with no
            // identifying signal AND no interactivity).
            val isInteractive = el.isClickable || el.isEditable || el.isScrollable
            if (label.isBlank() && !isInteractive) continue
            if (label in genericContainers && !isInteractive) continue

            val tags = buildList {
                if (el.isClickable) add("tap")
                if (el.isEditable) add("input")
                if (el.isScrollable) add("scroll")
                if (el.isChecked) add("checked")
                if (el.isSelected) add("selected")
            }.joinToString(",")
            val tagPart = if (tags.isNotEmpty()) " [$tags]" else ""
            val statePart = if (el.stateDescription.isNotBlank()) " (${el.stateDescription})" else ""
            // Position hint helps the LLM reason about typical UI layouts
            // (top bar, FAB bottom-right, bottom nav). Only emit for clickable
            // icon-style elements where the label is the className fallback.
            val pos = if (label == el.className) " @${positionHint(el.bounds, screenW, screenH)}" else ""
            val role = roleHint(el)
            val rolePart = if (role.isNotBlank()) " ($role)" else ""
            sb.appendLine("[${el.index}] $label$statePart$rolePart$tagPart$pos")
            rendered++
        }
        if (rendered == 0) sb.appendLine("(no readable elements — app may use custom views)")
        return sb.toString().trimEnd()
    }

    /** Pick the most informative label for this element. */
    private fun bestLabelFor(el: LuiAccessibilityService.ScreenElement): String {
        if (el.text.isNotBlank()) return el.text
        if (el.description.isNotBlank()) return el.description
        if (el.hint.isNotBlank()) return el.hint
        // Resource ID's local part is often meaningful: "send_btn", "search_icon".
        if (el.resourceId.isNotBlank()) {
            val local = el.resourceId.substringAfterLast('/').replace('_', ' ').trim()
            if (local.isNotBlank() && local != "id") return local
        }
        return el.className
    }

    /**
     * One-word inferred role to help the LLM cross-reference UI conventions.
     * Falls back to "" if nothing matches — the label is then the only signal.
     */
    private fun roleHint(el: LuiAccessibilityService.ScreenElement): String {
        val haystack = (el.description + " " + el.resourceId + " " + el.text + " " + el.hint).lowercase()
        return when {
            "search" in haystack -> "role: search"
            "send" in haystack && el.isClickable -> "role: send"
            "navigate up" in haystack || haystack.endsWith(" back") || haystack == "back" -> "role: back"
            "more options" in haystack || haystack == "more" -> "role: menu"
            "compose" in haystack || haystack.contains("new message") -> "role: compose"
            "FloatingActionButton" == el.className -> "role: fab"
            el.className == "EditText" || el.isEditable -> "role: input"
            el.className.contains("Tab") && el.isClickable -> "role: tab"
            else -> ""
        }
    }

    /** Rough screen-region label for icon elements without other signal. */
    private fun positionHint(bounds: android.graphics.Rect, screenW: Int, screenH: Int): String {
        if (screenW == 0 || screenH == 0) return ""
        val cx = bounds.centerX().toFloat() / screenW
        val cy = bounds.centerY().toFloat() / screenH
        val v = when {
            cy < 0.18f -> "top"
            cy > 0.82f -> "bottom"
            else -> "mid"
        }
        val h = when {
            cx < 0.33f -> "left"
            cx > 0.66f -> "right"
            else -> "center"
        }
        return "$v-$h"
    }

    private fun readScreenText(context: Context): String {
        val (pkg, elements) = snapshotBestWindow(context)
        if (elements.isEmpty()) {
            // Check whether LUI itself is the only foreground app (user is in
            // LUI's chat). Distinguish from "no readable elements anywhere".
            val service = LuiAccessibilityService.instance
            val active = service?.activePackage() ?: ""
            return if (active == context.packageName) {
                "LUI is currently in the foreground (you're talking to me directly). To control another app, the user needs to open it first — ask them to switch."
            } else {
                "Couldn't read the active screen — it may use custom views with no accessibility text. Active package: $active."
            }
        }
        return renderSnapshot(context, pkg, elements)
    }

    /**
     * Read the current screen and return an indexed list. Output format:
     *   Screen (com.android.settings · WifiActivity):
     *   [0] Wi-Fi [tap]
     *   [1] Bluetooth [tap]
     */
    fun readScreen(context: Context): ActionResult {
        ensureEnabled(context)?.let { return it }
        val text = readScreenText(context)
        LuiLogger.i("A11Y", "read_screen: ${text.lines().size} lines")
        return ActionResult.Success(text)
    }

    /** Append the post-action screen state so the LLM doesn't need a follow-up read. */
    private fun appendPostActionScreen(context: Context, headline: String, screenChanged: Boolean): ActionResult {
        val sb = StringBuilder(headline)
        if (!screenChanged) {
            sb.appendLine().append("(screen did not change within 700ms — element may not have responded)")
        }
        val screenText = readScreenText(context)
        sb.appendLine().appendLine().append(screenText)
        val firstLine = screenText.lineSequence().firstOrNull() ?: ""
        val rendered = screenText.lines().count { it.startsWith("[") }
        LuiLogger.i("A11Y", "post-action: changed=$screenChanged rendered=$rendered $firstLine")
        return ActionResult.Success(sb.toString())
    }

    fun findAndTap(context: Context, query: String): ActionResult {
        ensureEnabled(context)?.let { return it }
        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")
        if (!service.findAndTap(query)) {
            LuiLogger.w("A11Y", "Couldn't find: $query")
            return ActionResult.Failure("Couldn't find \"$query\" on screen.")
        }
        val changed = service.awaitScreenSettle()
        return appendPostActionScreen(context, "Tapped \"$query\".", changed)
    }

    fun tapByIndex(context: Context, index: Int): ActionResult {
        ensureEnabled(context)?.let { return it }
        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")
        if (!service.tapByIndex(index)) {
            return ActionResult.Failure("Element $index not in the last screen snapshot. Read the screen first.")
        }
        val changed = service.awaitScreenSettle()
        return appendPostActionScreen(context, "Tapped element $index.", changed)
    }

    fun scrollByIndex(context: Context, index: Int, direction: String): ActionResult {
        ensureEnabled(context)?.let { return it }
        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")
        if (!service.scrollByIndex(index, direction)) {
            return ActionResult.Failure("Couldn't scroll element $index. Make sure it's marked scrollable in the last read.")
        }
        val changed = service.awaitScreenSettle()
        return appendPostActionScreen(context, "Scrolled element $index $direction.", changed)
    }

    fun typeText(context: Context, text: String): ActionResult {
        ensureEnabled(context)?.let { return it }
        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")
        if (!service.typeText(text)) {
            return ActionResult.Failure("Couldn't type — no focused or editable text field found.")
        }
        // Typing rarely triggers a major redraw — short settle is fine.
        val changed = service.awaitScreenSettle(timeoutMs = 400, debounceMs = 100)
        return appendPostActionScreen(context, "Typed \"${text.take(50)}\".", changed)
    }

    fun tapAt(context: Context, x: Float, y: Float): ActionResult {
        ensureEnabled(context)?.let { return it }
        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")
        return if (service.tapAt(x, y)) {
            ActionResult.Success("Tapped at ($x, $y).")
        } else {
            ActionResult.Failure("Gesture failed.")
        }
    }

    fun scrollDown(context: Context): ActionResult {
        ensureEnabled(context)?.let { return it }
        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")
        val metrics = context.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.7f
        val endY = metrics.heightPixels * 0.3f
        if (!service.swipe(centerX, startY, centerX, endY)) return ActionResult.Failure("Scroll failed.")
        val changed = service.awaitScreenSettle()
        return appendPostActionScreen(context, "Scrolled down.", changed)
    }

    fun pressBack(context: Context): ActionResult {
        ensureEnabled(context)?.let { return it }
        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")
        if (!service.pressBack()) return ActionResult.Failure("Back press failed.")
        val changed = service.awaitScreenSettle()
        return appendPostActionScreen(context, "Pressed back.", changed)
    }

    fun openLui(context: Context): ActionResult {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } ?: return ActionResult.Failure("Couldn't find LUI.")
            context.startActivity(intent)
            ActionResult.Success("Switching back to LUI.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't switch: ${e.message}")
        }
    }

    fun pressHome(context: Context): ActionResult {
        ensureEnabled(context)?.let { return it }
        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")
        return if (service.pressHome()) {
            ActionResult.Success("Pressed home.")
        } else {
            ActionResult.Failure("Home press failed.")
        }
    }

    /**
     * Open an app and wait for it to be foreground, then return its first
     * indexed screen. Replaces the open_app + read_screen + retry loop.
     */
    fun openAppAndRead(context: Context, name: String): ActionResult {
        ensureEnabled(context)?.let { return it }
        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")

        val launchResult = AppLauncher.openApp(context, name)
        if (launchResult is ActionResult.Failure) return launchResult

        // We don't know the package name from openApp's text, so wait for the
        // foreground to be anything other than us, then settle.
        val deadline = System.currentTimeMillis() + 3500
        var pkg = service.activePackage()
        while (System.currentTimeMillis() < deadline && (pkg == context.packageName || pkg.isBlank())) {
            Thread.sleep(80)
            pkg = service.activePackage()
        }
        if (pkg == context.packageName || pkg.isBlank()) {
            return ActionResult.Failure("$name didn't come to the foreground in time.")
        }
        // Let the activity finish first-frame layout.
        service.awaitScreenSettle(timeoutMs = 1500, debounceMs = 300)
        val text = readScreenText(context)
        return ActionResult.Success("Opened $pkg.\n\n$text")
    }

    /**
     * Run a sequence of actions atomically. Each step waits-and-verifies before
     * the next runs. Steps is a JSON array, each item one of:
     *   {"action": "tap", "index": 7}
     *   {"action": "type", "text": "hello"}
     *   {"action": "scroll", "index": 4, "direction": "forward"}
     *   {"action": "back"}
     *   {"action": "wait", "ms": 500}
     *
     * Returns a per-step report and the final screen.
     */
    fun doSteps(context: Context, stepsJson: String): ActionResult {
        ensureEnabled(context)?.let { return it }
        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")

        val steps = try { JSONArray(stepsJson) } catch (e: Exception) {
            return ActionResult.Failure("Invalid steps JSON: ${e.message}")
        }

        val report = StringBuilder("Sequence (${steps.length()} steps):\n")
        var aborted = false
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val action = step.optString("action").lowercase()
            if (aborted) {
                report.append("— skipped: $action\n")
                continue
            }
            val (ok, label) = runStep(service, step, action)
            report.append(if (ok) "✓ $label\n" else "✗ $label\n")
            if (!ok) aborted = true
            if (ok) service.awaitScreenSettle(timeoutMs = 600, debounceMs = 120)
        }
        report.append("\n")
        report.append(readScreenText(context))
        return ActionResult.Success(report.toString().trimEnd())
    }

    private fun runStep(service: LuiAccessibilityService, step: JSONObject, action: String): Pair<Boolean, String> {
        return when (action) {
            "tap", "tap_by_index" -> {
                val idx = step.optInt("index", -1)
                if (idx < 0) false to "tap (missing index)" else service.tapByIndex(idx) to "tap $idx"
            }
            "type", "type_text" -> {
                val text = step.optString("text", "")
                service.typeText(text) to "type \"${text.take(40)}\""
            }
            "scroll", "scroll_by_index" -> {
                val idx = step.optInt("index", -1)
                val dir = step.optString("direction", "forward")
                if (idx < 0) false to "scroll (missing index)" else service.scrollByIndex(idx, dir) to "scroll $idx $dir"
            }
            "back" -> service.pressBack() to "back"
            "home" -> service.pressHome() to "home"
            "wait" -> {
                val ms = step.optLong("ms", 500L).coerceIn(50L, 5000L)
                Thread.sleep(ms); true to "wait ${ms}ms"
            }
            else -> false to "unknown action \"$action\""
        }
    }
}
