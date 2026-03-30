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
        "get_digest", "clear_digest", "get_2fa_code", "config_triage"
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

        Regex("(?:read|show|check|what are)\\s+(?:my\\s+)?(?:last\\s+)?(?:texts?|sms|messages?)\\s*(?:from\\s+)?(.+)?", RegexOption.IGNORE_CASE).find(lower)?.let {
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

        if (lower.matches(Regex(".*(?:read|show|check|what are)\\s*(?:my\\s+)?(?:notifications?|notifs?).*")) ||
            lower.matches(Regex(".*(?:any\\s+)?(?:new\\s+)?(?:messages?|notifications?)\\??.*")))
            return ToolCall("read_notifications")
        if (lower.matches(Regex(".*(?:clear|dismiss|remove)\\s*(?:all\\s+)?(?:notifications?|notifs?).*")))
            return ToolCall("clear_notifications")

        // ── Bouncer: digest, 2FA ──

        if (lower.matches(Regex(".*(?:digest|evening\\s+digest|batched|noise\\s+notifications?).*")) ||
            lower.matches(Regex(".*(?:what\\s+did\\s+i\\s+miss|missed\\s+notifications?).*")))
            return ToolCall("get_digest")
        if (lower.matches(Regex(".*(?:clear|delete)\\s+(?:the\\s+)?digest.*")))
            return ToolCall("clear_digest")
        if (lower.matches(Regex(".*(?:2fa|otp|verification|verify)\\s*(?:code)?.*")) ||
            lower.matches(Regex(".*(?:what(?:'s| is)\\s+(?:the|my)\\s+(?:code|otp|2fa)).*")))
            return ToolCall("get_2fa_code")

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

        Regex("(?:play|search|search for|find|look up)\\s+(.+?)\\s+(?:on|in|with)\\s+(\\w+.*)$", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("open_app_search", mapOf("query" to it.groupValues[1].trim(), "app" to it.groupValues[2].trim()))
        }

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
