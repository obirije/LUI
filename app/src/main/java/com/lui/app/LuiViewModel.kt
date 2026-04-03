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
import com.lui.app.llm.GenerationResult
import com.lui.app.llm.LlmRouter
import com.lui.app.llm.LlmToolCall
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

    // Bridge remote approval: shown when a remote agent tries a restricted tool
    data class BridgeApprovalRequest(val tool: String, val description: String)
    private val _bridgeApproval = MutableLiveData<BridgeApprovalRequest?>()
    val bridgeApproval: LiveData<BridgeApprovalRequest?> = _bridgeApproval
    private var bridgeApprovalResult: java.util.concurrent.CountDownLatch? = null
    private var bridgeApprovalGranted = false

    // Agent passthrough mode: "patch me to hermes"
    private var passthroughAgent: String? = null
    val isInPassthrough: Boolean get() = passthroughAgent != null

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

        // Set up bridge approval callback
        com.lui.app.bridge.BridgeProtocol.approvalCallback = { tool, description ->
            // This runs on the bridge thread — post to main thread and wait
            val latch = java.util.concurrent.CountDownLatch(1)
            bridgeApprovalGranted = false
            bridgeApprovalResult = latch

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                _bridgeApproval.value = BridgeApprovalRequest(tool, description)
            }

            // Block bridge thread until user responds (timeout 30s)
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            _bridgeApproval.postValue(null)
            bridgeApprovalGranted
        }

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

        val lower = text.trim().lowercase()

        // ── Agent passthrough mode: forward everything to agent ──
        if (passthroughAgent != null) {
            // Exit keywords — user addressing LUI directly
            if (lower == "lui" || lower.contains("lui come back") || lower.contains("come back lui") ||
                lower.contains("back to lui") || lower.contains("lui take over") ||
                lower == "disconnect" || lower == "exit" || lower == "end session" ||
                lower.startsWith("lui ") || lower.endsWith(" lui")) {
                addMessage(ChatMessage(text = text.trim(), sender = Sender.USER))
                endPassthrough()
                return
            }
            // Everything else goes to the agent
            addMessage(ChatMessage(text = text.trim(), sender = Sender.USER))
            forwardToAgent(passthroughAgent!!, text.trim())
            return
        }

        // ── @ mention: "@hermes deploy staging" ──
        val mentionMatch = Regex("^@([\\w-]+)\\s+(.+)", RegexOption.IGNORE_CASE).find(text.trim())
        if (mentionMatch != null) {
            val agentName = mentionMatch.groupValues[1]
            val instruction = mentionMatch.groupValues[2]
            addMessage(ChatMessage(text = text.trim(), sender = Sender.USER))
            forwardToAgent(agentName, instruction)
            return
        }

        // ── Normal flow: LLM handles everything including passthrough requests ──
        // The LLM has start_passthrough and end_passthrough tools.
        // "patch me to hermes" → LLM calls start_passthrough(agent="hermes")

        // Special commands
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

        // Force real-time tool calls for live-state queries even in cloud mode.
        // LLMs cache previous "no results" responses and skip re-checking.
        val forceToolCall = getForcedToolCall(lower)
        if (forceToolCall != null && router.isUsingCloud) {
            // Check permission first
            val permReq = PermissionHelper.getRequiredPermission(forceToolCall.tool)
            if (permReq != null && !PermissionHelper.hasPermission(getApplication(), permReq.permission)) {
                pendingToolCall = forceToolCall
                addMessage(ChatMessage(text = permReq.explanation, sender = Sender.LUI))
                _permissionRequest.value = permReq
                return
            }

            LuiLogger.i("FORCE", "Forcing tool call: ${forceToolCall.tool}")
            addMessage(ChatMessage(text = "", sender = Sender.THINKING))
            generationJob = viewModelScope.launch {
                val result = ActionExecutor.execute(getApplication(), forceToolCall)
                val resultMsg = when (result) {
                    is ActionResult.Success -> result.message
                    is ActionResult.Failure -> result.message
                }
                lastActionResult = resultMsg
                // Let LLM interpret the result naturally
                val history = _messages.value.orEmpty().filter {
                    it.sender == Sender.USER || it.sender == Sender.LUI
                }.takeLast(10)
                val toolCallObj = LlmToolCall(forceToolCall.tool, forceToolCall.params)
                val toolResults = listOf(Pair(toolCallObj, resultMsg))
                try {
                    val responseBuilder = StringBuilder()
                    cloudModel.generateWithTools("", history, toolResults).collect { r ->
                        when (r) {
                            is GenerationResult.TextToken -> {
                                responseBuilder.append(r.token)
                                val cleaned = SystemPrompt.cleanResponse(responseBuilder.toString())
                                if (cleaned.isNotBlank()) replaceLastWithLui(cleaned, streaming = true)
                            }
                            is GenerationResult.Done -> {
                                val cleaned = SystemPrompt.cleanResponse(r.text)
                                val toSpeak = cleaned.ifBlank { resultMsg }
                                replaceLastWithLui(toSpeak, streaming = false)
                                LuiLogger.d("VOICE", "Force-Done: convMode=${voiceEngine.conversationMode} text=${toSpeak.take(50)}")
                                if (voiceEngine.conversationMode) voiceEngine.speak(toSpeak)
                            }
                            is GenerationResult.ToolUse -> {} // shouldn't chain here
                        }
                    }
                    val final = SystemPrompt.cleanResponse(responseBuilder.toString())
                    if (final.isNotBlank()) replaceLastWithLui(final, streaming = false)
                } catch (e: Exception) {
                    replaceLastWithLui(resultMsg, streaming = false)
                }
            }
            return
        }

        // When cloud is the active model, the LLM orchestrates everything
        if (router.isUsingCloud) {
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
        LuiLogger.d("VOICE", "ToolExec: convMode=${voiceEngine.conversationMode} text=${response.take(50)}")
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

    /**
     * For live-state queries, force the tool call directly instead of hoping
     * the LLM will call it. LLMs cache previous results in conversation context
     * and skip re-checking. Any tool that reads real-time device/system state
     * must be forced here.
     */
    private fun getForcedToolCall(input: String): com.lui.app.data.ToolCall? {
        // Delegate to the keyword interceptor — it already has comprehensive patterns
        // for all live-state tools. Only force for read-only state queries.
        val toolCall = Interceptor.parse(input) ?: return null
        val liveStateTools = setOf(
            "read_notifications", "read_calendar", "read_sms", "read_clipboard",
            "get_time", "get_date", "device_info", "battery",
            "get_location", "get_distance",
            "now_playing", "screen_time",
            "get_2fa_code", "get_digest",
            "read_screen",
            "get_steps", "get_proximity", "get_light",
            "storage_info", "wifi_info", "query_media"
        )
        return if (toolCall.tool in liveStateTools) toolCall else null
    }

    private fun replaceLastWithThinking() {
        val current = _messages.value.orEmpty().toMutableList()
        if (current.isNotEmpty()) {
            current[current.size - 1] = ChatMessage(text = "", sender = Sender.THINKING)
            _messages.value = current
            _scrollToBottom.value = Unit
        }
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
    // ── Agent passthrough / @ mention ──

    private fun startPassthrough(agentName: String) {
        val agents = com.lui.app.bridge.AgentRegistry.registeredAgents
        val match = com.lui.app.bridge.AgentRegistry.findAgent(agentName)
        if (match == null) {
            addMessage(ChatMessage(text = "No agent named '$agentName' is connected. Available: ${agents.joinToString { it.name }.ifEmpty { "none" }}.", sender = Sender.LUI))
            if (voiceEngine.conversationMode) voiceEngine.speak("No agent named $agentName is connected.")
            return
        }
        passthroughAgent = match.name
        val msg = "Connected to ${match.name}. Everything you say goes to ${match.name} now. Say 'LUI come back' to return."
        addMessage(ChatMessage(text = msg, sender = Sender.LUI))
        LuiLogger.i("AGENT", "Passthrough started: ${match.name}")
        if (voiceEngine.conversationMode) voiceEngine.speak("Connected to ${match.name}.")
    }

    private fun endPassthrough() {
        val name = passthroughAgent ?: return
        passthroughAgent = null
        val msg = "Back with you. $name is still connected in the background."
        addMessage(ChatMessage(text = msg, sender = Sender.LUI))
        LuiLogger.i("AGENT", "Passthrough ended: $name")
        if (voiceEngine.conversationMode) voiceEngine.speak("Back with you.")
    }

    private fun forwardToAgent(agentName: String, instruction: String) {
        val agent = com.lui.app.bridge.AgentRegistry.findAgent(agentName)
        val resolvedName = agent?.name ?: agentName
        addMessage(ChatMessage(text = "", sender = Sender.THINKING))
        generationJob = viewModelScope.launch {
            // Run on IO thread — sendInstruction blocks with latch.await()
            val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.lui.app.bridge.AgentRegistry.sendInstruction(resolvedName, instruction)
            }
            lastActionResult = response
            replaceLastWithLui(response, streaming = false)
            LuiLogger.i("AGENT", "← $agentName: ${response.take(80)}")
            if (voiceEngine.conversationMode) voiceEngine.speak(response)
        }
    }

    /** Called by the fragment when user approves/denies a bridge remote action */
    fun onBridgeApprovalResult(approved: Boolean) {
        bridgeApprovalGranted = approved
        bridgeApprovalResult?.countDown()
        bridgeApprovalResult = null
        _bridgeApproval.value = null
        LuiLogger.i("BRIDGE", "User ${if (approved) "APPROVED" else "DENIED"} remote action")
    }

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
        // Cloud active: LLM orchestrates everything
        if (router.isUsingCloud) {
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

        generationJob = viewModelScope.launch {
            val history = _messages.value.orEmpty().filter {
                it.sender == Sender.USER || it.sender == Sender.LUI
            }.takeLast(10)

            // Use native tool use if cloud is the active model
            if (router.isUsingCloud) {
                generateWithNativeTools(userText, history)
            } else {
                generateWithLocalLlm(userText)
            }
        }
    }

    /**
     * Native tool-use flow: the LLM returns structured tool calls, we execute them,
     * send results back, and loop until the LLM returns text.
     * No JSON parsing from text. No keyword matching on LLM output.
     */
    private suspend fun generateWithNativeTools(userText: String, history: List<ChatMessage>) {
        val toolResults = mutableListOf<Pair<LlmToolCall, String>>()
        var isFirstRound = true
        val maxRounds = 5

        for (round in 0 until maxRounds) {
            val responseBuilder = StringBuilder()
            var lastSpokenIndex = 0
            var pipelineStarted = false
            var pendingToolCall: LlmToolCall? = null

            try {
                cloudModel.generateWithTools(
                    userText, history,
                    if (isFirstRound) null else toolResults
                ).collect { result ->
                    when (result) {
                        is GenerationResult.TextToken -> {
                            responseBuilder.append(result.token)
                            val cleaned = SystemPrompt.cleanResponse(responseBuilder.toString())
                            if (cleaned.isNotBlank()) {
                                replaceLastWithLui(cleaned, streaming = true)

                                if (voiceEngine.conversationMode) {
                                    if (!pipelineStarted) {
                                        voiceEngine.startPipeline()
                                        pipelineStarted = true
                                    }
                                    val newText = cleaned.substring(lastSpokenIndex)
                                    // Split on sentence-ending punctuation (eager) or clause punctuation (with min length)
                                    // Cloud TTS needs longer chunks for natural flow; local TTS can handle shorter
                                    val isCloud = voiceEngine.isUsingCloudTts
                                    val sentenceEnd = newText.indexOfAny(charArrayOf('.', '!', '?'))
                                    val clauseEnd = newText.indexOfAny(charArrayOf(',', ':', ';', '\u2014'))
                                    val minSentenceLen = if (isCloud) 60 else 5
                                    val minClauseLen = if (isCloud) 120 else 25
                                    val splitIdx = when {
                                        sentenceEnd >= 0 && newText.length > minSentenceLen -> sentenceEnd
                                        clauseEnd >= 0 && newText.length > minClauseLen -> clauseEnd
                                        else -> -1
                                    }
                                    if (splitIdx >= 0) {
                                        val chunk = newText.substring(0, splitIdx + 1).trim()
                                        if (chunk.isNotBlank()) {
                                            voiceEngine.speakSentence(chunk)
                                            lastSpokenIndex += splitIdx + 1
                                        }
                                    }
                                }
                            }
                        }
                        is GenerationResult.ToolUse -> {
                            pendingToolCall = result.toolCall
                        }
                        is GenerationResult.Done -> {
                            // Final text response — display and finish
                            val fullResponse = SystemPrompt.cleanResponse(result.text)
                            LuiLogger.llmResponse(fullResponse)
                            lastActionResult = fullResponse
                            replaceLastWithLui(fullResponse, streaming = false)
                            LuiLogger.d("VOICE", "Done: convMode=${voiceEngine.conversationMode} pipelineStarted=$pipelineStarted remaining=${fullResponse.length - lastSpokenIndex}")
                            if (voiceEngine.conversationMode) {
                                if (!pipelineStarted) {
                                    // All text arrived at once (no streaming tokens) — speak the whole thing
                                    voiceEngine.speak(fullResponse)
                                } else {
                                    val remaining = fullResponse.substring(lastSpokenIndex).trim()
                                    if (remaining.isNotBlank()) voiceEngine.speakSentence(remaining)
                                    voiceEngine.speakDone()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                LuiLogger.error("LLM", "Generation error: ${e.message}", e)
                replaceLastWithLui("Something went wrong: ${e.message}", streaming = false)
                return
            }

            // If no tool call, we're done
            if (pendingToolCall == null) return

            // Execute the tool call
            val tc = pendingToolCall!!
            LuiLogger.toolExecute(tc.name, tc.args)
            replaceLastWithThinking()

            val toolCallData = com.lui.app.data.ToolCall(tc.name, tc.args)
            val actionResult = ActionExecutor.execute(getApplication(), toolCallData)
            val resultMsg = when (actionResult) {
                is ActionResult.Success -> actionResult.message
                is ActionResult.Failure -> actionResult.message
            }
            LuiLogger.toolResult(tc.name, actionResult is ActionResult.Success, resultMsg)
            LuiLogger.toolChain(round + 1, tc.name, resultMsg)

            // Handle passthrough sentinels — these don't go back to the LLM
            if (resultMsg.startsWith("__PASSTHROUGH_START__")) {
                val agentName = resultMsg.removePrefix("__PASSTHROUGH_START__")
                startPassthrough(agentName)
                return
            }
            if (resultMsg == "__PASSTHROUGH_END__") {
                endPassthrough()
                return
            }

            // Track for undo
            if (actionResult is ActionResult.Success) {
                lastExecutedTool = toolCallData
            }

            // Add to tool results and loop — the LLM will see the result and decide what to do next
            toolResults.add(Pair(tc, resultMsg))
            isFirstRound = false
        }

        // If we hit max rounds, show the last result
        val lastResult = toolResults.lastOrNull()?.second ?: "Done."
        lastActionResult = lastResult
        replaceLastWithLui(lastResult, streaming = false)
        if (voiceEngine.conversationMode) voiceEngine.speak(lastResult)
    }

    /** Local model fallback — no native tool use, just text streaming */
    private suspend fun generateWithLocalLlm(userText: String) {
        val responseBuilder = StringBuilder()
        var lastSpokenIndex = 0
        var pipelineStarted = false

        try {
            router.generateStreaming(userText)
                .collect { token ->
                    responseBuilder.append(token)
                    val cleaned = SystemPrompt.cleanResponse(responseBuilder.toString())
                    if (cleaned.isNotBlank()) {
                        replaceLastWithLui(cleaned, streaming = true)

                        if (voiceEngine.conversationMode) {
                            if (!pipelineStarted) {
                                voiceEngine.startPipeline()
                                pipelineStarted = true
                            }
                            val newText = cleaned.substring(lastSpokenIndex)
                            val sentenceEnd = newText.indexOfAny(charArrayOf('.', '!', '?'))
                            val clauseEnd = newText.indexOfAny(charArrayOf(',', ':', ';', '\u2014'))
                            val splitIdx = when {
                                sentenceEnd >= 0 && newText.length > 5 -> sentenceEnd
                                clauseEnd >= 0 && newText.length > 25 -> clauseEnd
                                else -> -1
                            }
                            if (splitIdx >= 0) {
                                val chunk = newText.substring(0, splitIdx + 1).trim()
                                if (chunk.isNotBlank()) {
                                    voiceEngine.speakSentence(chunk)
                                    lastSpokenIndex += splitIdx + 1
                                }
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            LuiLogger.error("LLM", "Local generation error: ${e.message}", e)
            replaceLastWithLui("Something went wrong: ${e.message}", streaming = false)
            return
        }

        val fullResponse = SystemPrompt.cleanResponse(responseBuilder.toString())
        LuiLogger.llmResponse(fullResponse)
        replaceLastWithLui(fullResponse, streaming = false)
        if (voiceEngine.conversationMode) {
            val remaining = fullResponse.substring(lastSpokenIndex).trim()
            if (remaining.isNotBlank()) voiceEngine.speakSentence(remaining)
            voiceEngine.speakDone()
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
