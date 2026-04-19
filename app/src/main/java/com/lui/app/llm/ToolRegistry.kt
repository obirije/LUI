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
        ToolDef("read_screen", "Read the current screen as a numbered list of visible elements. Each line is `[N] label [tap|input|scroll|...]`. Use the index N with tap_by_index or scroll_by_index for unambiguous targeting."),
        ToolDef("tap_by_index", "Tap the element at the given index from the most recent read_screen. Indices reset every read — call read_screen first.",
            listOf(ParamDef("index", description = "Element index from the last read_screen", required = true))),
        ToolDef("scroll_by_index", "Scroll a specific scrollable container (one of the [scroll]-tagged elements from read_screen) in the given direction.",
            listOf(ParamDef("index", description = "Element index from the last read_screen", required = true),
                   ParamDef("direction", description = "forward or backward", required = true, enum = listOf("forward", "backward")))),
        ToolDef("find_and_tap", "Fallback: find a UI element by text, resource ID, or content description and tap it. Prefer tap_by_index after read_screen — this only succeeds when the query is unambiguous.",
            listOf(ParamDef("query", description = "Text, resource ID (com.app:id/foo), or content description", required = true))),
        ToolDef("type_text", "Type text into the currently focused input field on screen. Preserves caret if a selection is active.",
            listOf(ParamDef("text", description = "Text to type", required = true))),
        ToolDef("scroll_down", "Scroll the screen down (gesture-based — last resort if no scrollable was identified by read_screen)."),
        ToolDef("open_app_and_read", "Open an app and return its first indexed screen in one call. Replaces open_app + read_screen.",
            listOf(ParamDef("name", description = "App name (e.g. WhatsApp, Settings)", required = true))),
        ToolDef("do_steps", "Run a sequence of UI actions atomically. Each step waits for the screen to settle before the next runs. Aborts on first failure. Returns per-step status + final screen.",
            listOf(ParamDef("steps", description = "JSON array. Each item must have an `action` field set to EXACTLY one of: tap, type, scroll, back, home, wait. NO other action names are valid (use open_app_and_read separately, not as a step). Schemas: {action:'tap', index:N}, {action:'type', text:'...'}, {action:'scroll', index:N, direction:'forward'|'backward'}, {action:'back'}, {action:'home'}, {action:'wait', ms:300}. Example: '[{\"action\":\"tap\",\"index\":7},{\"action\":\"type\",\"text\":\"hello\"},{\"action\":\"tap\",\"index\":3}]'", required = true))),
        ToolDef("press_back", "Press the Android back button"),
        ToolDef("press_home", "Press the Android home button"),
        ToolDef("open_lui", "Switch back to LUI chat interface. Use when the user says 'switch back', 'go back to chat', 'come back', 'open LUI', or similar."),

        // The Bouncer (Notification Triage)
        ToolDef("get_digest", "Get the Evening Digest — batched noise notifications that were silently collected"),
        ToolDef("clear_digest", "Clear the notification digest"),
        ToolDef("get_notification_history", "Query historic notifications from the last N hours, optionally filtered by app name or package",
            listOf(ParamDef("hours", description = "How many hours back to search (default 24)"),
                   ParamDef("app", description = "Optional app name or package substring to filter by"))),
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

        // Wellness (stress relief)
        ToolDef("play_relaxing_sound", """Play a looping ambient sound or calming music to help the user relax.

Available sounds:
- Ambient nature: rain, thunder, ocean, fire, wind, forest, crickets
- Noise: white_noise (crisp, focus), brown_noise (deep, sleep)
- Music: piano (Clair de Lune by Debussy — emotional calm), meditation (singing bowl bell — mindfulness)

Guidance for picking:
- Night / sleep: crickets, brown_noise, rain, thunder
- Morning / waking up: forest, piano
- Afternoon work / focus: white_noise, brown_noise, ocean
- Evening wind-down: fire, rain, piano
- Acute stress / anxiety: piano, meditation, rain (soft rhythm is grounding)
- Just feeling overstimulated: ocean or brown_noise (masks noise)
If unsure, rain is the safe universal pick.""",
            listOf(ParamDef("type", description = "one of: rain, thunder, ocean, fire, wind, forest, white_noise, brown_noise, crickets, piano, meditation", required = true))),
        ToolDef("stop_relaxing_sound", "Stop any ambient sound that is currently playing"),
        ToolDef("list_relaxing_sounds", "List which ambient sounds are bundled and available on this device"),
        ToolDef("start_wellness_mode", "Enter wellness mode: plays a calming sound, enables Do Not Disturb, dims the screen. Use when the user is stressed or needs to wind down. If you don't specify a sound, LUI auto-picks based on time of day and current stress level.",
            listOf(ParamDef("sound", description = "optional sound type (same list as play_relaxing_sound). Omit to let LUI auto-pick based on time and stress."))),
        ToolDef("stop_wellness_mode", "Exit wellness mode: stops ambient sound, restores normal notifications and brightness"),

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

        // Vision — ONLY call when user EXPLICITLY asks for photo/image
        ToolDef("take_photo", "Take a photo with the device camera. ONLY use when user explicitly says 'take a photo', 'snap a picture', 'what do you see', 'look around'. NEVER call for greetings or general chat."),
        ToolDef("pick_image", "Open gallery for user to select an image. ONLY when user says 'pick image', 'choose photo', 'upload image', 'from gallery'."),
        ToolDef("analyze_image", "Describe what is in the last photo taken or selected. The image is automatically included.",
            listOf(ParamDef("question", description = "What to look for in the image"))),

        // BYOS Bridge
        ToolDef("start_bridge", "Start the BYOS WebSocket bridge so remote agents can connect and use LUI's tools"),
        ToolDef("stop_bridge", "Stop the BYOS WebSocket bridge"),
        ToolDef("bridge_status", "Get the bridge status: running, URL, connected agents, auth token"),
        ToolDef("list_agents", "List all remote agents currently connected to the bridge. Call this to see what agents are available before sending instructions."),
        ToolDef("start_passthrough", "Enter passthrough mode with an agent. ALL subsequent messages from the user go directly to the agent until they say 'LUI come back'. Use when the user says 'patch me to X', 'connect me to X', 'talk to X', 'switch to X'.",
            listOf(ParamDef("agent", description = "Agent name to connect to", required = true))),
        ToolDef("end_passthrough", "Exit passthrough mode and return to LUI. Use when the user says 'LUI come back', 'disconnect', 'exit', 'back to LUI'."),
        ToolDef("instruct_agent", "Send a one-off instruction to a connected remote agent and get its response. The agent name is whatever the user says — just pass it through. Do NOT validate the name yourself, the tool will check.",
            listOf(ParamDef("agent", description = "Agent name", required = true),
                   ParamDef("instruction", description = "What to tell the agent to do", required = true))),

        // Web
        ToolDef("search_web", "Search the web and return top results. Use this first to find relevant URLs, then use browse_url to read a specific page.",
            listOf(ParamDef("query", description = "Search query", required = true))),
        ToolDef("browse_url", "Fetch a URL and return clean readable text. Works with any website. You can construct search URLs directly — e.g. browse_url(url='https://en.wikipedia.org/wiki/Liverpool_F.C.') or browse_url(url='https://www.booking.com/searchresults.html?ss=Rio+de+Janeiro&checkin=2027-06-05'). Use after search_web to read a result, or build a URL from the user's intent.",
            listOf(ParamDef("url", description = "URL to fetch — can be a direct page or a constructed search URL", required = true))),

        // Ambient context
        ToolDef("ambient_context", "Get full device context: battery, charging, network, Bluetooth, audio, brightness"),
        ToolDef("bluetooth_devices", "List paired Bluetooth devices"),
        ToolDef("network_state", "Get network type, speed, metered status, VPN"),

        // Triggers — geofence and scheduled actions
        ToolDef("create_geofence", "Set a location-based trigger. When the user enters or exits a place, execute a tool. You MUST resolve the place to GPS coordinates first using get_location or by asking the user.",
            listOf(ParamDef("place", description = "Name of the place", required = true),
                   ParamDef("latitude", description = "GPS latitude", required = true),
                   ParamDef("longitude", description = "GPS longitude", required = true),
                   ParamDef("trigger", description = "enter or exit", required = false),
                   ParamDef("action", description = "Tool name to execute when triggered", required = true),
                   ParamDef("action_params", description = "JSON params for the tool", required = false),
                   ParamDef("radius", description = "Radius in meters (default 200)", required = false))),
        ToolDef("schedule_action", "Schedule a tool to run at a specific time or after a delay. Examples: '5 minutes', '30 seconds', '6:32pm', '18:30'.",
            listOf(ParamDef("time", description = "When to fire: relative ('5 minutes') or absolute ('6:32pm')", required = true),
                   ParamDef("action", description = "Tool name to execute", required = true),
                   ParamDef("action_params", description = "JSON params for the tool", required = false),
                   ParamDef("recurring", description = "true for daily repeat, false for one-time", required = false))),
        ToolDef("list_triggers", "List all active geofence and scheduled triggers"),
        ToolDef("delete_trigger", "Delete a trigger by its ID number or name",
            listOf(ParamDef("target", description = "Trigger ID or name to delete", required = true))),

        // Health ring
        ToolDef("get_heart_rate", "Measure heart rate from the connected health ring. Takes a few seconds."),
        ToolDef("get_spo2", "Get blood oxygen (SpO2) level from the health ring."),
        ToolDef("get_sleep", "Get last night's sleep data — duration and stages (deep, light, REM, awake)."),
        ToolDef("get_activity", "Get step count and calories from the health ring."),
        ToolDef("get_stress", "Get stress level from the health ring (0-100 scale)."),
        ToolDef("get_hrv", "Get heart rate variability (HRV) from the health ring in milliseconds."),
        ToolDef("get_temperature", "Get body temperature from the health ring (R09+ models only)."),
        ToolDef("get_health_summary", "Get a full health summary — heart rate, SpO2, stress, HRV, temperature, steps, ring battery. Takes a few seconds."),
        ToolDef("ring_battery", "Get the health ring's battery level."),
        ToolDef("ring_status", "Get health ring connection status, battery, and last reading."),
        ToolDef("ring_capabilities", "List all available health vitals and commands the ring can provide."),
        ToolDef("find_ring", "Make the health ring vibrate to help find it."),
        ToolDef("get_health_trend", "Get historical trend for a health metric over a time period. Shows average, range, and latest reading.",
            listOf(ParamDef("metric", description = "Metric to check: heart_rate, spo2, stress, hrv, temperature, steps", required = true),
                   ParamDef("hours", description = "How many hours back to look (default 24)", required = false))),

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
