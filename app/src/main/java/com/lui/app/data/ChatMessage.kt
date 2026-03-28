package com.lui.app.data

data class ChatMessage(
    val text: String,
    val sender: Sender,
    val timestamp: Long = System.currentTimeMillis(),
    val streaming: Boolean = false
) {
    enum class Sender { USER, LUI, WELCOME }
}
