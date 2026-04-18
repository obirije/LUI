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

    // Image picker request
    private val _pickImageRequest = MutableLiveData<Boolean>()
    val pickImageRequest: LiveData<Boolean> = _pickImageRequest

    fun onImagePicked(bitmap: android.graphics.Bitmap?) {
        if (bitmap != null) {
            val stream = java.io.ByteArrayOutputStream()
            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 640, 480, true)
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
            val base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
            com.lui.app.interceptor.actions.VisionActions.lastCapturedImage = base64
            com.lui.app.interceptor.actions.VisionActions.lastCapturedBitmap = scaled
            addMessage(ChatMessage(text = "", sender = Sender.LUI, imageBitmap = scaled))
            LuiLogger.i("VISION", "Image selected from gallery")
        }
        _pickImageRequest.value = false
    }

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

        // Auto-connect to paired health ring in background
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val ring = com.lui.app.interceptor.actions.HealthActions.getRingService(application)
                ring.autoConnect()
            } catch (e: Exception) {
                LuiLogger.e("Health", "Auto-connect failed: ${e.message}")
            }
        }

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

        // Initialize LLM (file I/O + model load on IO dispatcher).
        // Cloud-first + cloud ready: skip local load entirely — saves RAM and
        // avoids surfacing "failed to load" when the user doesn't need local.
        // Local is loaded lazily via loadLocalModel() when cloud fails or the
        // user toggles to local.
        viewModelScope.launch {
            if (keyStore.isCloudFirst && cloudModel.isReady) {
                _llmStatus.value = "cloud"
                return@launch
            }

            _llmStatus.value = "Checking for model..."
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ModelManager.ensureModel(application)
            }
            if (found) {
                _llmStatus.value = "Loading model..."
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    localModel.initialize()
                }
                if (result.isSuccess) {
                    _llmStatus.value = "ready"
                } else if (cloudModel.isReady) {
                    _llmStatus.value = "cloud"
                    Log.e("LuiVM", "Local init failed, using cloud", result.exceptionOrNull())
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

        // Initialize voice engine (file I/O on IO dispatcher)
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ModelManager.ensureVoiceModels(application)
                ModelManager.ensureTtsModel(application)
            }
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

        // Proactive stress alert — watch ring stress readings
        viewModelScope.launch {
            val ring = com.lui.app.interceptor.actions.HealthActions.getRingService(application)
            ring.stress.collect { level ->
                if (level > 0) onStressReading(level)
            }
        }
    }

    // ── Proactive stress alert ──
    // Fire when we see N consecutive high readings and we haven't alerted recently.

    private var consecutiveHighStress = 0
    private var lastStressAlertAt = 0L

    private fun onStressReading(level: Int) {
        val HIGH_THRESHOLD = 75
        val REQUIRED_CONSECUTIVE = 3          // ~45 min given 15-min sync cycle
        val COOLDOWN_MS = 2 * 60 * 60 * 1000L // 2 hours between alerts

        if (level >= HIGH_THRESHOLD) {
            consecutiveHighStress += 1
        } else {
            consecutiveHighStress = 0
            return
        }

        if (consecutiveHighStress < REQUIRED_CONSECUTIVE) return

        val now = System.currentTimeMillis()
        if (now - lastStressAlertAt < COOLDOWN_MS) return

        // Don't interrupt if the user is mid-conversation with voice mode
        if (voiceEngine.conversationMode) return

        lastStressAlertAt = now
        consecutiveHighStress = 0

        val text = "Your stress has been elevated (around $level) for the last while. Want me to start wellness mode? I'll play something calming, mute notifications, and dim the screen."
        addMessage(ChatMessage(text = text, sender = Sender.LUI))
        LuiLogger.i("STRESS", "Proactive alert fired at level $level")
    }

    fun handleUserInput(text: String) {
        if (text.isBlank()) return
        LuiLogger.userInput(text)

        val lower = text.trim().lowercase()

        // ── End conversation mode ──
        val endPhrases = listOf("goodbye", "bye", "good night", "goodnight", "bye bye",
            "that's all", "thats all", "i'm done", "im done", "go to sleep",
            "thanks lui", "thank you lui", "bye lui", "goodbye lui", "end conversation",
            "stop listening", "that will be all", "nothing else")
        if (voiceEngine.conversationMode && endPhrases.any { lower.contains(it) }) {
            addMessage(ChatMessage(text = text.trim(), sender = Sender.USER))
            val farewell = "Alright, talk to you later."
            addMessage(ChatMessage(text = farewell, sender = Sender.LUI))
            voiceEngine.conversationMode = false // Prevent auto-listen after TTS finishes
            voiceEngine.speak(farewell)
            viewModelScope.launch {
                kotlinx.coroutines.delay(3000) // Wait for farewell to finish speaking
                voiceEngine.stopListening()
                voiceEngine.stopSpeaking()
                LuiLogger.i("VOICE", "Conversation ended by user")
            }
            return
        }

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
            val forceStatusHint = when (forceToolCall.tool) {
                "search_web" -> "Searching the web..."
                "browse_url" -> "Reading page..."
                "get_location", "get_distance" -> "Getting location..."
                "read_notifications" -> "Checking notifications..."
                "read_screen" -> "Reading screen..."
                "battery" -> "Checking battery..."
                "ambient_context" -> "Checking device status..."
                "get_heart_rate" -> "Measuring heart rate..."
                "get_spo2" -> "Reading blood oxygen..."
                "get_sleep" -> "Syncing sleep data..."
                "get_activity" -> "Syncing activity..."
                "get_stress" -> "Reading stress level..."
                "get_hrv" -> "Reading HRV..."
                "get_temperature" -> "Reading temperature..."
                "get_health_summary" -> "Checking vitals..."
                "get_health_trend" -> "Checking health history..."
                "ring_battery" -> "Checking ring battery..."
                "ring_status" -> "Checking ring status..."
                else -> null
            }
            if (forceStatusHint != null) {
                addMessage(ChatMessage(text = forceStatusHint, sender = Sender.LUI, streaming = true))
            } else {
                addMessage(ChatMessage(text = "", sender = Sender.THINKING))
            }
            generationJob = viewModelScope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ActionExecutor.execute(getApplication(), forceToolCall)
                }
                val resultMsg = when (result) {
                    is ActionResult.Success -> result.message
                    is ActionResult.Failure -> result.message
                }
                lastActionResult = resultMsg

                // Show rich card if applicable
                val cardMsg = buildCardMessage(forceToolCall.tool, result, resultMsg)
                if (cardMsg.cardType != null) {
                    replaceLastWithLui("", streaming = false)
                    addMessage(cardMsg)
                    addMessage(ChatMessage(text = "", sender = Sender.THINKING))
                }

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
        LuiLogger.i("ROUTE", "Local path: cloudFirst=${keyStore.isCloudFirst}, cloudReady=${cloudModel.isReady}, localReady=${localModel.isReady}")
        val toolCall = Interceptor.parse(text)
        if (toolCall != null) {
            LuiLogger.interceptorMatch(toolCall.tool, toolCall.params)
            executeToolCall(toolCall)
            return
        }
        LuiLogger.interceptorMiss(text)

        if (localModel.isReady) {
            generateWithLlm(text)
        } else if (cloudModel.isReady) {
            // Local model not ready but cloud is — use cloud
            LuiLogger.i("ROUTE", "Local not ready, falling back to cloud")
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

        // Show status hint for slow tools
        val hint = when (toolCall.tool) {
            "search_web" -> "Searching the web..."
            "browse_url" -> "Reading page..."
            "get_heart_rate" -> "Measuring heart rate..."
            "get_health_summary" -> "Checking vitals..."
            "get_location", "get_distance" -> "Getting location..."
            "take_photo" -> "Capturing photo..."
            "read_screen" -> "Reading screen..."
            else -> null
        }
        if (hint != null) {
            addMessage(ChatMessage(text = hint, sender = Sender.LUI, streaming = true))
        }

        // Execute on IO thread to prevent ANR
        generationJob = viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ActionExecutor.execute(getApplication(), toolCall)
            }
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

            // Replace status hint with result (or add new message)
            if (hint != null) {
                val cardMessage = buildCardMessage(toolCall.tool, result, response)
                replaceLastWithLui(cardMessage.text, streaming = false)
                if (cardMessage.cardType != null) {
                    val current = _messages.value.orEmpty().toMutableList()
                    current[current.size - 1] = cardMessage
                    _messages.value = current
                }
            } else {
                val cardMessage = buildCardMessage(toolCall.tool, result, response)
                addMessage(cardMessage)
            }

            LuiLogger.d("VOICE", "ToolExec: convMode=${voiceEngine.conversationMode} text=${response.take(50)}")
            if (voiceEngine.conversationMode) voiceEngine.speak(response)
        }
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
            "get_2fa_code", "get_digest", "get_notification_history",
            "read_screen",
            "get_steps", "get_proximity", "get_light",
            "storage_info", "wifi_info", "query_media",
            "ambient_context", "bluetooth_devices", "network_state",
            "get_heart_rate", "get_spo2", "get_sleep", "get_activity", "get_stress", "get_hrv", "get_temperature",
            "get_health_summary", "get_health_trend", "ring_battery", "ring_status"
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

    /** Called when wake word "Hey LUI" activates the app */
    fun onWakeWordActivated() {
        LuiLogger.i("WAKE", "Wake word activated — greeting user")
        val greeting = "Hey, I'm here. What can I do for you?"
        addMessage(ChatMessage(text = greeting, sender = Sender.LUI))
        voiceEngine.speak(greeting)
        // After greeting finishes, start listening in conversation mode
        viewModelScope.launch {
            // Wait for TTS to finish (estimate based on greeting length)
            kotlinx.coroutines.delay(2500)
            startVoiceInput(conversationMode = true)
        }
    }

    fun startVoiceInput(conversationMode: Boolean = false) {
        voiceEngine.conversationMode = conversationMode

        // PersonaPlex mode — full-duplex audio conversation
        if (conversationMode && voiceEngine.personaPlexEnabled) {
            val url = keyStore.personaPlexUrl ?: return
            val role = keyStore.personaPlexRole ?: "You are LUI (pronounced Louie), a helpful, direct, and subtly witty phone assistant. Keep responses to 1-2 sentences."
            val voice = keyStore.personaPlexVoice

            // Initialize parallel STT (Deepgram cloud) for user transcript + tool detection
            val parallelStt = com.lui.app.voice.ParallelStt()
            val deepgramKey = keyStore.getSpeechKey(com.lui.app.llm.SpeechProvider.DEEPGRAM)
            val sttReady = deepgramKey != null && parallelStt.initialize(deepgramKey)

            if (sttReady) {
                // Fork mic PCM to STT
                voiceEngine.personaPlex.onPcmCaptured = { samples, count ->
                    parallelStt.feedPcm(samples, count)
                }

                // Show partial user transcript in chat
                var userBubbleActive = false
                viewModelScope.launch {
                    parallelStt.transcript.collect { partial ->
                        if (!userBubbleActive) {
                            addMessage(ChatMessage(text = partial, sender = Sender.USER, streaming = true))
                            userBubbleActive = true
                        } else {
                            updateLastMessage(partial, streaming = true)
                        }
                    }
                }

                // On final transcript — finalize bubble + check for tool calls
                viewModelScope.launch {
                    parallelStt.finalTranscript.collect { text ->
                        if (userBubbleActive) {
                            updateLastMessage(text, streaming = false)
                            userBubbleActive = false
                        } else {
                            addMessage(ChatMessage(text = text, sender = Sender.USER))
                        }

                        // Route tool calls
                        val toolCall = com.lui.app.interceptor.Interceptor.parse(text)
                        if (toolCall != null) {
                            LuiLogger.i("PersonaPlex", "Tool from STT: ${toolCall.tool}")
                            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.lui.app.interceptor.ActionExecutor.execute(getApplication(), toolCall)
                            }
                            val msg = when (result) {
                                is com.lui.app.interceptor.actions.ActionResult.Success -> result.message
                                is com.lui.app.interceptor.actions.ActionResult.Failure -> result.message
                            }
                            addMessage(ChatMessage(text = msg, sender = Sender.LUI))
                            voiceEngine.injectPersonaPlexContext(msg)
                        }
                    }
                }
            } else {
                LuiLogger.w("PersonaPlex", "Parallel STT not available — tool calls via text only")
            }

            // Show assistant transcript in chat
            viewModelScope.launch {
                voiceEngine.personaPlex.assistantTranscript.collect { transcript ->
                    addMessage(ChatMessage(text = transcript, sender = Sender.LUI))
                }
            }

            voiceEngine.startPersonaPlex(url, role, voice)
            return
        }

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
        // Cancel any previous generation/tool coroutine to prevent overlap
        generationJob?.cancel()
        addMessage(ChatMessage(text = "", sender = Sender.THINKING))

        generationJob = viewModelScope.launch {
            val history = _messages.value.orEmpty().filter {
                it.sender == Sender.USER || it.sender == Sender.LUI
            }.takeLast(10)

            // Use native tool use if cloud is available, local LLM as fallback
            if (router.isUsingCloud || (!localModel.isReady && cloudModel.isReady)) {
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
                val friendly = friendlyCloudError(e)
                replaceLastWithLui(friendly, streaming = false)
                maybeLoadLocalAfterCloudFailure(e)
                return
            }

            // If no tool call, we're done
            if (pendingToolCall == null) return

            // Execute the tool call
            val tc = pendingToolCall!!

            // Check permission before executing
            val permReq = PermissionHelper.getRequiredPermission(tc.name)
            if (permReq != null && !PermissionHelper.hasPermission(getApplication(), permReq.permission)) {
                val permToolCall = com.lui.app.data.ToolCall(tc.name, tc.args)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    this@LuiViewModel.pendingToolCall = permToolCall
                    replaceLastWithLui(permReq.explanation, streaming = false)
                    _permissionRequest.value = permReq
                }
                return
            }

            LuiLogger.toolExecute(tc.name, tc.args)

            // Show descriptive status for slow tools BEFORE executing
            val statusHint = when (tc.name) {
                "search_web" -> "Searching the web..."
                "browse_url" -> "Reading ${tc.args["url"]?.replace("https://", "")?.replace("http://", "")?.take(30) ?: "page"}..."
                "get_location", "get_distance" -> "Getting location..."
                "take_photo" -> "Capturing photo..."
                "read_screen" -> "Reading screen..."
                "get_heart_rate" -> "Measuring heart rate..."
                "get_spo2" -> "Reading blood oxygen..."
                "get_sleep" -> "Syncing sleep data..."
                "get_activity" -> "Syncing activity..."
                "get_stress" -> "Reading stress level..."
                "get_hrv" -> "Reading HRV..."
                "get_temperature" -> "Reading temperature..."
                "get_health_summary" -> "Checking vitals..."
                "get_health_trend" -> "Checking health history..."
                "ring_battery" -> "Checking ring battery..."
                else -> null
            }

            // Update UI on main thread, then yield so it actually renders
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (statusHint != null) {
                    replaceLastWithLui(statusHint, streaming = true)
                } else {
                    replaceLastWithThinking()
                }
            }

            // Execute tool on IO thread — main thread is free to render the status
            val toolCallData = com.lui.app.data.ToolCall(tc.name, tc.args)
            val actionResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ActionExecutor.execute(getApplication(), toolCallData)
            }
            val resultMsg = when (actionResult) {
                is ActionResult.Success -> actionResult.message
                is ActionResult.Failure -> actionResult.message
            }
            LuiLogger.toolResult(tc.name, actionResult is ActionResult.Success, resultMsg)
            LuiLogger.toolChain(round + 1, tc.name, resultMsg)

            // Show rich card for search results / status (before LLM interprets)
            val cardMsg = buildCardMessage(tc.name, actionResult, resultMsg)
            if (cardMsg.cardType != null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    replaceLastWithLui("", streaming = false)
                    addMessage(cardMsg)
                    addMessage(ChatMessage(text = "", sender = Sender.THINKING))
                }
            }

            // Handle pick image request — launch picker and wait
            if (resultMsg == "__PICK_IMAGE__") {
                _pickImageRequest.postValue(true)
                // Wait for image selection (up to 60s)
                val pickLatch = java.util.concurrent.CountDownLatch(1)
                val observer = object : androidx.lifecycle.Observer<Boolean> {
                    override fun onChanged(value: Boolean) {
                        if (value == false) pickLatch.countDown()
                    }
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _pickImageRequest.observeForever(observer)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    pickLatch.await(60, java.util.concurrent.TimeUnit.SECONDS)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _pickImageRequest.removeObserver(observer)
                }

                val bitmap = com.lui.app.interceptor.actions.VisionActions.lastCapturedBitmap
                if (bitmap != null) {
                    // Show preview and continue to LLM for analysis
                    replaceLastWithLui("", streaming = false)
                    val current = _messages.value.orEmpty().toMutableList()
                    current[current.size - 1] = ChatMessage(text = "", sender = Sender.LUI, imageBitmap = bitmap)
                    _messages.value = current
                    _scrollToBottom.value = Unit
                    addMessage(ChatMessage(text = "", sender = Sender.THINKING))
                    toolResults.add(Pair(tc, "Image selected from gallery. Now describe what you see in the image to the user."))
                    isFirstRound = false
                    continue
                } else {
                    replaceLastWithLui("No image selected.", streaming = false)
                    return
                }
            }

            // Handle photo captured — show preview and continue to LLM for analysis
            if (resultMsg.startsWith("__PHOTO_CAPTURED__")) {
                val cleanMsg = resultMsg.removePrefix("__PHOTO_CAPTURED__")
                val bitmap = com.lui.app.interceptor.actions.VisionActions.lastCapturedBitmap
                // Replace thinking dots with the photo preview
                if (bitmap != null) {
                    replaceLastWithLui("", streaming = false)
                    val current = _messages.value.orEmpty().toMutableList()
                    current[current.size - 1] = ChatMessage(text = "", sender = Sender.LUI, imageBitmap = bitmap)
                    _messages.value = current
                    _scrollToBottom.value = Unit
                }
                // Add NEW thinking dots for the analysis phase
                addMessage(ChatMessage(text = "", sender = Sender.THINKING))
                toolResults.add(Pair(tc, cleanMsg))
                isFirstRound = false
                continue
            }

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

        // Fallback: if local model produced only <think> content, try cloud or show fallback
        if (fullResponse.isBlank() && responseBuilder.isNotEmpty()) {
            LuiLogger.w("LLM", "Local model returned empty after cleaning (${responseBuilder.length} raw chars)")
            if (cloudModel.isReady) {
                LuiLogger.i("LLM", "Falling back to cloud model")
                val history = _messages.value.orEmpty().filter {
                    it.sender == Sender.USER || it.sender == Sender.LUI
                }.takeLast(10)
                generateWithNativeTools(userText, history)
                return
            }
            val fallback = "I'm here but couldn't form a response. Try asking something specific, or switch to a cloud model in settings."
            replaceLastWithLui(fallback, streaming = false)
            if (voiceEngine.conversationMode) voiceEngine.speak(fallback)
            return
        }

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

    private fun buildCardMessage(toolName: String, result: ActionResult, text: String): ChatMessage {
        if (result is ActionResult.Failure) return ChatMessage(text = text, sender = Sender.LUI)

        return when (toolName) {
            "search_web" -> {
                val cardData = parseSearchResultsToCards(text)
                if (cardData.isNotEmpty()) {
                    ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.SEARCH_RESULTS, cardData = cardData)
                } else {
                    ChatMessage(text = text, sender = Sender.LUI)
                }
            }
            "get_health_trend" -> {
                val cardData = com.lui.app.data.ChatMessageEntity.deriveHealthTrendForBuilder(text)
                if (cardData != null && cardData.size > 1) {
                    ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.HEALTH_TREND_CHART, cardData = cardData)
                } else {
                    ChatMessage(text = text, sender = Sender.LUI)
                }
            }
            "get_health_summary", "ring_status", "ring_capabilities" -> {
                val cardData = parseHealthToCards(text)
                if (cardData.isNotEmpty()) {
                    ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.DEVICE_STATUS, cardData = cardData)
                } else {
                    ChatMessage(text = text, sender = Sender.LUI)
                }
            }
            "read_notifications", "get_digest", "get_notification_history" -> {
                val cardData = com.lui.app.data.ChatMessageEntity.deriveNotificationsForBuilder(text)
                if (cardData != null && cardData.size > 1) {
                    ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.NOTIFICATIONS, cardData = cardData)
                } else {
                    ChatMessage(text = text, sender = Sender.LUI)
                }
            }
            "get_heart_rate" -> {
                val bpm = Regex("(\\d+)\\s*BPM").find(text)?.groupValues?.get(1)?.toIntOrNull()
                val color = when {
                    bpm == null -> "#9E9E9E"
                    bpm < 60 -> "#42A5F5"
                    bpm < 100 -> "#4CAF50"
                    bpm < 140 -> "#FFC107"
                    else -> "#F44336"
                }
                ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.DEVICE_STATUS,
                    cardData = listOf(mapOf("label" to "Heart Rate", "value" to "${bpm ?: "?"} BPM", "color" to color)))
            }
            "get_spo2" -> {
                val pct = Regex("(\\d+)%").find(text)?.groupValues?.get(1)?.toIntOrNull()
                val color = when {
                    pct == null -> "#9E9E9E"
                    pct >= 95 -> "#4CAF50"
                    pct >= 90 -> "#FFC107"
                    else -> "#F44336"
                }
                ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.DEVICE_STATUS,
                    cardData = listOf(mapOf("label" to "SpO2", "value" to "${pct ?: "?"}%", "color" to color)))
            }
            "get_sleep" -> {
                val cardData = parseHealthToCards(text)
                if (cardData.isNotEmpty()) {
                    ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.DEVICE_STATUS, cardData = cardData)
                } else {
                    ChatMessage(text = text, sender = Sender.LUI)
                }
            }
            "get_activity" -> {
                val steps = Regex("Steps:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                val cals = Regex("Calories:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                val color = when {
                    steps == null -> "#9E9E9E"
                    steps >= 10000 -> "#4CAF50"
                    steps >= 5000 -> "#FFC107"
                    else -> "#42A5F5"
                }
                val cards = mutableListOf(mapOf("label" to "Steps", "value" to "${steps ?: 0}", "color" to color))
                if (cals != null && cals > 0) cards.add(mapOf("label" to "Calories", "value" to "$cals kcal", "color" to color))
                ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.DEVICE_STATUS, cardData = cards)
            }
            "get_stress" -> {
                val level = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                val label = when {
                    level == null -> "?"
                    level < 30 -> "relaxed"
                    level < 60 -> "normal"
                    level < 80 -> "moderate"
                    else -> "high"
                }
                val color = when {
                    level == null -> "#9E9E9E"
                    level < 30 -> "#4CAF50"
                    level < 60 -> "#FFC107"
                    level < 80 -> "#FF9800"
                    else -> "#F44336"
                }
                ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.DEVICE_STATUS,
                    cardData = listOf(mapOf("label" to "Stress", "value" to "${level ?: "?"} ($label)", "color" to color)))
            }
            "get_hrv" -> {
                val ms = Regex("(\\d+)\\s*ms").find(text)?.groupValues?.get(1)?.toIntOrNull()
                val color = when {
                    ms == null -> "#9E9E9E"
                    ms >= 50 -> "#4CAF50"
                    ms >= 20 -> "#FFC107"
                    else -> "#F44336"
                }
                ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.DEVICE_STATUS,
                    cardData = listOf(mapOf("label" to "HRV", "value" to "${ms ?: "?"} ms", "color" to color)))
            }
            "get_temperature" -> {
                val temp = Regex("(\\d+\\.\\d+)").find(text)?.groupValues?.get(1)?.toFloatOrNull()
                val color = when {
                    temp == null -> "#9E9E9E"
                    temp in 36.1f..37.2f -> "#4CAF50"
                    temp in 37.2f..38.0f -> "#FFC107"
                    else -> "#F44336"
                }
                val display = if (temp != null) "${"%.1f".format(temp)}°C" else "?"
                ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.DEVICE_STATUS,
                    cardData = listOf(mapOf("label" to "Temperature", "value" to display, "color" to color)))
            }
            "ring_battery" -> {
                val pct = Regex("(\\d+)%").find(text)?.groupValues?.get(1)?.toIntOrNull()
                val color = when {
                    pct == null -> "#9E9E9E"
                    pct > 60 -> "#4CAF50"
                    pct > 20 -> "#FFC107"
                    else -> "#F44336"
                }
                ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.DEVICE_STATUS,
                    cardData = listOf(mapOf("label" to "Ring Battery", "value" to "${pct ?: "?"}%", "color" to color)))
            }
            "find_ring" -> {
                ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.DEVICE_STATUS,
                    cardData = listOf(mapOf("label" to "Find Ring", "value" to "Vibrating", "color" to "#42A5F5")))
            }
            "ambient_context", "device_info" -> {
                val cardData = parseStatusToCards(text)
                if (cardData.isNotEmpty()) {
                    ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.DEVICE_STATUS, cardData = cardData)
                } else {
                    ChatMessage(text = text, sender = Sender.LUI)
                }
            }
            "battery" -> {
                val pct = Regex("(\\d+)%").find(text)?.groupValues?.get(1)?.toIntOrNull()
                val charging = text.contains("charging", true) && !text.contains("not charging", true)
                val color = when {
                    pct == null -> "#9E9E9E"
                    pct > 60 -> "#4CAF50"
                    pct > 20 -> "#FFC107"
                    else -> "#F44336"
                }
                val display = "${pct ?: "?"}%" + if (charging) " ⚡" else ""
                ChatMessage(text = text, sender = Sender.LUI, cardType = ChatMessage.CardType.DEVICE_STATUS,
                    cardData = listOf(mapOf("label" to "Battery", "value" to display, "color" to color)))
            }
            else -> ChatMessage(text = text, sender = Sender.LUI)
        }
    }

    private fun parseSearchResultsToCards(text: String): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        // Parse the formatted search results: "1. Title\n   Snippet\n   URL"
        val pattern = Regex("""(\d+)\.\s+(.+)\n\s+(.+)\n\s+(https?://\S+)""")
        for (match in pattern.findAll(text)) {
            results.add(mapOf(
                "title" to match.groupValues[2].trim(),
                "snippet" to match.groupValues[3].trim(),
                "url" to match.groupValues[4].trim()
            ))
        }
        return results
    }

    private fun parseHealthToCards(text: String): List<Map<String, String>> {
        val rows = mutableListOf<Map<String, String>>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            // Skip list items like "- Heart rate (real-time + history)" — show as plain label
            if (line.trimStart().startsWith("-")) {
                val item = line.trimStart().removePrefix("-").trim()
                if (item.isNotBlank()) rows.add(mutableMapOf("label" to item, "value" to "", "color" to "#9E9E9E"))
                continue
            }
            val parts = line.split(":", limit = 2)
            if (parts.size == 2 && parts[0].trim().isNotBlank()) {
                val label = parts[0].trim()
                val value = parts[1].trim()
                val color = when {
                    // Heart rate color coding
                    label.contains("Heart Rate", true) -> {
                        val bpm = Regex("(\\d+)").find(value)?.groupValues?.get(1)?.toIntOrNull()
                        when {
                            bpm == null -> "#9E9E9E"
                            bpm < 60 -> "#42A5F5"
                            bpm < 100 -> "#4CAF50"
                            bpm < 140 -> "#FFC107"
                            else -> "#F44336"
                        }
                    }
                    // Zone color
                    label.contains("Zone", true) -> when {
                        value.contains("resting") -> "#42A5F5"
                        value.contains("normal") -> "#4CAF50"
                        value.contains("elevated") -> "#FFC107"
                        value.contains("high") -> "#F44336"
                        else -> null
                    }
                    // Battery color
                    label.contains("Battery", true) -> {
                        val pct = Regex("(\\d+)").find(value)?.groupValues?.get(1)?.toIntOrNull()
                        when {
                            pct == null -> "#9E9E9E"
                            pct > 60 -> "#4CAF50"
                            pct > 20 -> "#FFC107"
                            else -> "#F44336"
                        }
                    }
                    // SpO2 color
                    label.contains("SpO2", true) -> {
                        val pct = Regex("(\\d+)").find(value)?.groupValues?.get(1)?.toIntOrNull()
                        when {
                            pct == null -> "#9E9E9E"
                            pct >= 95 -> "#4CAF50"
                            pct >= 90 -> "#FFC107"
                            else -> "#F44336"
                        }
                    }
                    // Stress color
                    label.contains("Stress", true) -> {
                        val lvl = Regex("(\\d+)").find(value)?.groupValues?.get(1)?.toIntOrNull()
                        when {
                            lvl == null -> "#9E9E9E"
                            lvl < 30 -> "#4CAF50"
                            lvl < 60 -> "#FFC107"
                            lvl < 80 -> "#FF9800"
                            else -> "#F44336"
                        }
                    }
                    // HRV color
                    label.contains("HRV", true) -> {
                        val ms = Regex("(\\d+)").find(value)?.groupValues?.get(1)?.toIntOrNull()
                        when {
                            ms == null -> "#9E9E9E"
                            ms >= 50 -> "#4CAF50"
                            ms >= 20 -> "#FFC107"
                            else -> "#F44336"
                        }
                    }
                    // Temperature color
                    label.contains("Temp", true) -> {
                        val temp = Regex("(\\d+\\.?\\d*)").find(value)?.groupValues?.get(1)?.toFloatOrNull()
                        when {
                            temp == null -> "#9E9E9E"
                            temp in 36.1f..37.2f -> "#4CAF50"
                            temp in 37.2f..38.0f -> "#FFC107"
                            else -> "#F44336"
                        }
                    }
                    // Steps color
                    label.contains("Steps", true) -> {
                        val s = Regex("(\\d+)").find(value)?.groupValues?.get(1)?.toIntOrNull()
                        when {
                            s == null -> "#9E9E9E"
                            s >= 10000 -> "#4CAF50"
                            s >= 5000 -> "#FFC107"
                            else -> "#42A5F5"
                        }
                    }
                    // Ring/device status
                    value.contains("connected", true) && !value.contains("not connected", true) -> "#4CAF50"
                    value.contains("not connected", true) -> "#F44336"
                    else -> null
                }
                val map = mutableMapOf("label" to label, "value" to value)
                if (color != null) map["color"] = color
                rows.add(map)
            }
        }
        return rows
    }

    private fun parseStatusToCards(text: String): List<Map<String, String>> {
        val rows = mutableListOf<Map<String, String>>()
        for (line in text.lines()) {
            val parts = line.split(":", limit = 2)
            if (parts.size == 2 && parts[0].trim().isNotBlank()) {
                val label = parts[0].trim()
                val value = parts[1].trim()
                val color = when {
                    value.contains("not charging", true) -> "#FFC107"
                    value.contains("charging", true) -> "#4CAF50"
                    value.contains("disconnected", true) -> "#F44336"
                    value.contains("Wi-Fi", true) -> "#4CAF50"
                    value.contains("off", true) -> "#9E9E9E"
                    else -> null
                }
                val map = mutableMapOf("label" to label, "value" to value)
                if (color != null) map["color"] = color
                rows.add(map)
            }
        }
        return rows
    }

    fun refreshCloudConfig() {
        _llmStatus.value = when {
            router.isUsingCloud -> "cloud"
            localModel.isReady -> "ready"
            cloudModel.isReady -> "cloud"
            else -> "no_model"
        }
    }

    fun loadLocalModel() {
        viewModelScope.launch {
            _llmStatus.value = "Loading model..."
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                localModel.initialize()
            }
            _llmStatus.value = when {
                result.isSuccess && keyStore.isCloudFirst && cloudModel.isReady -> "cloud"
                result.isSuccess -> "ready"
                cloudModel.isReady -> "cloud"
                else -> "Model failed to load"
            }
        }
    }

    private fun friendlyCloudError(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            "429" in msg || "quota" in msg.lowercase() ->
                "Cloud model is rate-limited. Falling back to local if available."
            "401" in msg || "403" in msg || "API key" in msg ->
                "Cloud API key is invalid. Check your settings."
            "Unable to resolve host" in msg || "timeout" in msg.lowercase() || "network" in msg.lowercase() ->
                "No internet. Switching to local if available."
            else -> "Something went wrong: ${e.message}"
        }
    }

    /**
     * If a cloud call failed and the local model isn't loaded yet, kick off
     * an async load. Next message will route through local.
     */
    private fun maybeLoadLocalAfterCloudFailure(e: Exception) {
        if (localModel.isReady) return
        val msg = e.message ?: ""
        val networkish = "429" in msg ||
            "Unable to resolve host" in msg ||
            "timeout" in msg.lowercase() ||
            "network" in msg.lowercase() ||
            "quota" in msg.lowercase()
        if (!networkish) return
        LuiLogger.i("ROUTE", "Cloud failed (${msg.take(60)}) — lazy-loading local model")
        loadLocalModel()
    }

    override fun onCleared() {
        generationJob?.cancel()
        localModel.close()
        voiceEngine.release()
        super.onCleared()
    }
}
