package com.lui.app.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Central registry of all LUI tools with structured schemas.
 * Converts to each provider's native tool-use format (Gemini/Claude/OpenAI).
 */
object ToolRegistry {

    data class ToolDef(
        val name: String,
        val description: String,
        val parameters: List<ParamDef> = emptyList()
    )

    data class ParamDef(
        val name: String,
        val type: String = "string",
        val description: String,
        val required: Boolean = false,
        val enum: List<String>? = null
    )

    val tools: List<ToolDef> = listOf(
        // Hardware
        ToolDef("toggle_flashlight", "Toggle flashlight on, off, or auto-toggle",
            listOf(ParamDef("state", description = "on, off, or toggle", enum = listOf("on", "off", "toggle")))),
        ToolDef("set_volume", "Set media volume",
            listOf(ParamDef("direction", description = "Volume direction", required = true, enum = listOf("up", "down", "mute", "max")))),
        ToolDef("set_brightness", "Set screen brightness",
            listOf(ParamDef("level", description = "up, down, max, low, or 0-100 percentage", required = true))),
        ToolDef("toggle_dnd", "Toggle Do Not Disturb mode"),
        ToolDef("toggle_rotation", "Toggle auto-rotate"),
        ToolDef("set_ringer", "Set phone ringer mode",
            listOf(ParamDef("mode", description = "Ringer mode", required = true, enum = listOf("silent", "vibrate", "normal")))),

        // Alarms & Timers
        ToolDef("set_alarm", "Set an alarm",
            listOf(ParamDef("time", description = "Time like 7:30am, 19:30, 6pm", required = true),
                   ParamDef("label", description = "Alarm label"))),
        ToolDef("set_timer", "Set a countdown timer",
            listOf(ParamDef("amount", description = "Number of units", required = true),
                   ParamDef("unit", description = "Time unit", required = true, enum = listOf("seconds", "minutes", "hours")))),
        ToolDef("dismiss_alarm", "Dismiss the active alarm"),
        ToolDef("cancel_timer", "Cancel the active timer"),

        // Communication
        ToolDef("make_call", "Call a contact by name or phone number. Resolves names via contacts automatically.",
            listOf(ParamDef("target", description = "Contact name or phone number", required = true))),
        ToolDef("send_sms", "Send an SMS. Resolves contact names to numbers automatically.",
            listOf(ParamDef("number", description = "Contact name or phone number", required = true),
                   ParamDef("message", description = "Message text", required = true))),
        ToolDef("search_contact", "Search contacts by name",
            listOf(ParamDef("query", description = "Name to search for", required = true))),
        ToolDef("create_contact", "Create a new contact",
            listOf(ParamDef("name", description = "Contact name", required = true),
                   ParamDef("number", description = "Phone number", required = true))),
        ToolDef("read_sms", "Read recent SMS messages, optionally filtered by sender",
            listOf(ParamDef("from", description = "Contact name to filter by (optional)"))),

        // Calendar
        ToolDef("create_event", "Create a calendar event",
            listOf(ParamDef("title", description = "Event title", required = true),
                   ParamDef("date", description = "Date: today, tomorrow, next Monday, or MM/DD"),
                   ParamDef("time", description = "Time like 3pm, 14:30"))),
        ToolDef("read_calendar", "Read calendar events for a given day",
            listOf(ParamDef("date", description = "Date: today, tomorrow, next Monday"))),

        // Media
        ToolDef("play_pause", "Toggle media playback (play/pause)"),
        ToolDef("next_track", "Skip to the next track"),
        ToolDef("previous_track", "Go to the previous track"),
        ToolDef("now_playing", "Get info about the currently playing song/media"),

        // Apps & Deep Links
        ToolDef("open_app", "Open an app by name",
            listOf(ParamDef("name", description = "App name", required = true))),
        ToolDef("open_app_search", "Deep-link search inside an app (Spotify, YouTube, Netflix, Chrome, Amazon, Reddit, etc.)",
            listOf(ParamDef("app", description = "App name", required = true),
                   ParamDef("query", description = "Search query", required = true))),

        // Navigation & Location
        ToolDef("navigate", "Open turn-by-turn navigation to a destination in Google Maps",
            listOf(ParamDef("destination", description = "Address or place name", required = true))),
        ToolDef("search_map", "Search for a place on the map",
            listOf(ParamDef("query", description = "Place or query to search", required = true))),
        ToolDef("get_location", "Get the user's current GPS location with address"),
        ToolDef("get_distance", "Calculate distance and estimated drive time from user's current location to a destination. Automatically gets location — no need to call get_location first.",
            listOf(ParamDef("destination", description = "Destination address or place name", required = true))),

        // Device Info
        ToolDef("get_time", "Get the current time"),
        ToolDef("get_date", "Get the current date and day of week"),
        ToolDef("device_info", "Get device info: time, date, battery, network, device model"),
        ToolDef("battery", "Get battery level and charging status"),
        ToolDef("screen_time", "Get app usage/screen time stats for today",
            listOf(ParamDef("app", description = "App name to check (optional, omit for top 5)"))),

        // Notifications
        ToolDef("read_notifications", "Read recent notifications"),
        ToolDef("clear_notifications", "Dismiss all notifications"),

        // Clipboard & Sharing
        ToolDef("copy_clipboard", "Copy the last action result to clipboard"),
        ToolDef("read_clipboard", "Read what's currently on the clipboard"),
        ToolDef("share_text", "Share text via Android share sheet",
            listOf(ParamDef("text", description = "Text to share", required = true))),

        // Settings
        ToolDef("open_settings", "Open Android Settings"),
        ToolDef("open_settings_wifi", "Open Wi-Fi settings"),
        ToolDef("open_settings_bluetooth", "Open Bluetooth settings"),
        ToolDef("set_wallpaper", "Set the LUI wallpaper"),

        // Screen Control (Accessibility — Tier 4)
        ToolDef("read_screen", "Read the current screen content of whatever app is open. Returns visible text, buttons, and interactive elements."),
        ToolDef("find_and_tap", "Find a UI element on screen by text or label and tap it. Use this to interact with any app — e.g., tap a Play button, tap a search result, tap a menu item.",
            listOf(ParamDef("query", description = "Text or label of the element to tap", required = true))),
        ToolDef("type_text", "Type text into the currently focused input field on screen",
            listOf(ParamDef("text", description = "Text to type", required = true))),
        ToolDef("scroll_down", "Scroll down on the current screen"),
        ToolDef("press_back", "Press the Android back button"),
        ToolDef("press_home", "Press the Android home button"),
        ToolDef("open_lui", "Switch back to LUI chat interface. Use when the user says 'switch back', 'go back to chat', 'come back', 'open LUI', or similar."),

        // The Bouncer (Notification Triage)
        ToolDef("get_digest", "Get the Evening Digest — batched noise notifications that were silently collected"),
        ToolDef("clear_digest", "Clear the notification digest"),
        ToolDef("get_2fa_code", "Get the most recently captured 2FA/verification code from notifications"),
        ToolDef("config_triage", "Configure which apps are urgent (pass through) or noise (batched to digest)",
            listOf(ParamDef("app", description = "App package name or common name", required = true),
                   ParamDef("bucket", description = "urgent or noise", required = true, enum = listOf("urgent", "noise")))),

        // System Actions (Accessibility)
        ToolDef("lock_screen", "Lock the phone immediately"),
        ToolDef("take_screenshot", "Take a screenshot of whatever is currently on screen"),
        ToolDef("split_screen", "Toggle split screen mode"),

        // Screen & Display
        ToolDef("set_screen_timeout", "Set how long before screen auto-locks",
            listOf(ParamDef("duration", description = "15s, 30s, 1m, 2m, 5m, 10m, 30m, or never", required = true))),
        ToolDef("keep_screen_on", "Keep the screen on (prevent auto-lock) or release it",
            listOf(ParamDef("enable", description = "true to keep on, false to release", required = true))),
        ToolDef("bedtime_mode", "Enable bedtime mode: DND on, brightness low, short screen timeout. Or disable to restore.",
            listOf(ParamDef("enable", description = "true to enable, false to disable", required = true))),

        // Sensors
        ToolDef("get_steps", "Get step count from the pedometer sensor"),
        ToolDef("get_proximity", "Check if something is near the phone sensor (face-down, in pocket)"),
        ToolDef("get_light", "Read ambient light level in lux"),

        // Storage & System Info
        ToolDef("storage_info", "Get device storage and RAM usage"),
        ToolDef("wifi_info", "Get current Wi-Fi connection details (SSID, signal, speed)"),
        ToolDef("download_file", "Download a file from a URL silently to Downloads folder",
            listOf(ParamDef("url", description = "URL to download", required = true),
                   ParamDef("filename", description = "Optional filename"))),

        // Media Library
        ToolDef("query_media", "Query photos, videos, or music on the device",
            listOf(ParamDef("type", description = "photos, videos, or music", required = true),
                   ParamDef("date", description = "today, yesterday, this week, this month"))),

        // Audio
        ToolDef("route_audio", "Route audio output to speaker, bluetooth, or earpiece",
            listOf(ParamDef("target", description = "speaker, bluetooth, or earpiece", required = true))),

        // BYOS Bridge
        ToolDef("start_bridge", "Start the BYOS WebSocket bridge so remote agents can connect and use LUI's tools"),
        ToolDef("stop_bridge", "Stop the BYOS WebSocket bridge"),
        ToolDef("bridge_status", "Get the bridge status: running, URL, connected agents, auth token"),
        ToolDef("list_agents", "List all remote agents currently connected to the bridge. Call this to see what agents are available before sending instructions."),
        ToolDef("instruct_agent", "Send an instruction to a connected remote agent and get its response. The agent name is whatever the user says — just pass it through. Do NOT validate the name yourself, the tool will check.",
            listOf(ParamDef("agent", description = "Agent name", required = true),
                   ParamDef("instruction", description = "What to tell the agent to do", required = true))),

        // Meta
        ToolDef("undo", "Undo/reverse the last action where possible")
    )

