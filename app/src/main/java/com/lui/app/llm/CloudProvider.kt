package com.lui.app.llm

enum class CloudProvider(
    val displayName: String,
    val endpoint: String,
    val defaultModel: String
) {
    GEMMA4(
        "Gemma 4",
        "https://generativelanguage.googleapis.com/v1beta/models",
        "gemma-4-26b-a4b-it"
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
    ),
    OLLAMA(
        "Ollama",
        "http://localhost:11434/v1/chat/completions",
        "qwen2.5:7b"
    );

    companion object {
        fun fromName(name: String?): CloudProvider? =
            entries.find { it.name.equals(name, ignoreCase = true) }
    }
}
