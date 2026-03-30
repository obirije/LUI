package com.lui.app.helper

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * File-based logger for LUI. Writes timestamped events to a log file on the device.
 * Designed for development debugging — captures the full conversation loop:
 * user input, interceptor decisions, LLM routing, tool execution, voice events, errors.
 *
 * Log file: /data/data/com.lui.app.debug/files/lui_log.txt
 * Pull with: adb shell run-as com.lui.app.debug cat files/lui_log.txt
 * Or:        adb exec-out run-as com.lui.app.debug cat files/lui_log.txt > lui_log.txt
 */
object LuiLogger {

    private const val TAG = "LUI"
    private const val FILE_NAME = "lui_log.txt"
    private const val MAX_SIZE_BYTES = 5 * 1024 * 1024 // 5MB, then rotate

    private var logFile: File? = null
    private val queue = ConcurrentLinkedQueue<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var initialized = false

    fun init(context: Context) {
        logFile = File(context.filesDir, FILE_NAME)
        initialized = true
        // Start background writer
        thread(isDaemon = true, name = "LuiLogger") {
            while (true) {
                try {
                    val batch = mutableListOf<String>()
                    while (queue.isNotEmpty()) {
                        queue.poll()?.let { batch.add(it) }
                    }
                    if (batch.isNotEmpty()) {
                        writeBatch(batch)
                    }
                    Thread.sleep(500)
                } catch (_: InterruptedException) { break }
                catch (e: Exception) { Log.e(TAG, "Logger write failed", e) }
            }
        }
        i("SYSTEM", "=== LUI Logger initialized ===")
    }

    // ── Log levels ──

    fun d(category: String, message: String) = log("D", category, message)
    fun i(category: String, message: String) = log("I", category, message)
    fun w(category: String, message: String) = log("W", category, message)
    fun e(category: String, message: String, throwable: Throwable? = null) {
        val rawMsg = if (throwable != null) "$message | ${throwable.message}" else message
        // Sanitize API keys from error messages
        val msg = rawMsg.replace(Regex("[?&]key=[A-Za-z0-9_-]+"), "?key=***")
            .replace(Regex("x-api-key:\\s*[A-Za-z0-9_-]+"), "x-api-key: ***")
            .replace(Regex("Bearer\\s+[A-Za-z0-9_.-]+"), "Bearer ***")
        log("E", category, msg)
    }

    // ── Semantic logging methods ──

    fun userInput(text: String, source: String = "text") {
        i("INPUT", "[$source] $text")
    }

    fun voicePartial(text: String) {
        d("VOICE", "partial: $text")
    }

    fun voiceFinal(text: String) {
        i("VOICE", "final: $text")
    }

    fun voiceState(state: String) {
        d("VOICE", "state → $state")
    }

    fun interceptorMatch(tool: String, params: Map<String, String>, source: String = "keyword") {
        i("INTERCEPT", "[$source] matched → $tool $params")
    }

    fun interceptorMiss(input: String) {
        d("INTERCEPT", "no match → sending to LLM: ${input.take(80)}")
    }

    fun llmRoute(provider: String, cloudFirst: Boolean, cloudReady: Boolean, localReady: Boolean) {
        i("LLM", "route: provider=$provider cloudFirst=$cloudFirst cloudReady=$cloudReady localReady=$localReady")
    }

    fun llmStreaming(token: String) {
        // Only log periodically to avoid flooding
        d("LLM", "streaming: ...${token.takeLast(60)}")
    }

    fun llmResponse(fullResponse: String) {
        // Log full response in chunks to capture tool calls embedded in text
        i("LLM", "response (${fullResponse.length} chars): ${fullResponse.take(500)}")
        if (fullResponse.length > 500) {
            i("LLM", "response cont: ${fullResponse.substring(500).take(500)}")
        }
    }

    fun toolExecute(tool: String, params: Map<String, String>) {
        i("TOOL", "execute: $tool $params")
    }

    fun toolResult(tool: String, success: Boolean, message: String) {
        val level = if (success) "I" else "W"
        log(level, "TOOL", "result: $tool → ${if (success) "OK" else "FAIL"}: $message")
    }

    fun toolChain(step: Int, tool: String, result: String) {
        i("CHAIN", "step $step: $tool → ${result.take(100)}")
    }

    fun confirmation(tool: String, confirmed: Boolean) {
        i("CONFIRM", "$tool → ${if (confirmed) "YES" else "NO"}")
    }

    fun permission(permission: String, granted: Boolean) {
        i("PERM", "$permission → ${if (granted) "granted" else "denied"}")
    }

    fun ttsSpeak(text: String, cloud: Boolean) {
        i("TTS", "[${if (cloud) "cloud" else "local"}] ${text.take(100)}")
    }

    fun error(category: String, message: String, throwable: Throwable? = null) {
        e(category, message, throwable)
    }

    // ── Retrieve logs for display ──

    fun getRecentLogs(lines: Int = 100): String {
        val file = logFile ?: return "Logger not initialized."
        if (!file.exists()) return "No logs yet."
        return try {
            file.readLines().takeLast(lines).joinToString("\n")
        } catch (e: Exception) { "Error reading logs: ${e.message}" }
    }

    fun getLogFilePath(): String {
        return logFile?.absolutePath ?: "not initialized"
    }

    fun clearLogs() {
        try { logFile?.writeText("") } catch (_: Exception) {}
        i("SYSTEM", "=== Logs cleared ===")
    }

    // ── Internal ──

    private fun log(level: String, category: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp [$level/$category] $message"

        // Also log to Logcat
        when (level) {
            "D" -> Log.d(TAG, "[$category] $message")
            "I" -> Log.i(TAG, "[$category] $message")
            "W" -> Log.w(TAG, "[$category] $message")
            "E" -> Log.e(TAG, "[$category] $message")
        }

        if (initialized) queue.add(line)
    }

    private fun writeBatch(lines: List<String>) {
        val file = logFile ?: return

        // Rotate if too large
        if (file.exists() && file.length() > MAX_SIZE_BYTES) {
            val backup = File(file.parent, "lui_log_prev.txt")
            backup.delete()
            file.renameTo(backup)
        }

        FileWriter(file, true).use { writer ->
            for (line in lines) {
                writer.appendLine(line)
            }
        }
    }
}
