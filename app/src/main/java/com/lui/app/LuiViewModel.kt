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
import com.lui.app.helper.WallpaperHelper
import com.lui.app.interceptor.ActionExecutor
import com.lui.app.interceptor.Interceptor
import com.lui.app.interceptor.actions.ActionResult
import com.lui.app.llm.LocalModel
import com.lui.app.llm.ModelManager
import com.lui.app.llm.SystemPrompt
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
    private var generationJob: Job? = null

    init {
        addMessage(ChatMessage(
            text = application.getString(R.string.welcome_message),
            sender = Sender.WELCOME
        ))

        val prefs = application.getSharedPreferences("lui_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("wallpaper_set", false)) {
            WallpaperHelper.setLuiWallpaper(application)
            prefs.edit().putBoolean("wallpaper_set", true).apply()
        }

        // Check for model and initialize LLM
        viewModelScope.launch {
            _llmStatus.value = "Checking for model..."
            val found = ModelManager.ensureModel(application)
            if (found) {
                _llmStatus.value = "Loading model..."
                val result = localModel.initialize()
                if (result.isSuccess) {
                    _llmStatus.value = "ready"
                } else {
                    _llmStatus.value = "Model failed to load. Using keyword mode."
                    Log.e("LuiVM", "Model init failed", result.exceptionOrNull())
                }
            } else {
                _llmStatus.value = "no_model"
            }
        }
    }

    fun handleUserInput(text: String) {
        if (text.isBlank()) return

        addMessage(ChatMessage(text = text.trim(), sender = Sender.USER))

        // Keyword interceptor first — instant, reliable
        val toolCall = Interceptor.parse(text)
        if (toolCall != null) {
            val result = ActionExecutor.execute(getApplication(), toolCall)
            val response = when (result) {
                is ActionResult.Success -> result.message
                is ActionResult.Failure -> result.message
            }
            addMessage(ChatMessage(text = response, sender = Sender.LUI))
            return
        }

        // LLM for everything else
        if (localModel.isReady) {
            generateWithLlm(text)
        } else {
            addMessage(ChatMessage(
                text = "I heard you, but my brain isn't loaded yet. For now I can do: flashlight, alarms, timers, open apps, make calls, wifi/bluetooth settings.",
                sender = Sender.LUI
            ))
        }
    }

    private fun generateWithLlm(userText: String) {
        // Add streaming placeholder with pulsing dots
        addMessage(ChatMessage(text = "\u2026", sender = Sender.LUI, streaming = true))

        val responseBuilder = StringBuilder()

        generationJob = viewModelScope.launch {
            localModel.generateStreaming(userText)
                .catch { e ->
                    Log.e("LuiVM", "Generation error", e)
                    updateLastMessage("Something went wrong: ${e.message}", streaming = false)
                }
                .collect { token ->
                    responseBuilder.append(token)
                    val cleaned = SystemPrompt.cleanResponse(responseBuilder.toString())
                    if (cleaned.isNotBlank()) {
                        updateLastMessage(cleaned, streaming = true)
                    }
                }

            // Generation complete — finalize the message
            val fullResponse = SystemPrompt.cleanResponse(responseBuilder.toString())
            val llmToolCall = Interceptor.parse(fullResponse)
            if (llmToolCall != null) {
                val result = ActionExecutor.execute(getApplication(), llmToolCall)
                val actionResponse = when (result) {
                    is ActionResult.Success -> result.message
                    is ActionResult.Failure -> result.message
                }
                updateLastMessage(actionResponse, streaming = false)
            } else {
                updateLastMessage(fullResponse, streaming = false)
            }
        }
    }

    private fun addMessage(message: ChatMessage) {
        val current = _messages.value.orEmpty().toMutableList()
        current.add(message)
        _messages.value = current
        _scrollToBottom.value = Unit
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

    override fun onCleared() {
        generationJob?.cancel()
        localModel.close()
        super.onCleared()
    }
}
