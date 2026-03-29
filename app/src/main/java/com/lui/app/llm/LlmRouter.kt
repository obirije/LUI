package com.lui.app.llm

import com.lui.app.data.ChatMessage
import com.lui.app.data.SecureKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class LlmRouter(
    private val localModel: LocalModel,
    private val cloudModel: CloudModel,
    private val keyStore: SecureKeyStore
) {
    val isReady: Boolean
        get() = localModel.isReady || cloudModel.isReady

    val activeProviderName: String
        get() = when {
            keyStore.isCloudFirst && cloudModel.isReady -> keyStore.selectedProvider?.displayName ?: "Cloud"
            localModel.isReady -> "Local"
            cloudModel.isReady -> keyStore.selectedProvider?.displayName ?: "Cloud"
            else -> "None"
        }

    val isUsingCloud: Boolean
        get() = when {
            keyStore.isCloudFirst && cloudModel.isReady -> true
            !localModel.isReady && cloudModel.isReady -> true
            else -> false
        }

    fun generateStreaming(userMessage: String, history: List<ChatMessage> = emptyList()): Flow<String> {
        android.util.Log.i("LlmRouter", "Route: cloudFirst=${keyStore.isCloudFirst}, cloudReady=${cloudModel.isReady}, localReady=${localModel.isReady}, using=${activeProviderName}")

        if (keyStore.isCloudFirst && cloudModel.isReady) {
            return cloudModel.generateStreaming(userMessage, history)
        }

        if (localModel.isReady) {
            return localModel.generateStreaming(userMessage)
        }

        if (cloudModel.isReady) {
            return cloudModel.generateStreaming(userMessage, history)
        }

        return emptyFlow()
    }
}
