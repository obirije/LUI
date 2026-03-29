package com.lui.app.llm

enum class CloudProvider(
    val displayName: String,
    val endpoint: String,
    val defaultModel: String
) {
    GEMINI(
        "Gemini",
        "https://generativelanguage.googleapis.com/v1beta/models",
        "gemini-2.0-flash"
    ),
    CLAUDE(
        "Claude",
        "https://api.anthropic.com/v1/messages",
        "claude-sonnet-4-20250514"
    ),
    OPENAI(
        "OpenAI",
        "https://api.openai.com/v1/chat/completions",
        "gpt-4o-mini"
    );

    companion object {
        fun fromName(name: String?): CloudProvider? =
            entries.find { it.name.equals(name, ignoreCase = true) }
    }
}
