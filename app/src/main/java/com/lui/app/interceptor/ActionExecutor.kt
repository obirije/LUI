package com.lui.app.interceptor

import android.content.Context
import com.lui.app.data.ToolCall
import com.lui.app.helper.WallpaperHelper
import com.lui.app.interceptor.actions.ActionResult
import com.lui.app.interceptor.actions.AlarmActions
import com.lui.app.interceptor.actions.AppLauncher
import com.lui.app.interceptor.actions.SystemActions

/**
 * Routes a ToolCall to the appropriate action handler.
 */
object ActionExecutor {

    fun execute(context: Context, toolCall: ToolCall): ActionResult {
        return when (toolCall.tool) {
            "toggle_flashlight" -> SystemActions.toggleFlashlight(context)
            "open_settings_wifi" -> SystemActions.openWifiSettings(context)
            "open_settings_bluetooth" -> SystemActions.openBluetoothSettings(context)

            "set_alarm" -> AlarmActions.setAlarm(
                context,
                time = toolCall.params["time"] ?: "8:00",
                label = toolCall.params["label"] ?: "LUI Alarm"
            )

            "set_timer" -> AlarmActions.setTimer(
                context,
                amount = toolCall.params["amount"] ?: "5",
                unit = toolCall.params["unit"] ?: "minutes"
            )

            "open_app" -> AppLauncher.openApp(
                context,
                name = toolCall.params["name"] ?: ""
            )

            "make_call" -> AppLauncher.makeCall(
                context,
                target = toolCall.params["target"] ?: ""
            )

            "set_wallpaper" -> {
                WallpaperHelper.setLuiWallpaper(context)
                ActionResult.Success("LUI wallpaper set.")
            }

            else -> ActionResult.Failure("Unknown action: ${toolCall.tool}")
        }
    }
}
