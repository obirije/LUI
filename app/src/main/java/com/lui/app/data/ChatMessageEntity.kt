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
                ChatMessage.CardType.HEALTH_TREND_CHART -> deriveHealthTrend(text)
                ChatMessage.CardType.NOTIFICATIONS -> deriveNotifications(text)
            }
        }

        // Public aliases used by LuiViewModel.buildCardMessage so the same parser
        // is shared between live build and Room rehydration.
        fun deriveHealthTrendForBuilder(text: String) = deriveHealthTrend(text)
        fun deriveNotificationsForBuilder(text: String) = deriveNotifications(text)

        private fun deriveHealthTrend(text: String): List<Map<String, String>>? {
            val rows = mutableListOf<Map<String, String>>()
            val meta = mutableMapOf<String, String>()

            // Header line: "Heart Rate trend (last 24 hours, 96 readings):"
            val header = Regex("""^(.+?) trend \(last (\d+) hours""", RegexOption.MULTILINE).find(text)
            if (header != null) {
                meta["label"] = header.groupValues[1]
                meta["hours"] = header.groupValues[2]
            }
            Regex("""Average:\s*(.+?)$""", RegexOption.MULTILINE).find(text)?.let {
                meta["avg"] = it.groupValues[1].trim()
            }
            Regex("""Range:\s*(.+?)$""", RegexOption.MULTILINE).find(text)?.let {
                meta["range"] = it.groupValues[1].trim()
            }
            Regex("""Latest:\s*(.+?)$""", RegexOption.MULTILINE).find(text)?.let {
                meta["latest"] = it.groupValues[1].trim()
            }
            rows.add(meta)

            // Chart marker: [chart:t1=v1;t2=v2;...]
            val chart = Regex("""\[chart:([^]]+)]""").find(text)
            if (chart != null) {
                for (pair in chart.groupValues[1].split(';')) {
                    val (t, v) = pair.split('=').takeIf { it.size == 2 } ?: continue
                    rows.add(mapOf("t" to t, "v" to v))
                }
            }
            return if (rows.size > 1) rows else null
        }

        private fun deriveNotifications(text: String): List<Map<String, String>>? {
            // Format we render from getNotificationHistory / getDigest:
            //   AppName (3):
            //     9:42 AM: Title — snippet
            //     9:30 AM: Title — snippet
            val rows = mutableListOf<Map<String, String>>()
            // First row = header text
            val firstLine = text.lines().firstOrNull { it.isNotBlank() }
            if (firstLine != null) rows.add(mapOf("header" to firstLine.trim()))

            val groupRegex = Regex("""^(.+?)\s*\((\d+)\):\s*$""")
            val entryRegex = Regex("""^\s+([^:]+?):\s*(.+?)$""")
            var currentApp: String? = null
            var currentCount: String? = null
            for (line in text.lines()) {
                val g = groupRegex.find(line)
                if (g != null) {
                    currentApp = g.groupValues[1].trim()
                    currentCount = g.groupValues[2]
                    continue
                }
                val e = entryRegex.find(line)
                if (e != null && currentApp != null) {
                    val time = e.groupValues[1].trim()
                    val rest = e.groupValues[2].trim()
                    val (title, snippet) = rest.split(" — ", limit = 2).let {
                        if (it.size == 2) it[0] to it[1] else it[0] to ""
                    }
                    rows.add(mapOf(
                        "app" to currentApp!!,
                        "count" to (currentCount ?: ""),
                        "time" to time,
                        "title" to title,
                        "snippet" to snippet
                    ))
                }
            }
            return if (rows.size > 1) rows else null
        }
    }
}
