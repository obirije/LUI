package com.lui.app.audio

import android.content.Context
import com.lui.app.data.SecureKeyStore
import com.lui.app.helper.LuiLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for the native ACE-Step HTTP API (ACE-Step-1.5).
 *
 * The server exposes an async pipeline:
 *   1. POST {base}/release_task        → returns task_id
 *   2. POST {base}/query_result (poll) → status 0 running / 1 ok / 2 failed
 *   3. GET  {base}/v1/audio?path=…     → raw audio bytes
 *
 * The endpoint configured in [SecureKeyStore.aceStepEndpoint] should be the
 * base URL (e.g. `http://192.168.1.105:8001`) — paths are appended here.
 * See docs: https://github.com/ace-step/ACE-Step-1.5/blob/main/docs/en/API.md
 */
object AceStepClient {

    private const val TAG = "AceStep"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 60_000

    // Polling: RTX 3090 generates in <10s, so check every 1.5s up to 2 min.
    private const val POLL_INTERVAL_MS = 1500L
    private const val POLL_TIMEOUT_MS = 120_000L

    suspend fun generate(
        context: Context,
        prompt: String,
        durationSec: Int = 45
    ): Result<File> = withContext(Dispatchers.IO) {
        val store = SecureKeyStore(context)
        val base = store.aceStepEndpoint?.trimEnd('/')
            ?: return@withContext Result.failure(IllegalStateException("ACE-Step endpoint not configured"))
        val apiKey = store.aceStepApiKey

        try {
            val taskId = releaseTask(base, apiKey, prompt, durationSec)
                ?: return@withContext Result.failure(RuntimeException("release_task returned no task_id"))
            LuiLogger.i(TAG, "Task queued: $taskId")

            val audioPath = pollForResult(base, apiKey, taskId)
                ?: return@withContext Result.failure(RuntimeException("generation failed or timed out"))

            val libraryDir = File(context.filesDir, "generated_music").apply { mkdirs() }
            val outFile = File(libraryDir, "track_${System.currentTimeMillis()}.mp3")
            downloadAudio(base, apiKey, audioPath, outFile)

            if (outFile.length() < 1024) {
                outFile.delete()
                return@withContext Result.failure(RuntimeException("Audio file too small (${outFile.length()}b)"))
            }
            LuiLogger.i(TAG, "Downloaded ${outFile.length() / 1024}kb for: ${prompt.take(60)}")
            Result.success(outFile)
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Generate failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** POST /release_task — returns the task_id or null on failure. */
    private fun releaseTask(base: String, apiKey: String?, prompt: String, duration: Int): String? {
        val url = URL("$base/release_task")
        // Request ~18% extra because the server's AudioSaver trims the final
        // 15% DiT fade-tail. Net result: the user gets ~`duration` seconds of
        // non-fading audio on loop.
        val requestedDuration = (duration / (1.0 - 0.15)).coerceAtMost(600.0)
        val body = JSONObject()
            .put("task_type", "text2music")
            .put("prompt", prompt)
            .put("audio_duration", requestedDuration)
            .put("audio_format", "mp3")
            .put("inference_steps", 30)         // quality over turbo default
            .put("guidance_scale", 7.0)
            .put("thinking", false)             // avoid LM-planned outro
            .put("use_random_seed", true)
            .put("batch_size", 1)
            .toString()

        val conn = openPostJson(url, apiKey)
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
            LuiLogger.e(TAG, "release_task HTTP $code: ${err?.take(200)}")
            conn.disconnect(); return null
        }
        val response = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val envelope = JSONObject(response)
        val data = envelope.optJSONObject("data") ?: return null
        return data.optString("task_id").takeIf { it.isNotBlank() }
    }

    /** Polls /query_result until the task finishes; returns the audio path
     *  (e.g. `/v1/audio?path=/tmp/abc.mp3`) on success, null on timeout or
     *  failure. */
    private suspend fun pollForResult(base: String, apiKey: String?, taskId: String): String? {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        val url = URL("$base/query_result")

        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)

            val body = JSONObject()
                .put("task_id_list", JSONArray().put(taskId))
                .toString()

            val conn = openPostJson(url, apiKey)
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode !in 200..299) {
                    LuiLogger.w(TAG, "query_result HTTP ${conn.responseCode}")
                    continue
                }
                val resp = JSONObject(conn.inputStream.bufferedReader().readText())
                val arr = resp.optJSONArray("data") ?: continue
                if (arr.length() == 0) continue

                val item = arr.getJSONObject(0)
                when (item.optInt("status", -1)) {
                    1 -> {
                        // result is a JSON STRING that parses to an ARRAY of per-song objects
                        val resultStr = item.optString("result").takeIf { it.isNotBlank() } ?: return null
                        val resultArr = JSONArray(resultStr)
                        if (resultArr.length() == 0) return null
                        return resultArr.getJSONObject(0).optString("file").takeIf { it.isNotBlank() }
                    }
                    2 -> {
                        LuiLogger.e(TAG, "Task $taskId failed: ${item.optString("error")}")
                        return null
                    }
                    else -> { /* 0 = still running; keep polling */ }
                }
            } finally {
                conn.disconnect()
            }
        }
        LuiLogger.e(TAG, "Polling timed out after ${POLL_TIMEOUT_MS}ms for task $taskId")
        return null
    }

    /** GET {base}{audioPath} — the `file` from query_result is already a
     *  server-relative path with the query string, so concat and fetch. */
    private fun downloadAudio(base: String, apiKey: String?, audioPath: String, dest: File) {
        val url = if (audioPath.startsWith("http")) URL(audioPath)
                  else URL(base + ensureLeadingSlash(audioPath))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            apiKey?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw RuntimeException("audio download HTTP ${conn.responseCode}")
        }
        conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
    }

    private fun openPostJson(url: URL, apiKey: String?): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            apiKey?.let { setRequestProperty("Authorization", "Bearer $it") }
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

    private fun ensureLeadingSlash(s: String) = if (s.startsWith("/")) s else "/$s"

    // Unused but kept for future inline-URL responses / mock servers.
    @Suppress("unused")
    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Lightweight health check — tries GET /health, returns true on 200. */
    suspend fun ping(context: Context): Boolean = withContext(Dispatchers.IO) {
        val base = SecureKeyStore(context).aceStepEndpoint?.trimEnd('/') ?: return@withContext false
        try {
            val conn = (URL("$base/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (e: Exception) {
            LuiLogger.w(TAG, "ping failed: ${e.message}"); false
        }
    }
}
