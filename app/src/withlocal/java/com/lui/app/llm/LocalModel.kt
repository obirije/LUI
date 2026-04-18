package com.lui.app.llm

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.io.File

class LocalModel(private val context: Context) : LlmProvider {

    companion object {
        private const val TAG = "LuiLLM"
        const val MODEL_FILENAME = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
    }

    private val engine: InferenceEngine = AiChat.getInferenceEngine(context)

    override val isReady: Boolean get() = engine.state.value.isModelLoaded

    fun getModelPath(): String {
        return File(context.filesDir, "models/$MODEL_FILENAME").absolutePath
    }

    fun isModelDownloaded(): Boolean {
        val file = File(getModelPath())
        return file.exists() && file.length() > 1_000_000
    }

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val modelPath = getModelPath()
            if (!File(modelPath).exists()) {
                return@withContext Result.failure(Exception("Model not found at $modelPath"))
            }

            Log.d(TAG, "Loading model from $modelPath")
            engine.loadModel(modelPath)
            engine.setSystemPrompt(SystemPrompt.PROMPT)
            Log.d(TAG, "Model loaded and system prompt set")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LLM", e)
            Result.failure(e)
        }
    }

    override fun generateStreaming(userMessage: String): Flow<String> {
        if (!isReady) return emptyFlow()
        // Budget enough tokens for the model to produce a full answer without truncation.
        return engine.sendUserPrompt(userMessage, 1024)
    }

    fun close() {
        try {
            engine.cleanUp()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up", e)
        }
    }
}
