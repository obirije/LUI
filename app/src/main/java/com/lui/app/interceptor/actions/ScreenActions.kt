package com.lui.app.interceptor.actions

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.lui.app.helper.LuiAccessibilityService
import com.lui.app.helper.LuiLogger

object ScreenActions {

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
     * Read the current screen and return a text summary of visible elements.
     * Tries all windows — if the foreground app is LUI, reads the window behind it.
     */
    fun readScreen(context: Context): ActionResult {
        ensureEnabled(context)?.let { return it }

        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")

        // Read from all windows, pick the best one (most elements, skip LUI and systemui)
        val windows = try { service.windows } catch (_: Exception) { null }
        val skipPackages = setOf(context.packageName, "com.android.systemui")

        var bestElements = mutableListOf<LuiAccessibilityService.ScreenElement>()
        var bestPkg = "unknown"

        // Strategy 1: Try rootInActiveWindow first — this is the focused app
        val activeRoot = service.rootInActiveWindow
        if (activeRoot != null) {
            val activePkg = activeRoot.packageName?.toString() ?: ""
            LuiLogger.d("A11Y", "rootInActiveWindow: pkg=$activePkg childCount=${activeRoot.childCount}")
            if (activePkg !in skipPackages) {
                val elements = mutableListOf<LuiAccessibilityService.ScreenElement>()
                service.traverseNodePublic(activeRoot, elements, 0, 15)
                LuiLogger.d("A11Y", "rootInActiveWindow traversal: ${elements.size} elements from $activePkg")
                if (elements.size > bestElements.size) {
                    bestElements = elements
                    bestPkg = activePkg
                }
            } else {
                LuiLogger.d("A11Y", "rootInActiveWindow skipped (in skipPackages): $activePkg")
            }
            activeRoot.recycle()
        } else {
            LuiLogger.d("A11Y", "rootInActiveWindow is null")
        }

        // Strategy 2: Iterate windows if rootInActiveWindow didn't work well
        if (bestElements.size < 3 && windows != null) {
            for (w in windows) {
                val r = w.root
                val pkg = r?.packageName?.toString() ?: "null"
                LuiLogger.d("A11Y", "Window: pkg=$pkg type=${w.type} layer=${w.layer} active=${w.isActive}")
                r?.recycle()
            }

            for (window in windows) {
                val root = window.root
                if (root == null) {
                    LuiLogger.d("A11Y", "Window root is null for type=${window.type}")
                    continue
                }
                val pkg = root.packageName?.toString() ?: ""

                if (pkg in skipPackages) {
                    root.recycle()
                    continue
                }

                val elements = mutableListOf<LuiAccessibilityService.ScreenElement>()
                service.traverseNodePublic(root, elements, 0, 15)
                LuiLogger.d("A11Y", "Window $pkg: ${elements.size} elements, childCount=${root.childCount}")
                root.recycle()

                if (elements.size > bestElements.size) {
                    bestElements = elements
                    bestPkg = pkg
                }
            }
        }

        if (bestElements.isEmpty()) {
            return ActionResult.Success("I can only read other apps' screens. Switch to the app you want me to read, then ask again.")
        }

        val sb = StringBuilder()
        for (el in bestElements.take(30)) {
            val label = el.text.ifBlank { el.description }.ifBlank { el.className }
            if (label.isBlank() || label == "FrameLayout" || label == "LinearLayout" || label == "RelativeLayout") continue
            val clickable = if (el.isClickable) " [tap]" else ""
            sb.appendLine("$label$clickable")
        }

        val result = sb.toString().trim()
        if (result.isBlank()) {
            return ActionResult.Success("Screen has UI elements but no readable text. The app may use custom views.")
        }

        LuiLogger.i("A11Y", "Read screen ($bestPkg): ${bestElements.size} elements")
        return ActionResult.Success(result)
    }

    /**
     * Find a UI element by text/description and tap it.
     */
    fun findAndTap(context: Context, query: String): ActionResult {
        ensureEnabled(context)?.let { return it }

        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")

        return if (service.findAndTap(query)) {
            LuiLogger.i("A11Y", "Tapped: $query")
            ActionResult.Success("Tapped \"$query\".")
        } else {
            LuiLogger.w("A11Y", "Couldn't find: $query")
            ActionResult.Failure("Couldn't find \"$query\" on screen.")
        }
    }

    /**
     * Type text into the currently focused input field.
     */
    fun typeText(context: Context, text: String): ActionResult {
        ensureEnabled(context)?.let { return it }

        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")

        return if (service.typeText(text)) {
            ActionResult.Success("Typed \"${text.take(50)}\".")
        } else {
            ActionResult.Failure("Couldn't type — no focused text field found.")
        }
    }

    /**
     * Tap at specific coordinates.
     */
    fun tapAt(context: Context, x: Float, y: Float): ActionResult {
        ensureEnabled(context)?.let { return it }

        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")

        return if (service.tapAt(x, y)) {
            ActionResult.Success("Tapped at ($x, $y).")
        } else {
            ActionResult.Failure("Gesture failed.")
        }
    }

    /**
     * Scroll down on the current screen.
     */
    fun scrollDown(context: Context): ActionResult {
        ensureEnabled(context)?.let { return it }

        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")

        // Swipe from center-bottom to center-top
        val metrics = context.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.7f
        val endY = metrics.heightPixels * 0.3f

        return if (service.swipe(centerX, startY, centerX, endY)) {
            ActionResult.Success("Scrolled down.")
        } else {
            ActionResult.Failure("Scroll failed.")
        }
    }

    /**
     * Press the back button.
     */
    fun pressBack(context: Context): ActionResult {
        ensureEnabled(context)?.let { return it }

        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")

        return if (service.pressBack()) {
            ActionResult.Success("Pressed back.")
        } else {
            ActionResult.Failure("Back press failed.")
        }
    }

    /**
     * Switch back to LUI — brings LUI's main activity to the foreground.
     */
    fun openLui(context: Context): ActionResult {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } ?: return ActionResult.Failure("Couldn't find LUI.")
            context.startActivity(intent)
            ActionResult.Success("Switching back to LUI.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't switch: ${e.message}")
        }
    }

    /**
     * Press the home button.
     */
    fun pressHome(context: Context): ActionResult {
        ensureEnabled(context)?.let { return it }

        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")

        return if (service.pressHome()) {
            ActionResult.Success("Pressed home.")
        } else {
            ActionResult.Failure("Home press failed.")
        }
    }
}
