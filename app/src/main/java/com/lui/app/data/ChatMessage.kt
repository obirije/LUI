package com.lui.app.data

data class ChatMessage(
    val text: String,
    val sender: Sender,
    val timestamp: Long = System.currentTimeMillis(),
    val streaming: Boolean = false,
    val imageBitmap: android.graphics.Bitmap? = null,
    val cardType: CardType? = null,
    val cardData: List<Map<String, String>>? = null
) {
    enum class Sender { USER, LUI, WELCOME, THINKING }

    enum class CardType {
        SEARCH_RESULTS,
        DEVICE_STATUS,
        LINK_PREVIEW
    }
}
