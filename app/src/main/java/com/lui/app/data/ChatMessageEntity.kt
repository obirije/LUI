package com.lui.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val sender: String,
    val timestamp: Long
) {
    fun toChatMessage(): ChatMessage = ChatMessage(
        text = text,
        sender = ChatMessage.Sender.valueOf(sender),
        timestamp = timestamp
    )

    companion object {
        fun from(msg: ChatMessage): ChatMessageEntity = ChatMessageEntity(
            text = msg.text,
            sender = msg.sender.name,
            timestamp = msg.timestamp
        )
    }
}
