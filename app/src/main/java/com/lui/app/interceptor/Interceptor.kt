package com.lui.app.interceptor

import com.lui.app.data.ToolCall
import org.json.JSONObject

object Interceptor {

    private val KNOWN_TOOLS = setOf(
        "toggle_flashlight", "set_alarm", "set_timer", "open_app", "make_call",
        "open_settings_wifi", "open_settings_bluetooth", "set_wallpaper",
        "set_volume", "set_brightness", "toggle_dnd", "toggle_rotation"
    )

    fun parse(input: String): ToolCall? {
        return parseJson(input) ?: parseKeywords(input)
    }

    private fun parseJson(input: String): ToolCall? {
        try {
            val jsonStart = input.indexOf('{')
            val jsonEnd = input.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd <= jsonStart) return null
            val json = JSONObject(input.substring(jsonStart, jsonEnd + 1))
            val tool = json.optString("tool", "").lowercase()
            if (tool.isBlank() || tool !in KNOWN_TOOLS) return null
            val params = mutableMapOf<String, String>()
            json.optJSONObject("params")?.let { p -> for (k in p.keys()) params[k] = p.getString(k) }
            return ToolCall(tool, params)
        } catch (e: Exception) { return null }
    }

    private fun parseKeywords(input: String): ToolCall? {
        val lower = input.lowercase().trim()

        // Flashlight
        if (lower.contains("flashlight") || lower.contains("torch") || lower.contains("flash light"))
            return ToolCall("toggle_flashlight")

        // Alarm
        Regex(".*(?:set|create|make).*(?:alarm|wake).*?(\\d{1,2}[:\\.]?\\d{0,2}\\s*(?:am|pm)?)", RegexOption.IGNORE_CASE)
            .find(lower)?.let { return ToolCall("set_alarm", mapOf("time" to it.groupValues[1].trim())) }

        // Timer
        Regex(".*(?:set|start|create).*timer.*?(\\d+)\\s*(min|minute|sec|second|hour|hr)", RegexOption.IGNORE_CASE)
            .find(lower)?.let { return ToolCall("set_timer", mapOf("amount" to it.groupValues[1], "unit" to it.groupValues[2])) }

        // Volume
        if (lower.matches(Regex(".*(?:volume|sound).*(?:up|increase|raise|louder).*")))
            return ToolCall("set_volume", mapOf("direction" to "up"))
        if (lower.matches(Regex(".*(?:volume|sound).*(?:down|decrease|lower|quieter).*")))
            return ToolCall("set_volume", mapOf("direction" to "down"))
        if (lower.matches(Regex(".*(?:mute|silence|volume off).*")))
            return ToolCall("set_volume", mapOf("direction" to "mute"))
        if (lower.matches(Regex(".*(?:max|full).*volume.*")) || lower.matches(Regex(".*volume.*(?:max|full).*")))
            return ToolCall("set_volume", mapOf("direction" to "max"))

        // Brightness
        if (lower.matches(Regex(".*brightness.*(?:up|increase|higher|brighter).*")) || lower.matches(Regex(".*(?:brighter|brighten).*")))
            return ToolCall("set_brightness", mapOf("level" to "up"))
        if (lower.matches(Regex(".*brightness.*(?:down|decrease|lower|dimmer|dim).*")) || lower.matches(Regex(".*(?:dimmer|dim).*")))
            return ToolCall("set_brightness", mapOf("level" to "down"))
        if (lower.matches(Regex(".*brightness.*(?:max|full).*")))
            return ToolCall("set_brightness", mapOf("level" to "max"))
        if (lower.matches(Regex(".*brightness.*(?:min|low|lowest).*")))
            return ToolCall("set_brightness", mapOf("level" to "low"))
        Regex(".*brightness.*?(\\d+)\\s*%?").find(lower)?.let {
            return ToolCall("set_brightness", mapOf("level" to it.groupValues[1]))
        }

        // DND
        if (lower.matches(Regex(".*(?:do not disturb|dnd|quiet mode|silence notifications).*")))
            return ToolCall("toggle_dnd")

        // Rotation
        if (lower.matches(Regex(".*(?:auto.?rotate|screen.?rotation|rotation).*")))
            return ToolCall("toggle_rotation")

        // Open app
        Regex("(?:open|launch|start|run)\\s+(.+)", RegexOption.IGNORE_CASE).find(lower)?.let {
            val name = it.groupValues[1].trim()
            if (name.isNotBlank() && name !in listOf("settings", "wifi", "bluetooth"))
                return ToolCall("open_app", mapOf("name" to name))
        }

        // Phone call
        Regex("(?:call|phone|dial)\\s+(.+)", RegexOption.IGNORE_CASE).find(lower)?.let {
            return ToolCall("make_call", mapOf("target" to it.groupValues[1].trim()))
        }

        // Wi-Fi
        if (lower.matches(Regex(".*(wifi|wi-fi).*")))
            return ToolCall("open_settings_wifi")

        // Bluetooth
        if (lower.contains("bluetooth"))
            return ToolCall("open_settings_bluetooth")

        // Wallpaper
        if (lower.matches(Regex(".*(?:set|apply|change).*wallpaper.*")))
            return ToolCall("set_wallpaper")

        return null
    }
}
