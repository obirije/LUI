package com.lui.app.interceptor

import com.lui.app.data.ToolCall
import org.json.JSONObject

object Interceptor {

    private val KNOWN_TOOLS = setOf(
        "toggle_flashlight", "set_alarm", "set_timer", "open_app", "make_call",
        "open_settings_wifi", "open_settings_bluetooth", "set_wallpaper",
        "set_volume", "set_brightness", "toggle_dnd", "toggle_rotation",
        "send_sms", "search_contact", "create_contact", "create_event",
        "play_pause", "next_track", "previous_track", "set_ringer",
        "battery", "share_text", "open_settings",
        "dismiss_alarm", "cancel_timer", "get_time", "get_date", "device_info",
        "navigate", "search_map", "open_app_search", "copy_clipboard",
        "read_notifications", "clear_notifications", "undo",
        "get_location", "get_distance", "read_calendar", "read_sms",
        "now_playing", "read_clipboard", "screen_time",
        "read_screen", "find_and_tap", "type_text", "scroll_down", "press_back", "press_home", "open_lui",
        "take_photo", "pick_image", "analyze_image",
        "lock_screen", "take_screenshot", "split_screen", "set_screen_timeout", "keep_screen_on", "bedtime_mode",
        "start_bridge", "stop_bridge", "bridge_status",
        "get_steps", "get_proximity", "get_light",
        "storage_info", "wifi_info", "download_file", "query_media", "route_audio",
        "get_digest", "clear_digest", "get_2fa_code", "config_triage",
        "search_web", "browse_url", "ambient_context", "bluetooth_devices", "network_state",
        "create_geofence", "schedule_action", "list_triggers", "delete_trigger",
        "get_heart_rate", "get_spo2", "get_sleep", "get_activity", "get_stress", "get_hrv", "get_temperature",
        "get_health_summary", "get_health_trend", "ring_battery", "ring_status", "ring_capabilities", "find_ring",
        "list_agents", "instruct_agent", "start_passthrough", "end_passthrough"
    )

    /** Parse user input — tries JSON extraction then keyword matching */
    fun parse(input: String): ToolCall? {
        return parseJson(input) ?: parseKeywords(input)
    }

    /** Parse LLM output — only JSON extraction, no keyword matching.
     *  LLM responses naturally contain words like "time", "call", "alarm"
     *  which would false-positive on keyword patterns. */
    fun parseLlmOutput(input: String): ToolCall? {
        return parseJson(input)
    }

