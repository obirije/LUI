package com.lui.app.llm

import com.lui.app.data.ChatMessage
import com.lui.app.data.SecureKeyStore
import com.lui.app.helper.LuiLogger
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

/**
 * Structured tool call returned by the LLM (native function calling).
 */
data class LlmToolCall(
    val name: String,
    val args: Map<String, String>,
    // Provider-specific IDs needed for sending tool results back
    val callId: String? = null
)

/**
 * Result of a generation: either streaming text tokens, or a tool call.
 */
sealed class GenerationResult {
    /** A text token to display (emitted during streaming) */
    data class TextToken(val token: String) : GenerationResult()
    /** The LLM wants to call a tool (emitted at end of stream) */
    data class ToolUse(val toolCall: LlmToolCall) : GenerationResult()
    /** Generation complete with final text (no tool call) */
    data class Done(val text: String) : GenerationResult()
}

class CloudModel(private val keyStore: SecureKeyStore, private val appContext: android.content.Context) : LlmProvider {

    companion object {
        private const val TAG = "CloudModel"
    }

    override val isReady: Boolean
        get() = keyStore.hasCloudConfigured

    override fun generateStreaming(userMessage: String): Flow<String> =
        generateStreaming(userMessage, emptyList())

    fun generateStreaming(userMessage: String, history: List<ChatMessage>): Flow<String> = flow {
        generateWithTools(userMessage, history).collect { result ->
            when (result) {
                is GenerationResult.TextToken -> emit(result.token)
                is GenerationResult.Done -> {} // already emitted via tokens
                is GenerationResult.ToolUse -> {} // handled by caller
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Generate with native tool use. Emits GenerationResult items.
     * Caller should handle ToolUse by executing the tool and calling continueWithToolResult.
     */
    fun generateWithTools(
        userMessage: String,
        history: List<ChatMessage>,
        toolResults: List<Pair<LlmToolCall, String>>? = null
    ): Flow<GenerationResult> = flow {
        val provider = keyStore.selectedProvider ?: throw Exception("No provider selected")
        val apiKey = keyStore.getApiKey(provider) ?: throw Exception("No API key")

        when (provider) {
            CloudProvider.GEMINI -> emitAll(generateGemini(apiKey, provider.defaultModel, userMessage, history, toolResults))
            CloudProvider.CLAUDE -> emitAll(generateClaude(apiKey, provider.defaultModel, userMessage, history, toolResults))
            CloudProvider.OPENAI -> emitAll(generateOpenAI(apiKey, provider.defaultModel, userMessage, history, toolResults))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<T>.emitAll(flow: Flow<T>) {
        flow.collect { emit(it) }
    }

    // ════════════════════════════════════════════
    //  GEMINI — Native Function Calling
    // ════════════════════════════════════════════

    private fun generateGemini(
        key: String, model: String, message: String,
        history: List<ChatMessage>,
        toolResults: List<Pair<LlmToolCall, String>>?
    ): Flow<GenerationResult> = flow {
        val url = URL("${CloudProvider.GEMINI.endpoint}/$model:streamGenerateContent?alt=sse&key=$key")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }

        val contents = JSONArray()

        // System instruction as first user/model pair
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", SystemPrompt.buildNativeToolPrompt(appContext)))))
        contents.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", "Understood. I'll use the provided tools when appropriate."))))

        // History
        for (msg in history.takeLast(10)) {
            val role = if (msg.sender == ChatMessage.Sender.USER) "user" else "model"
            contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", msg.text))))
        }

        // Tool results from previous round
        if (toolResults != null) {
            for ((toolCall, result) in toolResults) {
                // Model's function call
                val fcPart = JSONObject().put("functionCall",
                    JSONObject().put("name", toolCall.name).put("args", JSONObject(toolCall.args as Map<*, *>)))
                contents.put(JSONObject().put("role", "model").put("parts", JSONArray().put(fcPart)))

                // Function response
                val frPart = JSONObject().put("functionResponse",
                    JSONObject().put("name", toolCall.name)
                        .put("response", JSONObject().put("result", result)))
                contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(frPart)))
            }
        } else {
            // Current message
            contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", message))))
        }

        val body = JSONObject()
            .put("contents", contents)
            .put("tools", ToolRegistry.toGeminiTools())

        conn.outputStream.write(body.toString().toByteArray())

        try {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val fullText = StringBuilder()
            var pendingToolCall: LlmToolCall? = null
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val json = JSONObject(data)
                    LuiLogger.d("GEMINI_RAW", data.take(300))
                    val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: continue
                    val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: continue

                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        if (part.has("functionCall")) {
                            val fc = part.getJSONObject("functionCall")
                            val name = fc.getString("name")
                            val args = mutableMapOf<String, String>()
                            fc.optJSONObject("args")?.let { a ->
                                for (k in a.keys()) args[k] = a.optString(k, "")
                            }
                            pendingToolCall = LlmToolCall(name, args)
                            LuiLogger.i("GEMINI", "Function call: $name $args")
                        } else if (part.has("text")) {
                            val token = part.getString("text")
                            if (token.isNotEmpty()) {
                                fullText.append(token)
                                emit(GenerationResult.TextToken(token))
                            }
                        }
                    }
                } catch (e: Exception) {
                    LuiLogger.e("GEMINI", "Parse chunk error", e)
                }
            }
            reader.close()

            if (pendingToolCall != null) {
                emit(GenerationResult.ToolUse(pendingToolCall!!))
            } else {
                emit(GenerationResult.Done(fullText.toString()))
            }
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Gemini streaming error: ${e.message}", e)
            try { val err = conn.errorStream?.bufferedReader()?.readText(); LuiLogger.e(TAG, "API error: ${err?.take(200)}") } catch (_: Exception) {}
            throw e
        } finally {
            conn.disconnect()
        }
    }

    // ════════════════════════════════════════════
    //  CLAUDE — Native Tool Use
    // ════════════════════════════════════════════

    private fun generateClaude(
        key: String, model: String, message: String,
        history: List<ChatMessage>,
        toolResults: List<Pair<LlmToolCall, String>>?
    ): Flow<GenerationResult> = flow {
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

        // Tool results from previous round
        if (toolResults != null) {
            for ((toolCall, result) in toolResults) {
                // Assistant's tool_use
                val toolUseBlock = JSONObject()
                    .put("type", "tool_use")
                    .put("id", toolCall.callId ?: "tool_${toolCall.name}")
                    .put("name", toolCall.name)
                    .put("input", JSONObject(toolCall.args as Map<*, *>))
                messages.put(JSONObject().put("role", "assistant")
                    .put("content", JSONArray().put(toolUseBlock)))

                // User's tool_result
                val toolResultBlock = JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", toolCall.callId ?: "tool_${toolCall.name}")
                    .put("content", result)
                messages.put(JSONObject().put("role", "user")
                    .put("content", JSONArray().put(toolResultBlock)))
            }
        } else {
            messages.put(JSONObject().put("role", "user").put("content", message))
        }

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", SystemPrompt.buildNativeToolPrompt(appContext))
            .put("messages", messages)
            .put("tools", ToolRegistry.toClaudeTools())
            .put("stream", true)

        conn.outputStream.write(body.toString().toByteArray())

        try {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val fullText = StringBuilder()
            var pendingToolCall: LlmToolCall? = null
            var currentToolName: String? = null
            var currentToolId: String? = null
            val toolArgsBuilder = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val json = JSONObject(data)
                    val type = json.optString("type")

                    when (type) {
                        "content_block_start" -> {
                            val block = json.optJSONObject("content_block")
                            if (block?.optString("type") == "tool_use") {
                                currentToolName = block.optString("name")
                                currentToolId = block.optString("id")
                                toolArgsBuilder.clear()
                            }
                        }
                        "content_block_delta" -> {
                            val delta = json.optJSONObject("delta")
                            if (delta?.optString("type") == "input_json_delta") {
                                toolArgsBuilder.append(delta.optString("partial_json", ""))
                            } else if (delta?.optString("type") == "text_delta") {
                                val token = delta.optString("text", "")
                                if (token.isNotEmpty()) {
                                    fullText.append(token)
                                    emit(GenerationResult.TextToken(token))
                                }
                            }
                        }
                        "content_block_stop" -> {
                            if (currentToolName != null) {
                                val args = mutableMapOf<String, String>()
                                try {
                                    val argsJson = JSONObject(toolArgsBuilder.toString())
                                    for (k in argsJson.keys()) args[k] = argsJson.optString(k, "")
                                } catch (_: Exception) {}
                                pendingToolCall = LlmToolCall(currentToolName!!, args, currentToolId)
                                LuiLogger.i("CLAUDE", "Tool use: $currentToolName $args")
                                currentToolName = null
                                currentToolId = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    LuiLogger.e("CLAUDE", "Parse chunk error", e)
                }
            }
            reader.close()

            if (pendingToolCall != null) {
                emit(GenerationResult.ToolUse(pendingToolCall!!))
            } else {
                emit(GenerationResult.Done(fullText.toString()))
            }
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Claude streaming error: ${e.message}", e)
            try { val err = conn.errorStream?.bufferedReader()?.readText(); LuiLogger.e(TAG, "API error: ${err?.take(200)}") } catch (_: Exception) {}
            throw e
        } finally {
            conn.disconnect()
        }
    }

    // ════════════════════════════════════════════
    //  OPENAI — Native Function Calling
    // ════════════════════════════════════════════

    private fun generateOpenAI(
        key: String, model: String, message: String,
        history: List<ChatMessage>,
        toolResults: List<Pair<LlmToolCall, String>>?
    ): Flow<GenerationResult> = flow {
        val url = URL(CloudProvider.OPENAI.endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            doOutput = true
        }

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SystemPrompt.buildNativeToolPrompt(appContext)))
        for (msg in history.takeLast(10)) {
            val role = if (msg.sender == ChatMessage.Sender.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", msg.text))
        }

        // Tool results from previous round
        if (toolResults != null) {
            for ((toolCall, result) in toolResults) {
                // Assistant message with tool_calls
                val toolCallObj = JSONObject()
                    .put("id", toolCall.callId ?: "call_${toolCall.name}")
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", toolCall.name)
                        .put("arguments", JSONObject(toolCall.args as Map<*, *>).toString()))
                messages.put(JSONObject().put("role", "assistant")
                    .put("tool_calls", JSONArray().put(toolCallObj)))

                // Tool result
                messages.put(JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", toolCall.callId ?: "call_${toolCall.name}")
                    .put("content", result))
            }
        } else {
            messages.put(JSONObject().put("role", "user").put("content", message))
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", ToolRegistry.toOpenAITools())
            .put("stream", true)
            .put("max_tokens", 1024)

        conn.outputStream.write(body.toString().toByteArray())

        try {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val fullText = StringBuilder()
            var toolCallName = StringBuilder()
            var toolCallArgs = StringBuilder()
            var toolCallId: String? = null
            var hasToolCall = false
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val json = JSONObject(data)
                    val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val delta = choice.optJSONObject("delta") ?: continue

                    // Text content
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        fullText.append(content)
                        emit(GenerationResult.TextToken(content))
                    }

                    // Tool calls
                    val toolCalls = delta.optJSONArray("tool_calls")
                    if (toolCalls != null && toolCalls.length() > 0) {
                        hasToolCall = true
                        val tc = toolCalls.getJSONObject(0)
                        tc.optString("id", "").takeIf { it.isNotEmpty() }?.let { toolCallId = it }
                        val func = tc.optJSONObject("function")
                        func?.optString("name", "")?.takeIf { it.isNotEmpty() }?.let { toolCallName.append(it) }
                        func?.optString("arguments", "")?.let { toolCallArgs.append(it) }
                    }
                } catch (e: Exception) {
                    LuiLogger.e("OPENAI", "Parse chunk error", e)
                }
            }
            reader.close()

            if (hasToolCall && toolCallName.isNotEmpty()) {
                val args = mutableMapOf<String, String>()
                try {
                    val argsJson = JSONObject(toolCallArgs.toString())
                    for (k in argsJson.keys()) args[k] = argsJson.optString(k, "")
                } catch (_: Exception) {}
                val tc = LlmToolCall(toolCallName.toString(), args, toolCallId)
                LuiLogger.i("OPENAI", "Tool call: ${tc.name} ${tc.args}")
                emit(GenerationResult.ToolUse(tc))
            } else {
                emit(GenerationResult.Done(fullText.toString()))
            }
        } catch (e: Exception) {
            LuiLogger.e(TAG, "OpenAI streaming error: ${e.message}", e)
            try { val err = conn.errorStream?.bufferedReader()?.readText(); LuiLogger.e(TAG, "API error: ${err?.take(200)}") } catch (_: Exception) {}
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
