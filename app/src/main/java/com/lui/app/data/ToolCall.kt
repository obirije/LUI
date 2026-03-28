package com.lui.app.data

/**
 * Represents a parsed tool call from LLM output or keyword matching.
 * Example JSON: {"tool": "set_alarm", "params": {"time": "07:30", "label": "Morning"}}
 */
data class ToolCall(
    val tool: String,
    val params: Map<String, String> = emptyMap()
)
