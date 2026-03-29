package com.lui.app.llm

import kotlinx.coroutines.flow.Flow

interface LlmProvider {
    val isReady: Boolean
    fun generateStreaming(userMessage: String): Flow<String>
}
