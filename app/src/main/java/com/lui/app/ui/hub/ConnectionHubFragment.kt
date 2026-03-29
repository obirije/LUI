package com.lui.app.ui.hub

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.lui.app.LuiViewModel
import com.lui.app.R
import com.lui.app.data.SecureKeyStore
import com.lui.app.databinding.FragmentConnectionHubBinding
import com.lui.app.llm.CloudProvider
import com.lui.app.llm.SpeechProvider
import com.lui.app.voice.CloudTts
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class ConnectionHubFragment : Fragment() {

    private var _binding: FragmentConnectionHubBinding? = null
    private val binding get() = _binding!!
    private lateinit var keyStore: SecureKeyStore
    private lateinit var viewModel: LuiViewModel

    private data class VoiceOption(val id: String, val name: String) {
        override fun toString() = name
    }
    private var voices = mutableListOf<VoiceOption>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConnectionHubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        keyStore = SecureKeyStore(requireContext())
        viewModel = ViewModelProvider(requireActivity())[LuiViewModel::class.java]

        loadConfig()
        setupListeners()
        updateStatus()

        // Auto-scroll to focused field when keyboard opens
        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) binding.hubScrollView.post { binding.hubScrollView.smoothScrollTo(0, v.top - 100) }
        }
        binding.apiKeyField.onFocusChangeListener = focusListener
        binding.speechKeyField.onFocusChangeListener = focusListener
    }

    private fun loadConfig() {
        // LLM
        when (keyStore.selectedProvider) {
            CloudProvider.GEMINI -> binding.rbGemini.isChecked = true
            CloudProvider.CLAUDE -> binding.rbClaude.isChecked = true
            CloudProvider.OPENAI -> binding.rbOpenAI.isChecked = true
            null -> {}
        }
        keyStore.selectedProvider?.let { binding.apiKeyField.setText(keyStore.getApiKey(it) ?: "") }
        binding.cloudFirstSwitch.isChecked = keyStore.isCloudFirst

        // Speech
        binding.cloudSpeechSwitch.isChecked = keyStore.cloudSpeechEnabled
        when (keyStore.speechProvider) {
            SpeechProvider.DEEPGRAM -> binding.rbDeepgram.isChecked = true
            SpeechProvider.ELEVENLABS -> binding.rbElevenlabs.isChecked = true
        }
        binding.speechKeyField.setText(keyStore.getSpeechKey(keyStore.speechProvider) ?: "")
        setSpeechFieldsEnabled(keyStore.cloudSpeechEnabled)

        // Load available voices
        if (keyStore.cloudSpeechEnabled && keyStore.getSpeechKey(keyStore.speechProvider) != null) {
            fetchVoices()
        }
    }

    private fun setupListeners() {
        // LLM
        binding.providerGroup.setOnCheckedChangeListener { _, id ->
            val p = when (id) { R.id.rbGemini -> CloudProvider.GEMINI; R.id.rbClaude -> CloudProvider.CLAUDE; R.id.rbOpenAI -> CloudProvider.OPENAI; else -> null }
            keyStore.selectedProvider = p
            p?.let { binding.apiKeyField.setText(keyStore.getApiKey(it) ?: "") }
            updateStatus(); viewModel.refreshCloudConfig()
        }
        binding.apiKeyField.addTextChangedListener(watcher {
            keyStore.selectedProvider?.let { keyStore.setApiKey(it, binding.apiKeyField.text.toString()) }
            updateStatus(); viewModel.refreshCloudConfig()
        })
        binding.cloudFirstSwitch.setOnCheckedChangeListener { _, c -> keyStore.isCloudFirst = c; viewModel.refreshCloudConfig() }

        // Speech
        binding.cloudSpeechSwitch.setOnCheckedChangeListener { _, c ->
            keyStore.cloudSpeechEnabled = c; setSpeechFieldsEnabled(c); updateStatus()
        }
        binding.speechProviderGroup.setOnCheckedChangeListener { _, id ->
            val p = when (id) { R.id.rbDeepgram -> SpeechProvider.DEEPGRAM; R.id.rbElevenlabs -> SpeechProvider.ELEVENLABS; else -> SpeechProvider.DEEPGRAM }
            keyStore.speechProvider = p
            binding.speechKeyField.setText(keyStore.getSpeechKey(p) ?: "")
            keyStore.selectedVoiceId = null // Reset voice when provider changes
            updateStatus()
            fetchVoices()
        }
        binding.speechKeyField.addTextChangedListener(watcher {
            keyStore.setSpeechKey(keyStore.speechProvider, binding.speechKeyField.text.toString())
            updateStatus()
            // Refresh voices when key changes
            fetchVoices()
        })

        binding.voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position < voices.size) {
                    keyStore.selectedVoiceId = voices[position].id
                    keyStore.selectedVoiceName = voices[position].name
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Test all
        binding.btnTest.setOnClickListener { testAllConnections() }
    }

    private fun fetchVoices() {
        val provider = keyStore.speechProvider
        val key = keyStore.getSpeechKey(provider) ?: return

        binding.voiceLabel.visibility = android.view.View.VISIBLE
        binding.voiceSpinner.visibility = android.view.View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                when (provider) {
                    SpeechProvider.DEEPGRAM -> getDeepgramVoices()
                    SpeechProvider.ELEVENLABS -> getElevenLabsVoices(key.trim())
                }
            }

            voices.clear()
            voices.addAll(fetched)

            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, voices)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.voiceSpinner.adapter = adapter

            // Select saved voice
            val savedId = keyStore.selectedVoiceId
            val idx = voices.indexOfFirst { it.id == savedId }
            if (idx >= 0) binding.voiceSpinner.setSelection(idx)
        }
    }

    private fun getDeepgramVoices(): List<VoiceOption> {
        return listOf(
            VoiceOption("aura-2-thalia-en", "Thalia (Female)"),
            VoiceOption("aura-2-andromeda-en", "Andromeda (Female)"),
            VoiceOption("aura-2-asteria-en", "Asteria (Female)"),
            VoiceOption("aura-2-luna-en", "Luna (Female)"),
            VoiceOption("aura-2-athena-en", "Athena (Female)"),
            VoiceOption("aura-2-arcas-en", "Arcas (Male)"),
            VoiceOption("aura-2-helios-en", "Helios (Male)"),
            VoiceOption("aura-2-orion-en", "Orion (Male)"),
            VoiceOption("aura-2-perseus-en", "Perseus (Male)"),
            VoiceOption("aura-2-angus-en", "Angus (Male)"),
            VoiceOption("aura-2-orpheus-en", "Orpheus (Male)"),
            VoiceOption("aura-2-zeus-en", "Zeus (Male)"),
        )
    }

    private fun getElevenLabsVoices(key: String): List<VoiceOption> {
        return try {
            val conn = (URL("https://api.elevenlabs.io/v1/voices").openConnection() as java.net.HttpURLConnection).apply {
                setRequestProperty("xi-api-key", key)
                connectTimeout = 10000
            }
            if (conn.responseCode != 200) {
                conn.disconnect()
                return emptyList()
            }
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val voicesArray = JSONObject(response).optJSONArray("voices") ?: return emptyList()
            val result = mutableListOf<VoiceOption>()
            for (i in 0 until voicesArray.length()) {
                val v = voicesArray.getJSONObject(i)
                val name = v.optString("name", "Unknown")
                val id = v.optString("voice_id", "")
                val category = v.optString("category", "")
                // Only show voices the user can actually use:
                // "premade" (default), "cloned", "generated", "professional"
                // Skip "library" voices — they require paid plan via API
                if (id.isNotBlank() && category != "library") {
                    result.add(VoiceOption(id, "$name ($category)"))
                }
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun setSpeechFieldsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.4f
        binding.speechProviderGroup.alpha = alpha
        binding.speechKeyField.alpha = alpha
        for (i in 0 until binding.speechProviderGroup.childCount) binding.speechProviderGroup.getChildAt(i).isEnabled = enabled
        binding.speechKeyField.isEnabled = enabled
    }

    private fun updateStatus() {
        val results = mutableListOf<String>()

        val llmProvider = keyStore.selectedProvider
        val llmKey = llmProvider?.let { keyStore.getApiKey(it) }
        if (llmProvider != null && llmKey != null) results.add("green:${llmProvider.displayName} configured")

        if (keyStore.cloudSpeechEnabled && keyStore.getSpeechKey(keyStore.speechProvider) != null)
            results.add("green:${keyStore.speechProvider.displayName} configured")

        if (results.isEmpty()) {
            binding.hubStatusText.text = "No connections configured"
        } else {
            showColoredResults(results)
        }
    }

    private fun testAllConnections() {
        binding.btnTest.isEnabled = false
        binding.hubStatusText.text = "Testing..."

        viewLifecycleOwner.lifecycleScope.launch {
            val results = mutableListOf<String>()

            // Test LLM
            val llmProvider = keyStore.selectedProvider
            val llmKey = llmProvider?.let { keyStore.getApiKey(it) }
            if (llmProvider != null && llmKey != null) {
                val ok = withContext(Dispatchers.IO) { testLlm(llmProvider, llmKey) }
                results.add(if (ok) "green:${llmProvider.displayName} connected" else "red:${llmProvider.displayName} failed")
            }

            // Test Speech
            if (keyStore.cloudSpeechEnabled && keyStore.getSpeechKey(keyStore.speechProvider) != null) {
                val cloudTts = CloudTts(keyStore)
                val ok = cloudTts.testConnection()
                results.add(if (ok) "green:${keyStore.speechProvider.displayName} connected" else "red:${keyStore.speechProvider.displayName} failed")
            }

            binding.btnTest.isEnabled = true
            if (results.isEmpty()) {
                binding.hubStatusText.text = "Nothing to test"
            } else {
                showColoredResults(results)
            }
        }
    }

    private fun testLlm(provider: CloudProvider, key: String): Boolean {
        return try {
            val conn = when (provider) {
                CloudProvider.GEMINI -> (URL("${CloudProvider.GEMINI.endpoint}/${provider.defaultModel}:generateContent?key=$key").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 10000
                    outputStream.write("""{"contents":[{"parts":[{"text":"hi"}]}]}""".toByteArray())
                }
                CloudProvider.CLAUDE -> (URL(CloudProvider.CLAUDE.endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); setRequestProperty("x-api-key", key); setRequestProperty("anthropic-version", "2023-06-01"); doOutput = true; connectTimeout = 10000
                    outputStream.write("""{"model":"${provider.defaultModel}","max_tokens":10,"messages":[{"role":"user","content":"hi"}]}""".toByteArray())
                }
                CloudProvider.OPENAI -> (URL(CloudProvider.OPENAI.endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); setRequestProperty("Authorization", "Bearer $key"); doOutput = true; connectTimeout = 10000
                    outputStream.write("""{"model":"${provider.defaultModel}","max_tokens":10,"messages":[{"role":"user","content":"hi"}]}""".toByteArray())
                }
            }
            val code = conn.responseCode; conn.disconnect(); code in 200..299
        } catch (e: Exception) { false }
    }

    private fun showColoredResults(results: List<String>) {
        val ssb = SpannableStringBuilder()
        for ((i, result) in results.withIndex()) {
            val isGreen = result.startsWith("green:")
            val text = result.substringAfter(":")
            val dot = "\u25CF "  // Filled circle
            val start = ssb.length
            ssb.append(dot)
            val color = if (isGreen) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
            ssb.setSpan(ForegroundColorSpan(color), start, start + dot.length, 0)
            ssb.append(text)
            if (i < results.lastIndex) ssb.append("\n")
        }
        binding.hubStatusText.text = ssb
    }

    private fun watcher(action: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { action() }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