    // ── Gemini format ──

    fun toGeminiTools(): JSONArray {
        val declarations = JSONArray()
        for (tool in tools) {
            val func = JSONObject()
            func.put("name", tool.name)
            func.put("description", tool.description)

            val params = JSONObject()
            params.put("type", "object")
            val props = JSONObject()
            val required = JSONArray()
            for (p in tool.parameters) {
                val prop = JSONObject()
                prop.put("type", p.type)
                prop.put("description", p.description)
                p.enum?.let { prop.put("enum", JSONArray(it)) }
                props.put(p.name, prop)
                if (p.required) required.put(p.name)
            }
            params.put("properties", props)
            if (required.length() > 0) params.put("required", required)
            func.put("parameters", params)

            declarations.put(func)
        }
        val toolObj = JSONObject()
        toolObj.put("function_declarations", declarations)
        return JSONArray().put(toolObj)
    }

    // ── Claude format ──

    fun toClaudeTools(): JSONArray {
        val arr = JSONArray()
        for (tool in tools) {
            val obj = JSONObject()
            obj.put("name", tool.name)
            obj.put("description", tool.description)

            val schema = JSONObject()
            schema.put("type", "object")
            val props = JSONObject()
            val required = JSONArray()
            for (p in tool.parameters) {
                val prop = JSONObject()
                prop.put("type", p.type)
                prop.put("description", p.description)
                p.enum?.let { prop.put("enum", JSONArray(it)) }
                props.put(p.name, prop)
                if (p.required) required.put(p.name)
            }
            schema.put("properties", props)
            if (required.length() > 0) schema.put("required", required)
            obj.put("input_schema", schema)

            arr.put(obj)
        }
        return arr
    }

    // ── OpenAI format ──

    fun toOpenAITools(): JSONArray {
        val arr = JSONArray()
        for (tool in tools) {
            val func = JSONObject()
            func.put("name", tool.name)
            func.put("description", tool.description)

            val params = JSONObject()
            params.put("type", "object")
            val props = JSONObject()
            val required = JSONArray()
            for (p in tool.parameters) {
                val prop = JSONObject()
                prop.put("type", p.type)
                prop.put("description", p.description)
                p.enum?.let { prop.put("enum", JSONArray(it)) }
                props.put(p.name, prop)
                if (p.required) required.put(p.name)
            }
            params.put("properties", props)
            if (required.length() > 0) params.put("required", required)
            func.put("parameters", params)

            val toolObj = JSONObject()
            toolObj.put("type", "function")
            toolObj.put("function", func)
            arr.put(toolObj)
        }
        return arr
    }
}
