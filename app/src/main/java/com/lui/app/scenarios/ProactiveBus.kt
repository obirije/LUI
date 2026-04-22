package com.lui.app.scenarios

import com.lui.app.data.ChatMessage
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Fan-out channel for LUI-initiated chat messages produced outside the
 * normal user → tool → reply flow. Scenario observers and the scheduled
 * trigger receiver post here; [com.lui.app.LuiViewModel] subscribes while
 * alive and forwards each event to the chat canvas.
 *
 * Events emitted while no subscriber is listening are dropped. Scenarios
 * that must reach the user even when LUI is closed should additionally
 * surface a system notification.
 */
object ProactiveBus {
    val messages: MutableSharedFlow<ChatMessage> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 16)

    fun emit(message: ChatMessage) {
        messages.tryEmit(message)
    }
}
