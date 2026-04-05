package com.lui.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val sender: String,
    val timestamp: Long,
    val cardType: String? = null
) {
    fun toChatMessage(): ChatMessage {
        val type = cardType?.let {
            try { ChatMessage.CardType.valueOf(it) } catch (_: Exception) { null }
        }
        return ChatMessage(
            text = text,
            sender = ChatMessage.Sender.valueOf(sender),
            timestamp = timestamp,
            cardType = type,
            cardData = if (type != null) deriveCardData(type, text) else null
        )
    }

    companion object {
        fun from(msg: ChatMessage): ChatMessageEntity = ChatMessageEntity(
            text = msg.text,
            sender = msg.sender.name,
            timestamp = msg.timestamp,
            cardType = msg.cardType?.name
        )

        private fun deriveCardData(type: ChatMessage.CardType, text: String): List<Map<String, String>>? {
            return when (type) {
                ChatMessage.CardType.SEARCH_RESULTS -> {
                    val results = mutableListOf<Map<String, String>>()
                    val pattern = Regex("""(\d+)\.\s+(.+)\n\s+(.+)\n\s+(https?://\S+)""")
                    for (match in pattern.findAll(text)) {
                        results.add(mapOf(
                            "title" to match.groupValues[2].trim(),
                            "snippet" to match.groupValues[3].trim(),
                            "url" to match.groupValues[4].trim()
                        ))
                    }
                    results.ifEmpty { null }
                }
                ChatMessage.CardType.DEVICE_STATUS -> {
                    val rows = mutableListOf<Map<String, String>>()
                    for (line in text.lines()) {
                        val parts = line.split(":", limit = 2)
                        if (parts.size == 2 && parts[0].trim().isNotBlank()) {
                            val label = parts[0].trim()
                            val value = parts[1].trim()
                            val color = when {
                                value.contains("not charging", true) -> "#FFC107"
                                value.contains("charging", true) -> "#4CAF50"
                                value.contains("disconnected", true) -> "#F44336"
                                value.contains("Wi-Fi", true) -> "#4CAF50"
                                value.contains("off", true) -> "#9E9E9E"
                                else -> null
                            }
                            val map = mutableMapOf("label" to label, "value" to value)
                            if (color != null) map["color"] = color
                            rows.add(map)
                        }
                    }
                    rows.ifEmpty { null }
                }
                ChatMessage.CardType.LINK_PREVIEW -> null
            }
        }
    }
}
