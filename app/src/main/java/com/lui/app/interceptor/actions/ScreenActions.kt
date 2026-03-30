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
     */
    fun readScreen(context: Context): ActionResult {
        ensureEnabled(context)?.let { return it }

        val service = LuiAccessibilityService.instance ?: return ActionResult.Failure("Accessibility service not connected.")
        val elements = service.readScreen()

        if (elements.isEmpty()) return ActionResult.Success("Screen appears empty or unreadable.")

        val sb = StringBuilder()
        for (el in elements.take(20)) {
            val label = el.text.ifBlank { el.description }.ifBlank { el.className }
            val clickable = if (el.isClickable) " [tap]" else ""
            sb.appendLine("$label$clickable")
        }

        LuiLogger.i("A11Y", "Read screen: ${elements.size} elements")
        return ActionResult.Success(sb.toString().trim())
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
