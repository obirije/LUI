package com.lui.app.llm

import android.content.Context
import com.lui.app.helper.LuiLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads GGUF model files from HuggingFace with resume support and progress reporting.
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"

    // Default model — Qwen2.5 1.5B Instruct Q4_K_M (~940MB, bartowski)
    // Switched from Qwen3.5 0.8B (2026-04-13) — that model has a thinking mode that
    // produced empty/canned chat responses on this size class.
    const val DEFAULT_MODEL_URL =
        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
    const val DEFAULT_MODEL_FILENAME = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"

    data class Progress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedBps: Long,
        val done: Boolean = false,
        val error: String? = null
    ) {
        val percent: Int get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
        val megabytesDownloaded: String get() = "%.1f".format(bytesDownloaded / 1_048_576.0)
        val totalMegabytes: String get() = "%.1f".format(totalBytes / 1_048_576.0)
        val speedMbps: String get() = "%.1f".format(speedBps / 1_048_576.0)
    }

    /**
     * Download the default LLM model to internal storage.
     * Supports resume — if a partial file exists, continues from where it left off.
     */
    fun downloadModel(
        context: Context,
        url: String = DEFAULT_MODEL_URL,
        filename: String = LocalModel.MODEL_FILENAME
    ): Flow<Progress> = flow {
        val targetDir = File(context.filesDir, "models")
        targetDir.mkdirs()
        val targetFile = File(targetDir, filename)
        val partFile = File(targetDir, "$filename.part")

        LuiLogger.i(TAG, "Starting download: $url -> ${targetFile.absolutePath}")

        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 60000
                instanceFollowRedirects = true

                // Resume support
                if (partFile.exists() && partFile.length() > 0) {
                    setRequestProperty("Range", "bytes=${partFile.length()}-")
                    LuiLogger.i(TAG, "Resuming from ${partFile.length()} bytes")
                }
            }

            val responseCode = conn.responseCode

            if (responseCode !in listOf(200, 206)) {
                val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { "unknown" }
                conn.disconnect()
                emit(Progress(0, 0, 0, error = "HTTP $responseCode: $err"))
                return@flow
            }

            val isResume = responseCode == 206
            val existingBytes = if (isResume) partFile.length() else 0L
            val contentLength = conn.contentLengthLong
            val totalBytes = if (isResume) existingBytes + contentLength else contentLength

            if (!isResume && partFile.exists()) {
                partFile.delete()
            }

            // Check available space (need totalBytes + 50MB margin)
            val usableSpace = targetDir.usableSpace
            val needed = totalBytes - existingBytes + 50_000_000
            if (usableSpace < needed) {
                conn.disconnect()
                emit(Progress(0, totalBytes, 0, error = "Not enough space. Need ${needed / 1_048_576}MB, have ${usableSpace / 1_048_576}MB"))
                return@flow
            }

            LuiLogger.i(TAG, "Download: total=${totalBytes}, resume=$isResume, existing=$existingBytes")

            val input = conn.inputStream
            val output = java.io.FileOutputStream(partFile, isResume)
            val buffer = ByteArray(65536)
            var bytesRead: Int
            var downloaded = existingBytes
            var lastEmitTime = System.currentTimeMillis()
            var lastEmitBytes = downloaded
            val startTime = System.currentTimeMillis()

            emit(Progress(downloaded, totalBytes, 0))

            while (input.read(buffer).also { bytesRead = it } > 0) {
                if (!coroutineContext.isActive) {
                    LuiLogger.i(TAG, "Download cancelled at $downloaded bytes")
                    output.close()
                    input.close()
                    conn.disconnect()
                    return@flow
                }

                output.write(buffer, 0, bytesRead)
                downloaded += bytesRead

                val now = System.currentTimeMillis()
                val elapsed = now - lastEmitTime
                if (elapsed >= 500) {
                    val speed = ((downloaded - lastEmitBytes) * 1000) / elapsed.coerceAtLeast(1)
                    emit(Progress(downloaded, totalBytes, speed))
                    lastEmitTime = now
                    lastEmitBytes = downloaded
                }
            }

            output.flush()
            output.close()
            input.close()
            conn.disconnect()

            // Verify minimum size
            if (partFile.length() < 1_000_000) {
                partFile.delete()
                emit(Progress(0, totalBytes, 0, error = "Download too small — file may be corrupt"))
                return@flow
            }

            // Rename .part to final
            if (targetFile.exists()) targetFile.delete()
            partFile.renameTo(targetFile)

            val totalTime = (System.currentTimeMillis() - startTime) / 1000
            val avgSpeed = if (totalTime > 0) (downloaded - existingBytes) / totalTime else 0
            LuiLogger.i(TAG, "Download complete: ${targetFile.length()} bytes in ${totalTime}s (avg ${avgSpeed / 1024} KB/s)")

            emit(Progress(downloaded, totalBytes, avgSpeed, done = true))

        } catch (e: Exception) {
            LuiLogger.e(TAG, "Download failed: ${e.message}", e)
            emit(Progress(partFile.length(), 0, 0, error = e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Check if a partial download exists and return its size.
     */
    fun getPartialDownloadSize(context: Context, filename: String = LocalModel.MODEL_FILENAME): Long {
        val partFile = File(context.filesDir, "models/$filename.part")
        return if (partFile.exists()) partFile.length() else 0
    }

    /**
     * Delete partial download.
     */
    fun deletePartialDownload(context: Context, filename: String = LocalModel.MODEL_FILENAME) {
        File(context.filesDir, "models/$filename.part").delete()
    }

    /**
     * Delete the downloaded model.
     */
    fun deleteModel(context: Context, filename: String = LocalModel.MODEL_FILENAME) {
        File(context.filesDir, "models/$filename").delete()
        File(context.filesDir, "models/$filename.part").delete()
    }
}
