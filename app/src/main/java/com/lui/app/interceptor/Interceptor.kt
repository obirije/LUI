package com.lui.app.interceptor

import com.lui.app.data.ToolCall
import org.json.JSONObject

/**
 * Dual parser: tries JSON extraction first, falls back to keyword matching.
 * Returns null if no actionable intent is detected.
 */
object Interceptor {

    private val KNOWN_TOOLS = setOf(
        "toggle_flashlight",
        "set_alarm",
        "set_timer",
        "open_app",
        "make_call",
        "open_settings_wifi",
        "open_settings_bluetooth",
        "set_wallpaper"
    )

    fun parse(input: String): ToolCall? {
        return parseJson(input) ?: parseKeywords(input)
    }

    /**
     * Try to extract a JSON tool call from text.
     * Looks for {"tool": "...", "params": {...}} anywhere in the string.
     */
    private fun parseJson(input: String): ToolCall? {
        try {
            val jsonStart = input.indexOf('{')
            val jsonEnd = input.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd <= jsonStart) return null

            val jsonStr = input.substring(jsonStart, jsonEnd + 1)
            val json = JSONObject(jsonStr)

            val tool = json.optString("tool", "").lowercase()
            if (tool.isBlank() || tool !in KNOWN_TOOLS) return null

            val params = mutableMapOf<String, String>()
            val paramsObj = json.optJSONObject("params")
            if (paramsObj != null) {
                for (key in paramsObj.keys()) {
                    params[key] = paramsObj.getString(key)
                }
            }
            return ToolCall(tool, params)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Keyword/regex fallback for when the LLM responds in prose.
     */
    private fun parseKeywords(input: String): ToolCall? {
        val lower = input.lowercase().trim()

        // Flashlight
        if (lower.contains("flashlight") || lower.contains("torch") || lower.contains("flash light")) {
            return ToolCall("toggle_flashlight")
        }

        // Alarm
        val alarmPattern = Regex(".*(?:set|create|make).*(?:alarm|wake).*?(\\d{1,2}[:\\.]?\\d{0,2}\\s*(?:am|pm)?)", RegexOption.IGNORE_CASE)
        alarmPattern.find(lower)?.let { match ->
            val time = match.groupValues[1].trim()
            return ToolCall("set_alarm", mapOf("time" to time))
        }

        // Timer
        val timerPattern = Regex(".*(?:set|start|create).*timer.*?(\\d+)\\s*(min|minute|sec|second|hour|hr)", RegexOption.IGNORE_CASE)
        timerPattern.find(lower)?.let { match ->
            val amount = match.groupValues[1]
            val unit = match.groupValues[2]
            return ToolCall("set_timer", mapOf("amount" to amount, "unit" to unit))
        }

        // Open app
        val openPattern = Regex("(?:open|launch|start|run)\\s+(.+)", RegexOption.IGNORE_CASE)
        openPattern.find(lower)?.let { match ->
            val appName = match.groupValues[1].trim()
            // Filter out non-app targets
            if (appName.isNotBlank() && appName !in listOf("settings", "wifi", "bluetooth")) {
                return ToolCall("open_app", mapOf("name" to appName))
            }
        }

        // Phone call
        val callPattern = Regex("(?:call|phone|dial)\\s+(.+)", RegexOption.IGNORE_CASE)
        callPattern.find(lower)?.let { match ->
            val target = match.groupValues[1].trim()
            return ToolCall("make_call", mapOf("target" to target))
        }

        // Wi-Fi settings
        if (lower.matches(Regex(".*(wifi|wi-fi).*"))) {
            return ToolCall("open_settings_wifi")
        }

        // Bluetooth settings
        if (lower.matches(Regex(".*(bluetooth|bt).*(?:on|off|toggle|settings|connect).*")) ||
            lower.matches(Regex(".*(?:turn on|enable|toggle).*bluetooth.*"))) {
            return ToolCall("open_settings_bluetooth")
        }

        // Wallpaper
        if (lower.matches(Regex(".*(?:set|apply|change).*wallpaper.*")) ||
            lower.matches(Regex(".*wallpaper.*(?:set|apply|lui|theme).*"))) {
            return ToolCall("set_wallpaper")
        }

        return null
    }
}
