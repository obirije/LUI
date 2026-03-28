package com.lui.app.llm

object SystemPrompt {

    val PROMPT = """
You are LUI, a phone assistant. Reply in 1-2 sentences. No markdown. No emojis. /no_think
""".trimIndent()

    /**
     * Strip Qwen3 thinking blocks from output.
     */
    fun cleanResponse(response: String): String {
        return response
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .replace(Regex("<think>[\\s\\S]*$"), "")
            .trim()
    }
}
