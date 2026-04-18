package com.lui.app.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * Cloud-only stub of LocalModel for the "lite" build flavor.
 *
 * The lite APK does not bundle llama.cpp (saves ~96 MB of native libraries),
 * so on-device LLM inference is unavailable. This stub keeps the public API
 * identical so callers compile unchanged. `isReady` is permanently false,
 * which causes [LlmRouter] to route every request to the cloud provider.
 *
 * Method signatures match the real implementation in `app/src/withlocal/`.
 */
class LocalModel(private val context: Context) : LlmProvider {

    companion object {
        private const val TAG = "LuiLLM"
        // Kept so ConnectionHubFragment / ModelManager / ModelDownloader compile unchanged.
        // The lite UI hides the model-management section, so this value is never user-visible.
        const val MODEL_FILENAME = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
    }

    override val isReady: Boolean get() = false

    fun getModelPath(): String =
        File(context.filesDir, "models/$MODEL_FILENAME").absolutePath

    fun isModelDownloaded(): Boolean = false

    suspend fun initialize(): Result<Unit> {
        Log.i(TAG, "Local LLM unavailable in this build (cloud-only)")
        return Result.failure(
            UnsupportedOperationException("Local LLM disabled in cloud-only build")
        )
    }

    override fun generateStreaming(userMessage: String): Flow<String> = emptyFlow()

    fun close() {
        // no-op
    }
}
