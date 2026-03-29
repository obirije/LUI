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

    private val localModel = LocalModel(application)
    private val keyStore = com.lui.app.data.SecureKeyStore(application)
    private val cloudModel = CloudModel(keyStore)
    private val router = LlmRouter(localModel, cloudModel, keyStore)
    private val chatRepo = ChatRepository(application)
    val voiceEngine = VoiceEngine(application)
    private var generationJob: Job? = null

    init {
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

        addMessage(ChatMessage(text = text.trim(), sender = Sender.USER))

        val toolCall = Interceptor.parse(text)
        if (toolCall != null) {
            val result = ActionExecutor.execute(getApplication(), toolCall)
            val response = when (result) {
                is ActionResult.Success -> result.message
                is ActionResult.Failure -> result.message
            }
            addMessage(ChatMessage(text = response, sender = Sender.LUI))
            // Speak action result in conversation mode
            if (voiceEngine.conversationMode) {
                voiceEngine.speak(response)
            }
            return
        }

        if (router.isReady) {
            generateWithLlm(text)
        } else {
            val msg = "I heard you, but my brain isn't loaded yet. For now I can do: flashlight, alarms, timers, open apps, make calls, wifi/bluetooth settings."
            addMessage(ChatMessage(text = msg, sender = Sender.LUI))
            if (voiceEngine.conversationMode) voiceEngine.speak(msg)
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
        val toolCall = Interceptor.parse(text)
        if (toolCall != null) {
            val result = ActionExecutor.execute(getApplication(), toolCall)
            val response = when (result) {
                is ActionResult.Success -> result.message
                is ActionResult.Failure -> result.message
            }
            addMessage(ChatMessage(text = response, sender = Sender.LUI))
            if (voiceEngine.conversationMode) voiceEngine.speak(response)
            return
        }

        if (router.isReady) {
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
                    Log.e("LuiVM", "Generation error", e)
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
            val llmToolCall = Interceptor.parse(fullResponse)
            if (llmToolCall != null) {
                val result = ActionExecutor.execute(getApplication(), llmToolCall)
                val actionResponse = when (result) {
                    is ActionResult.Success -> result.message
                    is ActionResult.Failure -> result.message
                }
                replaceLastWithLui(actionResponse, streaming = false)
                if (voiceEngine.conversationMode) voiceEngine.speak(actionResponse)
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
