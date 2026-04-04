package com.lui.app.interceptor

import android.content.Context
import android.content.Intent
import android.os.Build
import com.lui.app.bridge.AgentRegistry
import com.lui.app.bridge.LuiBridgeService
import com.lui.app.data.ToolCall
import com.lui.app.helper.WallpaperHelper
import com.lui.app.interceptor.actions.*

object ActionExecutor {

    fun execute(context: Context, toolCall: ToolCall): ActionResult {
        return when (toolCall.tool) {
            "open_settings" -> SystemActions.openSettings(context)
            "toggle_flashlight" -> SystemActions.toggleFlashlight(context, toolCall.params["state"])
            "open_settings_wifi" -> SystemActions.openWifiSettings(context)
            "open_settings_bluetooth" -> SystemActions.openBluetoothSettings(context)
            "set_volume" -> SystemActions.setVolume(context, toolCall.params["direction"] ?: "up")
            "set_brightness" -> SystemActions.setBrightness(context, toolCall.params["level"] ?: "up")
            "toggle_dnd" -> SystemActions.toggleDnd(context)
            "toggle_rotation" -> SystemActions.toggleRotation(context)

            "set_alarm" -> AlarmActions.setAlarm(context,
                time = toolCall.params["time"] ?: "8:00",
                label = toolCall.params["label"] ?: "LUI Alarm")
            "set_timer" -> AlarmActions.setTimer(context,
                amount = toolCall.params["amount"] ?: "5",
                unit = toolCall.params["unit"] ?: "minutes")
            "dismiss_alarm" -> AlarmActions.dismissAlarm(context)
            "cancel_timer" -> AlarmActions.cancelTimer(context)

            "open_app" -> AppLauncher.openApp(context, name = toolCall.params["name"] ?: "")
            "make_call" -> AppLauncher.makeCall(context, target = toolCall.params["target"] ?: "")

            "send_sms" -> SmsActions.sendSms(context,
                number = toolCall.params["number"] ?: "",
                message = toolCall.params["message"] ?: "")

            "search_contact" -> ContactActions.searchContact(context, query = toolCall.params["query"] ?: "")
            "create_contact" -> ContactActions.createContact(context,
                name = toolCall.params["name"] ?: "",
                number = toolCall.params["number"] ?: "")

            "create_event" -> CalendarActions.createEvent(context,
                title = toolCall.params["title"] ?: "Event",
                dateStr = toolCall.params["date"] ?: "today",
                timeStr = toolCall.params["time"] ?: "9:00am")

            "play_pause" -> MediaActions.playPause(context)
            "next_track" -> MediaActions.nextTrack(context)
            "previous_track" -> MediaActions.previousTrack(context)
            "set_ringer" -> MediaActions.setRingerMode(context, mode = toolCall.params["mode"] ?: "normal")
            "battery" -> MediaActions.getBattery(context)
            "share_text" -> MediaActions.shareText(context, text = toolCall.params["text"] ?: "")

            "get_time" -> DeviceInfoActions.getTime(context)
            "get_date" -> DeviceInfoActions.getDate(context)
            "device_info" -> DeviceInfoActions.getDeviceInfo(context)

            "navigate" -> NavigationActions.navigate(context, destination = toolCall.params["destination"] ?: "")
            "search_map" -> NavigationActions.searchMap(context, query = toolCall.params["query"] ?: "")
            "open_app_search" -> AppLauncher.openAppWithQuery(context,
                app = toolCall.params["app"] ?: "",
                query = toolCall.params["query"] ?: "")

            "read_notifications" -> NotificationActions.readNotifications(context)
            "clear_notifications" -> NotificationActions.clearNotifications(context)
            "get_digest" -> NotificationActions.getDigest(context)
            "clear_digest" -> NotificationActions.clearDigest(context)
            "get_2fa_code" -> NotificationActions.get2faCode(context)
            "config_triage" -> NotificationActions.configTriage(
                app = toolCall.params["app"] ?: "",
                bucket = toolCall.params["bucket"] ?: "urgent")

            "read_screen" -> ScreenActions.readScreen(context)
            "find_and_tap" -> ScreenActions.findAndTap(context, query = toolCall.params["query"] ?: "")
            "type_text" -> ScreenActions.typeText(context, text = toolCall.params["text"] ?: "")
            "scroll_down" -> ScreenActions.scrollDown(context)
            "press_back" -> ScreenActions.pressBack(context)
            "press_home" -> ScreenActions.pressHome(context)
            "open_lui" -> ScreenActions.openLui(context)

            "take_photo" -> VisionActions.takePhoto(context)
            "pick_image" -> ActionResult.Success("__PICK_IMAGE__")
            "analyze_image" -> {
                if (VisionActions.lastCapturedImage != null) {
                    ActionResult.Success("Photo is attached to this message. Analyze it and tell the user what you see.")
                } else {
                    ActionResult.Failure("No photo captured yet. Use take_photo first.")
                }
            }

            "lock_screen" -> SystemActions.lockScreen(context)
            "take_screenshot" -> SystemActions.takeScreenshot(context)
            "split_screen" -> SystemActions.splitScreen(context)
            "set_screen_timeout" -> SystemActions.setScreenTimeout(context, duration = toolCall.params["duration"] ?: "1m")
            "keep_screen_on" -> SystemActions.keepScreenOn(context, enable = toolCall.params["enable"]?.toBoolean() ?: true)
            "bedtime_mode" -> {
                val enable = toolCall.params["enable"]?.toBoolean() ?: true
                if (enable) {
                    SystemActions.toggleDnd(context) // DND on
                    SystemActions.setBrightness(context, "10")
                    SystemActions.setScreenTimeout(context, "30s")
                    ActionResult.Success("Bedtime mode on: DND enabled, brightness low, screen timeout 30s.")
                } else {
                    SystemActions.toggleDnd(context) // DND off
                    SystemActions.setBrightness(context, "50")
                    SystemActions.setScreenTimeout(context, "2m")
                    ActionResult.Success("Bedtime mode off: DND disabled, brightness restored, timeout 2 minutes.")
                }
            }

            "get_steps" -> SensorActions.getSteps(context)
            "get_proximity" -> SensorActions.getProximity(context)
            "get_light" -> SensorActions.getLight(context)

            "storage_info" -> StorageActions.getStorageInfo(context)
            "wifi_info" -> StorageActions.getWifiInfo(context)
            "download_file" -> StorageActions.downloadFile(context,
                url = toolCall.params["url"] ?: "",
                filename = toolCall.params["filename"])
            "query_media" -> StorageActions.queryMedia(context,
                type = toolCall.params["type"] ?: "photos",
                dateFilter = toolCall.params["date"])
            "route_audio" -> StorageActions.routeAudio(context,
                target = toolCall.params["target"] ?: "speaker")

            "get_location" -> LocationActions.getLocation(context)
            "get_distance" -> LocationActions.getDistance(context, destination = toolCall.params["destination"] ?: "")

            "read_calendar" -> CalendarActions.readCalendar(context, dateStr = toolCall.params["date"] ?: "today")
            "read_sms" -> SmsActions.readSms(context, from = toolCall.params["from"])

            "now_playing" -> MediaActions.nowPlaying(context)
            "read_clipboard" -> MediaActions.readClipboard(context)
            "screen_time" -> MediaActions.getScreenTime(context, appName = toolCall.params["app"])

            "copy_clipboard" -> {
                val text = (context as? android.app.Application)?.let { /* can't access VM */ null }
                // Will be handled by ViewModel directly
                ActionResult.Failure("__COPY_LAST__")
            }

            "undo" -> ActionResult.Failure("__UNDO__")

            "start_bridge" -> {
                try {
                    val intent = Intent(context, LuiBridgeService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    // Never include token in tool results — it gets sent to cloud LLMs
                    ActionResult.Success("Bridge started on port ${com.lui.app.bridge.LuiBridgeServer.DEFAULT_PORT}. Check the notification for connection details.")
                } catch (e: Exception) {
                    ActionResult.Failure("Couldn't start bridge: ${e.message}")
                }
            }
            "stop_bridge" -> {
                try {
                    context.stopService(Intent(context, LuiBridgeService::class.java))
                    ActionResult.Success("Bridge stopped.")
                } catch (e: Exception) {
                    ActionResult.Failure("Couldn't stop bridge: ${e.message}")
                }
            }
            "start_passthrough" -> {
                ActionResult.Success("__PASSTHROUGH_START__${toolCall.params["agent"] ?: ""}")
            }
            "end_passthrough" -> {
                ActionResult.Success("__PASSTHROUGH_END__")
            }

            "list_agents" -> {
                val result = AgentRegistry.listAgents()
                val count = result.optInt("count", 0)
                if (count == 0) {
                    ActionResult.Success("No agents registered. Remote agents can register via the bridge.")
                } else {
                    val agents = result.optJSONArray("agents")
                    val sb = StringBuilder("$count agent(s) registered:\n")
                    for (i in 0 until (agents?.length() ?: 0)) {
                        val a = agents!!.getJSONObject(i)
                        sb.appendLine("- ${a.getString("name")}: ${a.optString("description", "")}")
                    }
                    ActionResult.Success(sb.toString().trim())
                }
            }
            "instruct_agent" -> {
                val agentName = toolCall.params["agent"] ?: ""
                val instruction = toolCall.params["instruction"] ?: ""
                if (agentName.isBlank() || instruction.isBlank()) {
                    ActionResult.Failure("Need both agent name and instruction.")
                } else {
                    val response = AgentRegistry.sendInstruction(agentName, instruction)
                    ActionResult.Success("$agentName: $response")
                }
            }

            "bridge_status" -> {
                val url = LuiBridgeService.getConnectionUrl(context)
                if (url != null) {
                    ActionResult.Success("Bridge running at $url. Token is in the notification — I won't say it here for security.")
                } else {
                    ActionResult.Success("Bridge is not running. Say 'start bridge' to enable.")
                }
            }

            "set_wallpaper" -> {
                WallpaperHelper.setLuiWallpaper(context)
                ActionResult.Success("LUI wallpaper set.")
            }

            else -> ActionResult.Failure("Unknown action: ${toolCall.tool}")
        }
    }
}
