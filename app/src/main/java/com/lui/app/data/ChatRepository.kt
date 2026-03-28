package com.lui.app.data

import android.content.Context

class ChatRepository(context: Context) {
    private val dao = LuiDatabase.getInstance(context).chatMessageDao()

    suspend fun getHistory(): List<ChatMessage> {
        return dao.getAll().map { it.toChatMessage() }
    }

    suspend fun saveMessage(msg: ChatMessage) {
        // Only persist USER and LUI messages (not WELCOME, THINKING, or streaming)
        if (msg.sender == ChatMessage.Sender.USER || msg.sender == ChatMessage.Sender.LUI) {
            dao.insert(ChatMessageEntity.from(msg))
        }
    }

    suspend fun clearHistory() {
        dao.clearAll()
    }
}