    private fun parseJson(input: String): ToolCall? {
        try {
            val jsonStart = input.indexOf('{')
            val jsonEnd = input.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd <= jsonStart) return null

            // Strip common LLM wrapping: ```json ... ```, markdown code fences
            val cleaned = input.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val cleanedStart = cleaned.indexOf('{')
            val cleanedEnd = cleaned.lastIndexOf('}')

            // If there's significant non-tool text before the JSON, this is conversation
            // that happens to mention a tool — not an actual tool call.
            val textBefore = if (cleanedStart >= 0) cleaned.substring(0, cleanedStart).trim() else input.substring(0, jsonStart).trim()
            com.lui.app.helper.LuiLogger.d("JSON_PARSE", "textBefore(${textBefore.length}): '${textBefore.take(60)}' | json at $jsonStart")
            // Allow short preambles like "Sure." or "Here:" but block full sentences
            if (textBefore.length > 40) return null

            // Use cleaned positions if available
            val actualStart = if (cleanedStart >= 0) cleanedStart else jsonStart
            val actualEnd = if (cleanedEnd >= 0) cleanedEnd else jsonEnd
            val jsonStr = if (cleanedStart >= 0) cleaned.substring(actualStart, actualEnd + 1)
                          else input.substring(jsonStart, jsonEnd + 1)

            val json = JSONObject(jsonStr)
            val tool = json.optString("tool", "").lowercase()
            if (tool.isBlank() || tool !in KNOWN_TOOLS) return null
            val params = mutableMapOf<String, String>()
            json.optJSONObject("params")?.let { p -> for (k in p.keys()) params[k] = p.getString(k) }
            return ToolCall(tool, params)
        } catch (e: Exception) { return null }
    }

    private fun parseKeywords(input: String): ToolCall? {
        val lower = input.lowercase().trim()

        // ── Time & date queries (instant, no LLM needed) ──

        if (lower.matches(Regex(".*(?:what(?:'s| is)\\s+the\\s+)?(?:time|clock).*")) &&
            (lower.contains("what") || lower.contains("current")))
            return ToolCall("get_time")

        if (lower.matches(Regex(".*(?:what(?:'s| is)\\s+(?:the\\s+)?(?:date|day|today)).*")) ||
            lower.matches(Regex(".*(?:what\\s+day\\s+is\\s+it).*")))
            return ToolCall("get_date")

        if (lower.matches(Regex(".*(?:device|phone|system)\\s*(?:info|status|details).*")))
            return ToolCall("device_info")

        // ── Flashlight (with on/off/toggle distinction) ──

        if (lower.contains("flashlight") || lower.contains("torch") || lower.contains("flash light")) {
            val state = when {
                lower.contains("turn on") || lower.contains("switch on") || lower.contains("enable") -> "on"
                lower.contains("turn off") || lower.contains("switch off") || lower.contains("disable") -> "off"
                else -> "toggle"
            }
            return ToolCall("toggle_flashlight", mapOf("state" to state))
        }

        // ── Alarm ──

        Regex(".*(?:set|create|make).*(?:alarm|wake).*?(\\d{1,2}[:\\.]?\\d{0,2}\\s*(?:am|pm)?)", RegexOption.IGNORE_CASE)
            .find(lower)?.let { return ToolCall("set_alarm", mapOf("time" to it.groupValues[1].trim())) }

        // Cancel/dismiss alarm
        if (lower.matches(Regex(".*(?:cancel|dismiss|stop|turn off|delete|remove).*(?:alarm).*")))
            return ToolCall("dismiss_alarm")

        // ── Timer ──

        Regex(".*(?:set|start|create).*timer.*?(\\d+)\\s*(min|minute|sec|second|hour|hr)", RegexOption.IGNORE_CASE)
            .find(lower)?.let { return ToolCall("set_timer", mapOf("amount" to it.groupValues[1], "unit" to it.groupValues[2])) }

        // Cancel/stop timer
        if (lower.matches(Regex(".*(?:cancel|stop|dismiss|end).*(?:timer).*")))
            return ToolCall("cancel_timer")

        // ── Volume ──

        if (lower.matches(Regex(".*(?:volume|sound).*(?:up|increase|raise|louder).*")))
            return ToolCall("set_volume", mapOf("direction" to "up"))
        if (lower.matches(Regex(".*(?:volume|sound).*(?:down|decrease|lower|quieter).*")))
            return ToolCall("set_volume", mapOf("direction" to "down"))
        if (lower.matches(Regex(".*(?:mute|unmute).*(?:volume|sound|phone|music)?.*")) && !lower.contains("notification"))
            return ToolCall("set_volume", mapOf("direction" to "mute"))
        if (lower.matches(Regex(".*(?:max|full).*volume.*")) || lower.matches(Regex(".*volume.*(?:max|full).*")))
            return ToolCall("set_volume", mapOf("direction" to "max"))

        // ── Brightness ──

        if (lower.matches(Regex(".*brightness.*(?:up|increase|higher|brighter).*")) || lower.matches(Regex(".*(?:brighter|brighten).*")))
            return ToolCall("set_brightness", mapOf("level" to "up"))
        if (lower.matches(Regex(".*brightness.*(?:down|decrease|lower|dimmer|dim).*")) || lower.matches(Regex(".*(?:dimmer|dim\\b).*screen.*")))
            return ToolCall("set_brightness", mapOf("level" to "down"))
        if (lower.matches(Regex(".*brightness.*(?:max|full).*")))
            return ToolCall("set_brightness", mapOf("level" to "max"))
        if (lower.matches(Regex(".*brightness.*(?:min|low|lowest).*")))
            return ToolCall("set_brightness", mapOf("level" to "low"))
        Regex(".*brightness.*?(\\d+)\\s*%?").find(lower)?.let {
            return ToolCall("set_brightness", mapOf("level" to it.groupValues[1]))
        }

        // ── Do Not Disturb (tightened — requires "dnd" or "do not disturb" or "quiet mode") ──

        if (lower.matches(Regex(".*(?:do\\s+not\\s+disturb|\\bdnd\\b|quiet\\s+mode).*")))
            return ToolCall("toggle_dnd")

        // ── Rotation ──

        if (lower.matches(Regex(".*(?:auto.?rotate|screen.?rotation|rotation).*")))
            return ToolCall("toggle_rotation")

        // ── SMS (two patterns: with separator and without) ──

        // Pattern 1: "send sms to 555-1234 saying hello" / "text john message hey"
        Regex("(?:send\\s+(?:sms|text|message)|text)\\s+(?:to\\s+)?(.+?)\\s+(?:saying|message|that|:)\\s+(.+)", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("send_sms", mapOf("number" to it.groupValues[1].trim(), "message" to it.groupValues[2].trim()))
        }
        // Pattern 2: "text mom I'm running late" (first word after target-ish name is the message)
        Regex("(?:text|sms)\\s+(?:to\\s+)?([\\w]+)\\s+(.{3,})", RegexOption.IGNORE_CASE).find(lower)?.let {
            val target = it.groupValues[1].trim()
            // Avoid matching "text message" as target="message"
            if (target !in listOf("message", "messages", "settings")) {
                return ToolCall("send_sms", mapOf("number" to target, "message" to it.groupValues[2].trim()))
            }
        }

        // ── Contact search ──

        Regex("(?:find|search|look\\s*up|lookup)\\s+(?:contact|contacts|number)\\s+(?:for\\s+)?(.+)", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("search_contact", mapOf("query" to it.groupValues[1].trim()))
        }

        // ── Create contact (lowered min digits from 7 to 3) ──

        Regex("(?:create|add|new|save)\\s+contact\\s+(.+?)\\s+(?:number\\s+)?(\\+?[\\d\\s-]{3,})", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("create_contact", mapOf("name" to it.groupValues[1].trim(), "number" to it.groupValues[2].trim()))
        }

        // ── Calendar (improved: handles more natural phrasing) ──

        // Pattern 1: with explicit time — "schedule meeting tomorrow at 3pm"
        Regex("(?:create\\s+event|schedule|add\\s+(?:an?\\s+)?event|remind\\s+me(?:\\s+to)?)\\s+(.+?)\\s+(?:on\\s+)?(today|tomorrow|next\\s+\\w+|\\d{1,2}/\\d{1,2})?\\s*(?:at\\s+)?(\\d{1,2}[:\\.]?\\d{0,2}\\s*(?:am|pm))", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("create_event", mapOf(
                "title" to it.groupValues[1].trim(),
                "date" to (it.groupValues[2].ifBlank { "today" }),
                "time" to it.groupValues[3].trim()
            ))
        }
        // Pattern 2: no time — "schedule dentist tomorrow" (defaults to 9am)
        Regex("(?:create\\s+event|schedule|add\\s+(?:an?\\s+)?event)\\s+(.+?)\\s+(?:on\\s+|for\\s+)?(today|tomorrow|next\\s+\\w+|\\d{1,2}/\\d{1,2})\\s*$", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("create_event", mapOf(
                "title" to it.groupValues[1].trim(),
                "date" to it.groupValues[2].trim(),
                "time" to "9:00am"
            ))
        }

        // ── Media controls (tightened — must reference music/song/track/media) ──

        if (lower.matches(Regex(".*(?:play|pause|resume)\\s+(?:music|song|track|audio|media).*")) ||
            lower.matches(Regex("^(?:play|pause|resume)\\s*$")))
            return ToolCall("play_pause")
        if (lower.matches(Regex(".*(?:next|skip)\\s+(?:song|track|music).*")))
            return ToolCall("next_track")
        if (lower.matches(Regex(".*(?:previous|prev|back|last)\\s+(?:song|track|music).*")))
            return ToolCall("previous_track")

        // ── Ringer mode (tightened to avoid DND collision) ──

        if (lower.matches(Regex(".*(?:silent|silence)\\s+(?:mode|ringer|ring).*")) ||
            lower == "silent mode" || lower == "silence phone")
            return ToolCall("set_ringer", mapOf("mode" to "silent"))
        if (lower.matches(Regex(".*vibrate\\s*(?:mode|only)?.*")))
            return ToolCall("set_ringer", mapOf("mode" to "vibrate"))
        if (lower.matches(Regex(".*(?:ring|normal)\\s+mode.*")))
            return ToolCall("set_ringer", mapOf("mode" to "normal"))

        // ── Battery (tightened — must be a question or explicit status request) ──

        if (lower.matches(Regex(".*(?:battery|charge)\\s*(?:level|status|percentage|life|left|%|\\?).*")) ||
            lower.matches(Regex(".*(?:how\\s+much)\\s+(?:battery|charge).*")) ||
            lower.matches(Regex(".*(?:what(?:'s| is))\\s+(?:my\\s+)?(?:battery|charge).*")) ||
            lower == "battery" || lower == "battery?")
            return ToolCall("battery")

        // ── Clipboard / copy & share last result ──

        if (lower.matches(Regex("^(?:copy|copy that|copy it|copy this|copy the result)$")))
            return ToolCall("copy_clipboard")
        if (lower.matches(Regex("^(?:share that|share it|share this|share the result|share last)$")))
            return ToolCall("share_text", mapOf("text" to "__LAST_RESULT__"))

        Regex("(?:share|send)\\s+(?:text\\s+)?[\"'](.+?)[\"']", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("share_text", mapOf("text" to it.groupValues[1]))
        }

        // ── Undo ──

        if (lower.matches(Regex("^(?:undo|undo that|undo it|cancel that|take that back|revert)$")))
            return ToolCall("undo")

        // ── Location ──

        if (lower.matches(Regex(".*(?:where\\s+am\\s+i|my\\s+location|current\\s+location).*")))
            return ToolCall("get_location")
        Regex("(?:how\\s+far|distance|how\\s+long).*?(?:to|from|is)\\s+(.+)", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("get_distance", mapOf("destination" to it.groupValues[1].trim()))
        }

        // ── Calendar reading ──

        if (lower.matches(Regex(".*(?:what(?:'s| is)\\s+(?:on\\s+)?my\\s+(?:calendar|schedule|agenda)).*")) ||
            lower.matches(Regex(".*(?:any|do i have)\\s+(?:events?|meetings?|appointments?).*(?:today|tomorrow)?.*")))  {
            val date = when {
                lower.contains("tomorrow") -> "tomorrow"
                lower.contains("today") || !lower.matches(Regex(".*(?:next\\s+\\w+).*")) -> "today"
                else -> Regex("next\\s+\\w+").find(lower)?.value ?: "today"
            }
            return ToolCall("read_calendar", mapOf("date" to date))
        }

        // ── SMS reading ──

        Regex("(?:read|show|check|what(?:'s| is| are))\\s+(?:my\\s+)?(?:last|recent|latest|new)?\\s*(?:texts?|sms|text\\s+messages?)\\s*(?:from\\s+)?(.+)?", RegexOption.IGNORE_CASE).find(lower)?.let {
            val from = it.groupValues[1].trim().ifBlank { null }
            val params = mutableMapOf<String, String>()
            if (from != null) params["from"] = from
            return ToolCall("read_sms", params)
        }

        // ── Now playing ──

        if (lower.matches(Regex(".*(?:what(?:'s| is)\\s+(?:this\\s+)?(?:song|track|playing|music)).*")) ||
            lower.matches(Regex(".*(?:what\\s+am\\s+i\\s+listening).*")) ||
            lower.matches(Regex(".*(?:now\\s+playing|current\\s+(?:song|track)).*")))
            return ToolCall("now_playing")

        // ── Clipboard reading ──

        if (lower.matches(Regex(".*(?:what(?:'s| is| did i)\\s+(?:on\\s+(?:my\\s+)?|in\\s+(?:my\\s+)?)?(?:clipboard|copied|copy)).*")) ||
            lower.matches(Regex("^(?:paste|what did i copy|clipboard)$")))
            return ToolCall("read_clipboard")

        // ── Screen time ──

        Regex("(?:how\\s+(?:much|long)|screen\\s*time|usage|time\\s+(?:on|spent|using)).*?(?:on\\s+|using\\s+|in\\s+)?([\\w\\s]+)?$", RegexOption.IGNORE_CASE).find(lower)?.let {
            if (lower.contains("screen") || lower.contains("usage") || lower.contains("time spent") || lower.contains("how much time") || lower.contains("how long")) {
                val app = it.groupValues[1].trim().ifBlank { null }
                val params = mutableMapOf<String, String>()
                if (app != null && app !in listOf("today", "phone", "my phone")) params["app"] = app
                return ToolCall("screen_time", params)
            }
        }

        // ── Navigation ──

        Regex("(?:navigate|directions?|take me|drive)\\s+(?:to\\s+)?(.+)", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("navigate", mapOf("destination" to it.groupValues[1].trim()))
        }
        Regex("(?:find|where\\s+is|search\\s+(?:for\\s+)?(?:on\\s+)?(?:the\\s+)?map)\\s+(.+)", RegexOption.IGNORE_CASE).find(lower)?.let {
            if (lower.contains("map") || lower.contains("where") || lower.contains("nearby"))
                return ToolCall("search_map", mapOf("query" to it.groupValues[1].trim()))
        }

        // ── Notifications ──

        // Clear must come BEFORE read — "clear notifications" contains "notifications" which read would catch
        if (lower.matches(Regex(".*(?:clear|dismiss|remove)\\s*(?:all\\s+)?(?:my\\s+)?(?:notifications?|notifs?).*")))
            return ToolCall("clear_notifications")
        if (lower.matches(Regex(".*(?:read|show|check|what are)\\s*(?:my\\s+)?(?:notifications?|notifs?).*")) ||
            lower.matches(Regex(".*(?:any\\s+)?(?:new\\s+)?notifications?\\??.*")))
            return ToolCall("read_notifications")

        // ── Bouncer: digest, 2FA ──

        if (lower.matches(Regex(".*(?:digest|evening\\s+digest|batched|noise\\s+notifications?).*")) ||
            lower.matches(Regex(".*(?:what\\s+did\\s+i\\s+miss|missed\\s+notifications?).*")))
            return ToolCall("get_digest")
        if (lower.matches(Regex(".*(?:clear|delete)\\s+(?:the\\s+)?digest.*")))
            return ToolCall("clear_digest")
        if (lower.matches(Regex(".*(?:2fa|otp|verification|verify)\\s*(?:code)?.*")) ||
            lower.matches(Regex(".*(?:what(?:'s| is)\\s+(?:the|my)\\s+(?:code|otp|2fa)).*")))
            return ToolCall("get_2fa_code")

        // ── Vision / Camera ──

        if (lower.matches(Regex(".*(?:take|capture|snap)\\s+(?:a\\s+)?(?:photo|picture|pic|image|selfie).*")) ||
            lower.matches(Regex(".*(?:what(?:'s| is|do you)\\s+(?:in front|around|see|near)).*")) ||
            lower == "take a photo" || lower == "take photo")
            return ToolCall("take_photo")

        // ── Screen control (Accessibility) ──

        if (lower.matches(Regex(".*(?:read|what(?:'s| is)\\s+on)\\s+(?:the\\s+)?screen.*")) ||
            lower.matches(Regex(".*(?:what\\s+do\\s+(?:i|you)\\s+see).*")))
            return ToolCall("read_screen")
        Regex("(?:tap|click|press|hit)\\s+(?:on\\s+)?(?:the\\s+)?[\"']?(.+?)[\"']?\\s*$", RegexOption.IGNORE_CASE).find(lower)?.let {
            val query = it.groupValues[1].trim()
            if (query.isNotBlank() && query != "back" && query != "home")
                return ToolCall("find_and_tap", mapOf("query" to query))
        }
        if (lower.matches(Regex(".*(?:scroll|swipe)\\s+down.*")))
            return ToolCall("scroll_down")
        if (lower == "go back" || lower == "back" || lower.matches(Regex(".*press\\s+back.*")))
            return ToolCall("press_back")
        if (lower.matches(Regex(".*(?:go|press)\\s+home.*")) && !lower.contains("screen") && !lower.contains("chat") && !lower.contains("lui"))
            return ToolCall("press_home")

        // ── Switch back to LUI ──

        if (lower.matches(Regex(".*(?:switch|go|come|get)\\s+(?:back\\s+)?(?:to\\s+)?(?:lui|our\\s+chat|the\\s+chat|chat|home\\s+screen).*")) ||
            lower.matches(Regex(".*(?:open|show)\\s+lui.*")) ||
            lower == "switch back" || lower == "come back" || lower == "go home")
            return ToolCall("open_lui")

        // ── Deep link app search — "play X on Spotify", "search X on YouTube" ──
        // Must come before web search to catch "search X on YouTube" as an app deep link, not a web search

        Regex("(?:play|search|search for|find|look up)\\s+(.+?)\\s+(?:on|in|with)\\s+(\\w+.*)$", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("open_app_search", mapOf("query" to it.groupValues[1].trim(), "app" to it.groupValues[2].trim()))
        }

        // ── Web search & browse ──

        Regex("(?:search the web|web search|google|look up online|search online)\\s+(?:for\\s+)?(.+)", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("search_web", mapOf("query" to it.groupValues[1].trim()))
        }
        // Fallback: "search X" without "on <app>" → web search
        Regex("^(?:search|look up)\\s+(?:for\\s+)?(.{3,})\\s*$", RegexOption.IGNORE_CASE).find(lower)?.let {
            val query = it.groupValues[1].trim()
            if (!query.startsWith("contact") && !query.startsWith("map"))
                return ToolCall("search_web", mapOf("query" to query))
        }

        // Only match explicit "browse X.com" — not "go to X" which should go to LLM for reasoning
        Regex("(?:browse|visit)\\s+(?:the\\s+)?(?:url\\s+|website\\s+|page\\s+|site\\s+)?(https?://\\S+|\\S+\\.\\S+)", RegexOption.IGNORE_CASE).find(lower)?.let {
            val url = it.groupValues[1].trim()
            // Don't match if there's more intent after the URL ("and search for...")
            if (url.contains(".") && !url.endsWith(".") && url.length > 4 && !lower.contains(" and "))
                return ToolCall("browse_url", mapOf("url" to url))
        }

        // ── Ambient context ──

        if (lower.matches(Regex(".*(?:ambient|device\\s+context|phone\\s+status|full\\s+status|everything\\s+about\\s+(?:my\\s+)?(?:phone|device)).*")) ||
            lower.matches(Regex(".*(?:what(?:'s| is)\\s+(?:the\\s+)?(?:status|state|context)\\s+(?:of\\s+)?(?:my\\s+)?(?:phone|device)).*")))
            return ToolCall("ambient_context")

        if (lower.matches(Regex(".*(?:bluetooth|bt)\\s+(?:devices?|paired|connected|list).*")) ||
            lower.matches(Regex(".*(?:what(?:'s| is)\\s+(?:connected|paired)\\s+(?:to\\s+)?(?:bluetooth|bt)).*")) ||
            lower.matches(Regex(".*(?:list|show)\\s+(?:my\\s+)?(?:bluetooth|bt)\\s+(?:devices?).*")))
            return ToolCall("bluetooth_devices")

        if (lower.matches(Regex(".*(?:network|internet|connection)\\s+(?:state|status|info|details|type|speed).*")) ||
            lower.matches(Regex(".*(?:am\\s+i\\s+on)\\s+(?:wifi|mobile|data|4g|5g|lte).*")))
            return ToolCall("network_state")

        // ── Health ring ──

        if (lower.matches(Regex(".*(?:health\\s+summary|health\\s+status|how(?:'s| is)\\s+my\\s+health|vitals|health\\s+check).*")))
            return ToolCall("get_health_summary")

        if (lower.matches(Regex(".*(?:what\\s+(?:vitals|health|data)\\s+can|ring\\s+capabilities|what\\s+can\\s+(?:the\\s+)?ring|available\\s+vitals|what\\s+does\\s+(?:the\\s+)?ring\\s+(?:track|measure|monitor)).*")))
            return ToolCall("ring_capabilities")

        if (lower.matches(Regex(".*(?:heart\\s*rate|pulse|bpm|heartbeat).*")) ||
            lower.matches(Regex(".*(?:what(?:'s| is)\\s+my\\s+(?:heart|pulse|hr)).*")) ||
            lower.matches(Regex(".*(?:check|measure|read)\\s+(?:my\\s+)?(?:heart|pulse|hr).*")))
            return ToolCall("get_heart_rate")

        if (lower.matches(Regex(".*(?:ring)\\s+(?:battery|charge|level).*")) ||
            lower.matches(Regex(".*(?:how\\s+much)\\s+(?:ring|band)\\s+(?:battery|charge).*")))
            return ToolCall("ring_battery")

        if (lower.matches(Regex(".*(?:ring|band)\\s+(?:status|info|connected).*")))
            return ToolCall("ring_status")

        if (lower.matches(Regex(".*(?:find|locate|where).*(?:ring|band).*")) ||
            lower.matches(Regex(".*(?:ring|band).*(?:vibrate|buzz|beep).*")))
            return ToolCall("find_ring")

        if (lower.matches(Regex(".*(?:blood\\s*oxygen|spo2|sp\\s*o\\s*2|oxygen\\s*(?:level|saturation)).*")))
            return ToolCall("get_spo2")

        if (lower.matches(Regex(".*(?:how\\s+(?:did|was)\\s+(?:my\\s+)?sleep|sleep\\s+(?:data|stages?|quality|summary|report|tracking)|last\\s+night(?:'s)?\\s+sleep).*")))
            return ToolCall("get_sleep")

        if (lower.matches(Regex(".*(?:(?:steps|activity|calories)\\s+(?:from|on|ring)|ring\\s+(?:steps|activity)|step\\s+count\\s+(?:from|on)\\s+(?:the\\s+)?ring).*")))
            return ToolCall("get_activity")

        if (lower.matches(Regex(".*(?:stress\\s+(?:level|score|reading)|(?:how|what)(?:'s| is)\\s+my\\s+stress|am\\s+i\\s+stressed).*")))
            return ToolCall("get_stress")

        if (lower.matches(Regex(".*(?:hrv|heart\\s*rate\\s*variability|h\\.?r\\.?v\\.?).*")))
            return ToolCall("get_hrv")

        if (lower.matches(Regex(".*(?:(?:body\\s+)?temp(?:erature)?\\s+(?:from|on|ring)|ring\\s+temp|my\\s+(?:body\\s+)?temp(?:erature)?).*")))
            return ToolCall("get_temperature")

        // ── Lock / Screenshot / Split Screen ──

        if (lower.matches(Regex(".*(?:lock)\\s+(?:my\\s+)?(?:phone|screen|device).*")) || lower == "lock")
            return ToolCall("lock_screen")
        if (lower.matches(Regex(".*(?:take|capture|grab)\\s+(?:a\\s+)?(?:screenshot|screen\\s*shot|screen\\s*cap).*")))
            return ToolCall("take_screenshot")
        if (lower.matches(Regex(".*(?:split|dual)\\s+screen.*")))
            return ToolCall("split_screen")

        // ── Screen timeout / keep on ──

        if (lower.matches(Regex(".*(?:keep|stay)\\s+(?:the\\s+)?screen\\s+on.*")) || lower.matches(Regex(".*(?:don't|do not)\\s+(?:let\\s+)?(?:the\\s+)?screen\\s+(?:turn\\s+)?off.*")))
            return ToolCall("keep_screen_on", mapOf("enable" to "true"))
        if (lower.matches(Regex(".*(?:release|stop keeping|let)\\s+(?:the\\s+)?screen.*")))
            return ToolCall("keep_screen_on", mapOf("enable" to "false"))
        Regex("(?:set\\s+)?screen\\s*(?:timeout|off)\\s+(?:to\\s+)?(.+)", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("set_screen_timeout", mapOf("duration" to it.groupValues[1].trim()))
        }

        // ── Bedtime mode ──

        if (lower.matches(Regex(".*(?:bedtime|sleep|night)\\s+mode.*(?:on|enable)?.*")) ||
            lower.matches(Regex(".*(?:enable|turn on|activate)\\s+(?:bedtime|sleep|night).*")))
            return ToolCall("bedtime_mode", mapOf("enable" to "true"))
        if (lower.matches(Regex(".*(?:disable|turn off|deactivate)\\s+(?:bedtime|sleep|night).*")))
            return ToolCall("bedtime_mode", mapOf("enable" to "false"))

        // ── Sensors ──

        if (lower.matches(Regex(".*(?:steps|step\\s+count|pedometer|how\\s+(?:many|much)\\s+(?:steps|walked)).*")))
            return ToolCall("get_steps")
        if (lower.matches(Regex(".*(?:proximity|face.?down|in\\s+(?:my\\s+)?pocket).*")))
            return ToolCall("get_proximity")
        if (lower.matches(Regex(".*(?:light\\s+(?:level|sensor)|ambient\\s+light|how\\s+(?:bright|dark)\\s+is\\s+it|lux).*")))
            return ToolCall("get_light")

        // ── Storage / RAM / Wi-Fi ──

        if (lower.matches(Regex(".*(?:storage|disk|space|how\\s+much\\s+(?:storage|space|memory)).*")) ||
            lower.matches(Regex(".*(?:ram|free\\s+memory).*")))
            return ToolCall("storage_info")
        if (lower.matches(Regex(".*(?:what\\s+)?wifi.*(?:connected|info|name|ssid|signal|speed).*")) ||
            lower.matches(Regex(".*(?:what\\s+)?(?:network|internet)\\s+(?:am\\s+i|connected).*")))
            return ToolCall("wifi_info")

        // ── Download ──

        Regex("(?:download|save|fetch)\\s+(?:this\\s+)?(?:file\\s+)?(?:from\\s+)?(.+)", RegexOption.IGNORE_CASE).find(lower)?.let {
            val url = it.groupValues[1].trim()
            if (url.startsWith("http")) return ToolCall("download_file", mapOf("url" to url))
        }

        // ── Media query ──

        if (lower.matches(Regex(".*(?:photos?|pictures?|images?)\\s+(?:i\\s+)?(?:took|taken|from)\\s+(?:today|yesterday|this\\s+week|this\\s+month).*")) ||
            lower.matches(Regex(".*(?:how\\s+many|show|list)\\s+(?:my\\s+)?(?:photos?|pictures?).*"))) {
            val date = when {
                lower.contains("yesterday") -> "yesterday"
                lower.contains("week") -> "this week"
                lower.contains("month") -> "this month"
                else -> "today"
            }
            return ToolCall("query_media", mapOf("type" to "photos", "date" to date))
        }
        if (lower.matches(Regex(".*(?:how\\s+many|show|list)\\s+(?:my\\s+)?(?:videos?).*"))) {
            return ToolCall("query_media", mapOf("type" to "videos", "date" to "today"))
        }
        if (lower.matches(Regex(".*(?:how\\s+many|show|list)\\s+(?:my\\s+)?(?:songs?|music|audio).*"))) {
            return ToolCall("query_media", mapOf("type" to "music"))
        }

        // ── Audio routing ──

        if (lower.matches(Regex(".*(?:switch|route|output)\\s+(?:audio\\s+)?(?:to\\s+)?(?:speaker|speakerphone).*")))
            return ToolCall("route_audio", mapOf("target" to "speaker"))
        if (lower.matches(Regex(".*(?:switch|route|output)\\s+(?:audio\\s+)?(?:to\\s+)?(?:bluetooth|bt|headset|headphones?).*")))
            return ToolCall("route_audio", mapOf("target" to "bluetooth"))
        if (lower.matches(Regex(".*(?:switch|route|output)\\s+(?:audio\\s+)?(?:to\\s+)?(?:earpiece|phone).*")))
            return ToolCall("route_audio", mapOf("target" to "earpiece"))

        // ── BYOS Bridge ──

        if (lower.matches(Regex(".*(?:start|enable|turn on|activate)\\s+(?:the\\s+)?(?:bridge|websocket|byos|server).*")))
            return ToolCall("start_bridge")
        if (lower.matches(Regex(".*(?:stop|disable|turn off|deactivate)\\s+(?:the\\s+)?(?:bridge|websocket|byos|server).*")))
            return ToolCall("stop_bridge")
        if (lower.matches(Regex(".*(?:bridge|websocket|byos|server)\\s+(?:status|info|url|token).*")))
            return ToolCall("bridge_status")

        // ── Open settings ──

        if (lower.matches(Regex(".*(?:open|go\\s+to|show)\\s+settings.*")))
            return ToolCall("open_settings")

        // ── Open app (catch-all, low priority) ──

        Regex("(?:open|launch|start|run)\\s+(.+)", RegexOption.IGNORE_CASE).find(lower)?.let {
            val name = it.groupValues[1].trim()
            if (name.isNotBlank() && name !in listOf("wifi", "bluetooth", "settings", "the flashlight", "flashlight"))
                return ToolCall("open_app", mapOf("name" to name))
        }

        // ── Phone call ──

        Regex("(?:call|phone|dial)\\s+(.+)", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("make_call", mapOf("target" to it.groupValues[1].trim()))
        }

        // ── Wi-Fi ──

        if (lower.matches(Regex(".*(wifi|wi-fi).*")))
            return ToolCall("open_settings_wifi")

        // ── Bluetooth ──

        if (lower.contains("bluetooth"))
            return ToolCall("open_settings_bluetooth")

        // ── Wallpaper ──

        if (lower.matches(Regex(".*(?:set|apply|change).*wallpaper.*")))
            return ToolCall("set_wallpaper")

        return null
    }
}
