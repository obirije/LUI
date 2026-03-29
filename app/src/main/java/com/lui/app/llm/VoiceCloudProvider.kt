package com.lui.app.llm

enum class SpeechProvider(val displayName: String) {
    DEEPGRAM("Deepgram"),
    ELEVENLABS("ElevenLabs");

    companion object {
        fun fromName(name: String?): SpeechProvider? =
            entries.find { it.name.equals(name, ignoreCase = true) }
    }
}
