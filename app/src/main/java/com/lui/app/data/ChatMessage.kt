package com.lui.app.data

data class ChatMessage(
    val text: String,
    val sender: Sender,
    val timestamp: Long = System.currentTimeMillis(),
    val streaming: Boolean = false,
    val imageBitmap: android.graphics.Bitmap? = null
) {
    enum class Sender { USER, LUI, WELCOME, THINKING }
}
