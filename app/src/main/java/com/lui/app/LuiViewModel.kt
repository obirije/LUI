package com.lui.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lui.app.data.ChatMessage
import com.lui.app.data.ChatMessage.Sender
import com.lui.app.data.ChatRepository
import com.lui.app.helper.LuiLogger
import com.lui.app.helper.PermissionHelper
import com.lui.app.helper.WallpaperHelper
import com.lui.app.interceptor.ActionExecutor
import com.lui.app.interceptor.Interceptor
import com.lui.app.interceptor.actions.ActionResult
import com.lui.app.llm.CloudModel
import com.lui.app.llm.LlmRouter
import com.lui.app.llm.LocalModel
import com.lui.app.llm.ModelManager
import com.lui.app.llm.SystemPrompt
import com.lui.app.voice.VoiceEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class LuiViewModel(application: Application) : AndroidViewModel(application) {

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _scrollToBottom = MutableLiveData<Unit>()
    val scrollToBottom: LiveData<Unit> = _scrollToBottom

    private val _llmStatus = MutableLiveData<String>()
    val llmStatus: LiveData<String> = _llmStatus

    // Permission request: emitted when an action needs a runtime permission
    private val _permissionRequest = MutableLiveData<PermissionHelper.PermissionRequest?>()
    val permissionRequest: LiveData<PermissionHelper.PermissionRequest?> = _permissionRequest
    private var pendingToolCall: com.lui.app.data.ToolCall? = null

    // Confirmation for destructive actions (SMS, calls)
    private var pendingConfirmation: com.lui.app.data.ToolCall? = null

    // Last action result for "copy that" / "share that"
    var lastActionResult: String? = null
        private set
    // Last executed tool for undo
    private var lastExecutedTool: com.lui.app.data.ToolCall? = null

    private val localModel = LocalModel(application)
    private val keyStore = com.lui.app.data.SecureKeyStore(application)
    private val cloudModel = CloudModel(keyStore, application)
    private val router = LlmRouter(localModel, cloudModel, keyStore)
    private val chatRepo = ChatRepository(application)
    val voiceEngine = VoiceEngine(application)
    private var generationJob: Job? = null

    init {
        LuiLogger.init(application)

        // Load chat history then show welcome
        viewModelScope.launch {
            val history = chatRepo.getHistory()
            if (history.isNotEmpty()) {
                _messages.value = history
                _scrollToBottom.value = Unit
            } else {
                addMessage(ChatMessage(
                    text = application.getString(R.string.welcome_message),
                    sender = Sender.WELCOME
                ))
            }
        }

        val prefs = application.getSharedPreferences("lui_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("wallpaper_set", false)) {
            WallpaperHelper.setLuiWallpaper(application)
            prefs.edit().putBoolean("wallpaper_set", true).apply()
        }

        // Initialize LLM
        viewModelScope.launch {
            _llmStatus.value = "Checking for model..."
            val found = ModelManager.ensureModel(application)
            if (found) {
                _llmStatus.value = "Loading model..."
                val result = localModel.initialize()
                if (result.isSuccess) {
                    // Check if cloud should take priority
                    _llmStatus.value = if (keyStore.isCloudFirst && cloudModel.isReady) "cloud" else "ready"
                } else {
                    _llmStatus.value = "Model failed to load. Using keyword mode."
                    Log.e("LuiVM", "Model init failed", result.exceptionOrNull())
                }
            } else if (cloudModel.isReady) {
                _llmStatus.value = "cloud"
            } else {
                _llmStatus.value = "no_model"
                addMessage(ChatMessage(
                    text = "No LLM model found. I'm in keyword mode — I can handle flashlight, alarms, timers, apps, calls, volume, brightness, DND, and rotation. For full AI chat, sideload the model or configure a cloud API key (tap the status dot).",
                    sender = Sender.LUI
                ))
            }
        }

        // Initialize voice engine
        viewModelScope.launch {
            ModelManager.ensureVoiceModels(application)
            ModelManager.ensureTtsModel(application)
            voiceEngine.initialize(keyStore)
        }

        // Collect voice transcription events
        viewModelScope.launch {
            voiceEngine.transcription.collect { partial ->
                updateVoiceTranscript(partial)
            }
        }

        viewModelScope.launch {
            voiceEngine.finalTranscript.collect { final ->
                finalizeVoiceInput(final)
            }
        }

        // When conversation mode auto-restarts listening
        viewModelScope.launch {
            voiceEngine.autoListenStarted.collect {
                voiceMessageActive = true
                voiceBubbleAdded = false
                // Don't add bubble — wait for actual speech
            }
        }
    }

    fun handleUserInput(text: String) {
        if (text.isBlank()) return
        LuiLogger.userInput(text)

        // Special commands
        val lower = text.trim().lowercase()
        if (lower == "clear" || lower == "clear chat" || lower == "clear history") {
            viewModelScope.launch {
                chatRepo.clearHistory()
                _messages.value = listOf(ChatMessage(
                    text = getApplication<Application>().getString(R.string.welcome_message),
                    sender = Sender.WELCOME
                ))
            }
            return
        }

        // Handle pending confirmation (yes/no for destructive actions)
        if (pendingConfirmation != null) {
            val confirmed = lower.matches(Regex("^(yes|yeah|yep|yup|y|sure|ok|okay|do it|go|send|confirm|go ahead)$"))
            val denied = lower.matches(Regex("^(no|nah|nope|n|cancel|nevermind|never mind|stop|don't)$"))
            if (confirmed) {
                LuiLogger.confirmation(pendingConfirmation!!.tool, true)
                addMessage(ChatMessage(text = text.trim(), sender = Sender.USER))
                val toolCall = pendingConfirmation!!
                pendingConfirmation = null
                executeToolCall(toolCall)
                return
            } else if (denied) {
                LuiLogger.confirmation(pendingConfirmation!!.tool, false)
                pendingConfirmation = null
                addMessage(ChatMessage(text = text.trim(), sender = Sender.USER))
                addMessage(ChatMessage(text = "Cancelled.", sender = Sender.LUI))
                if (voiceEngine.conversationMode) voiceEngine.speak("Cancelled.")
                return
            }
            // If neither yes nor no, clear pending and process as normal input
            pendingConfirmation = null
        }

        addMessage(ChatMessage(text = text.trim(), sender = Sender.USER))

        // When a cloud model is available, the LLM orchestrates everything
        if (cloudModel.isReady) {
            LuiLogger.llmRoute(router.activeProviderName, keyStore.isCloudFirst, cloudModel.isReady, localModel.isReady)
            generateWithLlm(text)
            return
        }

        // Local/offline: interceptor handles what it can, local LLM gets the rest
        val toolCall = Interceptor.parse(text)
        if (toolCall != null) {
            LuiLogger.interceptorMatch(toolCall.tool, toolCall.params)
            executeToolCall(toolCall)
            return
        }
        LuiLogger.interceptorMiss(text)

        if (localModel.isReady) {
            generateWithLlm(text)
        } else {
            val msg = "I heard you, but my brain isn't loaded yet. For now I can do: flashlight, alarms, timers, open apps, make calls, wifi/bluetooth settings."
            addMessage(ChatMessage(text = msg, sender = Sender.LUI))
            if (voiceEngine.conversationMode) voiceEngine.speak(msg)
        }
    }

    private fun executeToolCall(toolCall: com.lui.app.data.ToolCall) {
        // Cancel any running generation to prevent overlap
        generationJob?.cancel()
        generationJob = null
        voiceEngine.stopSpeaking()

        // Check if this action needs a runtime permission
        val permReq = PermissionHelper.getRequiredPermission(toolCall.tool)
        if (permReq != null && !PermissionHelper.hasPermission(getApplication(), permReq.permission)) {
            pendingToolCall = toolCall
            addMessage(ChatMessage(text = permReq.explanation, sender = Sender.LUI))
            _permissionRequest.value = permReq
            return
        }

        // Handle ViewModel-level tools that need access to state
        if (toolCall.tool == "copy_clipboard") {
            val text = lastActionResult
            if (text != null) {
                val result = com.lui.app.interceptor.actions.MediaActions.copyToClipboard(getApplication(), text)
                val msg = when (result) { is ActionResult.Success -> result.message; is ActionResult.Failure -> result.message }
                addMessage(ChatMessage(text = msg, sender = Sender.LUI))
                if (voiceEngine.conversationMode) voiceEngine.speak(msg)
            } else {
                addMessage(ChatMessage(text = "Nothing to copy.", sender = Sender.LUI))
            }
            return
        }
        if (toolCall.tool == "share_text" && toolCall.params["text"] == "__LAST_RESULT__") {
            val text = lastActionResult
            if (text != null) {
                val result = com.lui.app.interceptor.actions.MediaActions.shareText(getApplication(), text)
                val msg = when (result) { is ActionResult.Success -> result.message; is ActionResult.Failure -> result.message }
                addMessage(ChatMessage(text = msg, sender = Sender.LUI))
            } else {
                addMessage(ChatMessage(text = "Nothing to share.", sender = Sender.LUI))
            }
            return
        }
        if (toolCall.tool == "undo") {
            handleUndo()
            return
        }

        // Confirm destructive actions before executing
        val confirmMsg = getConfirmationMessage(toolCall)
        if (confirmMsg != null && pendingConfirmation == null) {
            pendingConfirmation = toolCall
            addMessage(ChatMessage(text = confirmMsg, sender = Sender.LUI))
            if (voiceEngine.conversationMode) voiceEngine.speak(confirmMsg)
            return
        }

        pendingConfirmation = null
        LuiLogger.toolExecute(toolCall.tool, toolCall.params)
        val result = ActionExecutor.execute(getApplication(), toolCall)
        val response = when (result) {
            is ActionResult.Success -> {
                lastExecutedTool = toolCall
                LuiLogger.toolResult(toolCall.tool, true, result.message)
                result.message
            }
            is ActionResult.Failure -> {
                LuiLogger.toolResult(toolCall.tool, false, result.message)
                result.message
            }
        }
        lastActionResult = response
        addMessage(ChatMessage(text = response, sender = Sender.LUI))
        if (voiceEngine.conversationMode) voiceEngine.speak(response)
    }

    private fun handleUndo() {
        val last = lastExecutedTool
        if (last == null) {
            addMessage(ChatMessage(text = "Nothing to undo.", sender = Sender.LUI))
            return
        }
        val undoResult = when (last.tool) {
            "toggle_flashlight" -> {
                ActionExecutor.execute(getApplication(), com.lui.app.data.ToolCall("toggle_flashlight", mapOf("state" to "toggle")))
            }
            "set_alarm" -> ActionExecutor.execute(getApplication(), com.lui.app.data.ToolCall("dismiss_alarm"))
            "set_timer" -> ActionExecutor.execute(getApplication(), com.lui.app.data.ToolCall("cancel_timer"))
            "toggle_dnd" -> ActionExecutor.execute(getApplication(), com.lui.app.data.ToolCall("toggle_dnd"))
            "toggle_rotation" -> ActionExecutor.execute(getApplication(), com.lui.app.data.ToolCall("toggle_rotation"))
            "set_volume" -> {
                val reverse = when (last.params["direction"]) { "up" -> "down"; "down" -> "up"; else -> null }
                if (reverse != null) ActionExecutor.execute(getApplication(), com.lui.app.data.ToolCall("set_volume", mapOf("direction" to reverse)))
                else ActionResult.Failure("Can't undo that volume change.")
            }
            else -> ActionResult.Failure("Can't undo '${last.tool}'. Some actions are irreversible.")
        }
        lastExecutedTool = null
        val msg = when (undoResult) { is ActionResult.Success -> "Undone. ${undoResult.message}"; is ActionResult.Failure -> undoResult.message }
        addMessage(ChatMessage(text = msg, sender = Sender.LUI))
        if (voiceEngine.conversationMode) voiceEngine.speak(msg)
    }

    private fun getConfirmationMessage(toolCall: com.lui.app.data.ToolCall): String? {
        return when (toolCall.tool) {
            "send_sms" -> {
                val to = toolCall.params["number"] ?: "?"
                val msg = toolCall.params["message"] ?: ""
                "Send \"$msg\" to $to?"
            }
            "make_call" -> "Call ${toolCall.params["target"] ?: "?"}?"
            else -> null
        }
    }

    /** Called by the fragment after permission is granted/denied */
    fun onPermissionResult(granted: Boolean) {
        _permissionRequest.value = null
        val toolCall = pendingToolCall ?: return
        LuiLogger.permission(toolCall.tool, granted)
        pendingToolCall = null

        if (granted) {
            executeToolCall(toolCall)
        } else {
            addMessage(ChatMessage(text = "I need that permission to do this. Opening settings so you can enable it.", sender = Sender.LUI))
            PermissionHelper.openAppPermissionSettings(getApplication())
        }
    }

    // Voice state
    private var voiceMessageActive = false
    private var voiceBubbleAdded = false

    fun startVoiceInput(conversationMode: Boolean = false) {
        voiceEngine.conversationMode = conversationMode
        voiceEngine.startListening()
        voiceMessageActive = true
        voiceBubbleAdded = false
        // Don't add a user bubble yet — wait for actual partial text
    }

    private fun updateVoiceTranscript(partial: String) {
        if (!voiceMessageActive) return

        if (!voiceBubbleAdded) {
            // First partial result — add the user bubble now
            addMessage(ChatMessage(text = partial, sender = Sender.USER, streaming = true))
            voiceBubbleAdded = true
        } else {
            updateLastMessage(partial, streaming = true)
        }
    }

    private fun finalizeVoiceInput(text: String) {
        if (!voiceMessageActive) return
        LuiLogger.voiceFinal(text)
        voiceMessageActive = false

        if (!voiceBubbleAdded) {
            // Never got partials — add the final text directly (saves to Room)
            addMessage(ChatMessage(text = text, sender = Sender.USER))
        } else {
            // Update in-memory bubble with final text
            updateLastMessage(text, streaming = false)
            // Save final text to Room (the partial that was saved earlier is incomplete)
            viewModelScope.launch {
                chatRepo.saveMessage(ChatMessage(text = text, sender = Sender.USER))
            }
        }
        voiceBubbleAdded = false
        processAfterVoice(text)
    }

    private fun processAfterVoice(text: String) {
        // Cloud available: LLM orchestrates everything
        if (cloudModel.isReady) {
            generateWithLlm(text)
            return
        }

        // Local/offline: interceptor first
        val toolCall = Interceptor.parse(text)
        if (toolCall != null) {
            executeToolCall(toolCall)
            return
        }

        if (localModel.isReady) {
            generateWithLlm(text)
        } else {
            val msg = "Keyword mode only. Try: flashlight, alarm, open app, call."
            addMessage(ChatMessage(text = msg, sender = Sender.LUI))
            if (voiceEngine.conversationMode) voiceEngine.speak(msg)
        }
    }

    private fun generateWithLlm(userText: String) {
        addMessage(ChatMessage(text = "", sender = Sender.THINKING))

        val responseBuilder = StringBuilder()
        var lastSpokenIndex = 0
        var pipelineStarted = false

        generationJob = viewModelScope.launch {
            // Pass history for cloud models that need conversation context
            val history = _messages.value.orEmpty().filter {
                it.sender == Sender.USER || it.sender == Sender.LUI
            }.takeLast(10)
            router.generateStreaming(userText, history)
                .catch { e ->
                    LuiLogger.error("LLM", "Generation error: ${e.message}", e)
                    replaceLastWithLui("Something went wrong: ${e.message}", streaming = false)
                }
                .collect { token ->
                    responseBuilder.append(token)
                    val cleaned = SystemPrompt.cleanResponse(responseBuilder.toString())
                    if (cleaned.isNotBlank()) {
                        replaceLastWithLui(cleaned, streaming = true)

                        // Start TTS pipeline once, then feed sentences as they complete
                        if (voiceEngine.conversationMode) {
                            if (!pipelineStarted) {
                                voiceEngine.startPipeline()
                                pipelineStarted = true
                            }

                            val newText = cleaned.substring(lastSpokenIndex)
                            val splitIdx = newText.indexOfAny(charArrayOf('.', '!', '?'))
                            if (splitIdx >= 0 && newText.length > 10) {
                                val chunk = newText.substring(0, splitIdx + 1).trim()
                                if (chunk.isNotBlank()) {
                                    voiceEngine.speakSentence(chunk)
                                    lastSpokenIndex += splitIdx + 1
                                }
                            }
                        }
                    }
                }

            // Generation complete
            val fullResponse = SystemPrompt.cleanResponse(responseBuilder.toString())
            LuiLogger.llmResponse(fullResponse)
            val llmToolCall = Interceptor.parseLlmOutput(fullResponse)
            if (llmToolCall != null) {
                // LLM decided to call a tool — execute it
                LuiLogger.interceptorMatch(llmToolCall.tool, llmToolCall.params, "llm-json")
                LuiLogger.toolExecute(llmToolCall.tool, llmToolCall.params)
                val result = ActionExecutor.execute(getApplication(), llmToolCall)
                val actionMsg = when (result) {
                    is ActionResult.Success -> result.message
                    is ActionResult.Failure -> result.message
                }

                // If cloud model, feed result back — it may chain another tool or interpret
                if (router.isUsingCloud) {
                    replaceLastWithLui("", streaming = true)
                    val chainHistory = _messages.value.orEmpty().filter {
                        it.sender == Sender.USER || it.sender == Sender.LUI
                    }.takeLast(10).toMutableList()
                    chainHistory.add(ChatMessage(text = "Tool result for '${llmToolCall.tool}': $actionMsg", sender = Sender.USER))

                    // Allow up to 3 chained tool calls
                    var lastResult = actionMsg
                    var lastToolName = llmToolCall.tool
                    var chainCount = 0
                    val maxChains = 3

                    while (chainCount < maxChains) {
                        val chainBuilder = StringBuilder()
                        router.generateStreaming(
                            "Tool '$lastToolName' returned: $lastResult. " +
                            "If you need to call another tool to complete the user's request, output ONLY the JSON. " +
                            "Otherwise, summarize the result naturally for the user in 1-2 sentences.",
                            chainHistory
                        ).collect { token ->
                            chainBuilder.append(token)
                            val cleaned = SystemPrompt.cleanResponse(chainBuilder.toString())
                            if (cleaned.isNotBlank()) replaceLastWithLui(cleaned, streaming = true)
                        }

                        val chainResponse = SystemPrompt.cleanResponse(chainBuilder.toString())
                        val nextTool = Interceptor.parseLlmOutput(chainResponse)

                        if (nextTool != null) {
                            // Chain: execute next tool
                            LuiLogger.toolChain(chainCount + 1, nextTool.tool, "executing")
                            lastToolName = nextTool.tool
                            val nextResult = ActionExecutor.execute(getApplication(), nextTool)
                            lastResult = when (nextResult) {
                                is ActionResult.Success -> nextResult.message
                                is ActionResult.Failure -> nextResult.message
                            }
                            chainHistory.add(ChatMessage(text = "Tool result for '${nextTool.tool}': $lastResult", sender = Sender.USER))
                            replaceLastWithLui("", streaming = true)
                            chainCount++
                        } else {
                            // No more tools — this is the final interpretation
                            val finalMsg = chainResponse.ifBlank { lastResult }
                            lastActionResult = finalMsg
                            replaceLastWithLui(finalMsg, streaming = false)
                            if (voiceEngine.conversationMode) voiceEngine.speak(finalMsg)
                            break
                        }
                    }

                    // Safety: if we hit max chains, show the last result
                    if (chainCount >= maxChains) {
                        replaceLastWithLui(lastResult, streaming = false)
                        if (voiceEngine.conversationMode) voiceEngine.speak(lastResult)
                    }
                } else {
                    replaceLastWithLui(actionMsg, streaming = false)
                    if (voiceEngine.conversationMode) voiceEngine.speak(actionMsg)
                }
            } else {
                replaceLastWithLui(fullResponse, streaming = false)
                if (voiceEngine.conversationMode) {
                    val remaining = fullResponse.substring(lastSpokenIndex).trim()
                    if (remaining.isNotBlank()) voiceEngine.speakSentence(remaining)
                    voiceEngine.speakDone()
                }
            }
        }
    }

    private fun addMessage(message: ChatMessage) {
        val current = _messages.value.orEmpty().toMutableList()
        current.add(message)
        _messages.value = current
        _scrollToBottom.value = Unit
        // Persist USER and LUI messages — but NOT streaming partials (voice mid-transcription)
        if ((message.sender == Sender.USER || message.sender == Sender.LUI) && !message.streaming) {
            viewModelScope.launch { chatRepo.saveMessage(message) }
        }
    }

    private fun replaceLastWithLui(text: String, streaming: Boolean = false) {
        val current = _messages.value.orEmpty().toMutableList()
        if (current.isNotEmpty()) {
            val msg = ChatMessage(text = text, sender = Sender.LUI, timestamp = current.last().timestamp, streaming = streaming)
            current[current.size - 1] = msg
            _messages.value = current
            _scrollToBottom.value = Unit
            // Persist final (non-streaming) LUI response
            if (!streaming && text.isNotBlank()) {
                viewModelScope.launch { chatRepo.saveMessage(msg) }
            }
        }
    }

    private fun updateLastMessage(text: String, streaming: Boolean = false) {
        val current = _messages.value.orEmpty().toMutableList()
        if (current.isNotEmpty()) {
            val last = current.last()
            current[current.size - 1] = last.copy(text = text, streaming = streaming)
            _messages.value = current
            _scrollToBottom.value = Unit
        }
    }

    fun refreshCloudConfig() {
        _llmStatus.value = when {
            router.isUsingCloud -> "cloud"
            localModel.isReady -> "ready"
            cloudModel.isReady -> "cloud"
            else -> "no_model"
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        localModel.close()
        voiceEngine.release()
        super.onCleared()
    }
}
