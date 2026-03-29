package com.lui.app.llm

import android.util.Log
import com.lui.app.data.ChatMessage
import com.lui.app.data.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class CloudModel(private val keyStore: SecureKeyStore) : LlmProvider {

    companion object {
        private const val TAG = "CloudModel"
    }

    override val isReady: Boolean
        get() = keyStore.hasCloudConfigured

    override fun generateStreaming(userMessage: String): Flow<String> =
        generateStreaming(userMessage, emptyList())

    fun generateStreaming(userMessage: String, history: List<ChatMessage>): Flow<String> = flow {
        val provider = keyStore.selectedProvider ?: throw Exception("No provider selected")
        val apiKey = keyStore.getApiKey(provider) ?: throw Exception("No API key")

        val conn = when (provider) {
            CloudProvider.GEMINI -> connectGemini(apiKey, provider.defaultModel, userMessage, history)
            CloudProvider.CLAUDE -> connectClaude(apiKey, provider.defaultModel, userMessage, history)
            CloudProvider.OPENAI -> connectOpenAI(apiKey, provider.defaultModel, userMessage, history)
        }

        try {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                val token = when (provider) {
                    CloudProvider.GEMINI -> parseGeminiChunk(data)
                    CloudProvider.CLAUDE -> parseClaudeChunk(data)
                    CloudProvider.OPENAI -> parseOpenAIChunk(data)
                }
                if (token != null && token.isNotEmpty()) emit(token)
            }
            reader.close()
        } catch (e: Exception) {
            Log.e(TAG, "Streaming error", e)
            // Try to read error body
            try {
                val error = conn.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "API error: $error")
            } catch (_: Exception) {}
            throw e
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    // ---- Gemini ----

    private fun connectGemini(key: String, model: String, message: String, history: List<ChatMessage>): HttpURLConnection {
        val url = URL("${CloudProvider.GEMINI.endpoint}/$model:streamGenerateContent?alt=sse&key=$key")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }

        val contents = JSONArray()

        // System instruction
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", SystemPrompt.CLOUD_PROMPT))))
        contents.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", "Understood."))))

        // History
        for (msg in history.takeLast(10)) {
            val role = if (msg.sender == ChatMessage.Sender.USER) "user" else "model"
            contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", msg.text))))
        }

        // Current message
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", message))))

        val body = JSONObject().put("contents", contents)
        conn.outputStream.write(body.toString().toByteArray())
        return conn
    }

    private fun parseGeminiChunk(data: String): String? {
        return try {
            JSONObject(data)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)
                ?.optString("text")
        } catch (e: Exception) { null }
    }

    // ---- Claude ----

    private fun connectClaude(key: String, model: String, message: String, history: List<ChatMessage>): HttpURLConnection {
        val url = URL(CloudProvider.CLAUDE.endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", key)
            setRequestProperty("anthropic-version", "2023-06-01")
            doOutput = true
        }

        val messages = JSONArray()
        for (msg in history.takeLast(10)) {
            val role = if (msg.sender == ChatMessage.Sender.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", msg.text))
        }
        messages.put(JSONObject().put("role", "user").put("content", message))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", SystemPrompt.CLOUD_PROMPT)
            .put("messages", messages)
            .put("stream", true)

        conn.outputStream.write(body.toString().toByteArray())
        return conn
    }

    private fun parseClaudeChunk(data: String): String? {
        return try {
            val json = JSONObject(data)
            if (json.optString("type") == "content_block_delta") {
                json.optJSONObject("delta")?.optString("text")
            } else null
        } catch (e: Exception) { null }
    }

    // ---- OpenAI ----

    private fun connectOpenAI(key: String, model: String, message: String, history: List<ChatMessage>): HttpURLConnection {
        val url = URL(CloudProvider.OPENAI.endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            doOutput = true
        }

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SystemPrompt.CLOUD_PROMPT))
        for (msg in history.takeLast(10)) {
            val role = if (msg.sender == ChatMessage.Sender.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", msg.text))
        }
        messages.put(JSONObject().put("role", "user").put("content", message))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("max_tokens", 1024)

        conn.outputStream.write(body.toString().toByteArray())
        return conn
    }

    private fun parseOpenAIChunk(data: String): String? {
        return try {
            JSONObject(data)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?.optString("content")
        } catch (e: Exception) { null }
    }
}
