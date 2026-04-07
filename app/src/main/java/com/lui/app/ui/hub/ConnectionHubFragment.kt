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
import com.lui.app.bridge.AgentRegistry
import com.lui.app.bridge.BridgeProtocol
import com.lui.app.bridge.LuiBridgeService
import com.lui.app.bridge.LuiBridgeServer
import com.lui.app.llm.CloudProvider
import com.lui.app.llm.ModelDownloader
import com.lui.app.llm.LocalModel
import com.lui.app.llm.SpeechProvider
import com.lui.app.voice.CloudTts
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ConnectionHubFragment : Fragment() {

    private var _binding: FragmentConnectionHubBinding? = null
    private val binding get() = _binding!!
    private lateinit var keyStore: SecureKeyStore
    private lateinit var viewModel: LuiViewModel

    private var bridgeStateListener: ((Boolean) -> Unit)? = null
    private var downloadJob: Job? = null

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
        updateLocalModelStatus()

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
            CloudProvider.OLLAMA -> binding.rbOllama.isChecked = true
            null -> {}
        }
        keyStore.selectedProvider?.let { binding.apiKeyField.setText(keyStore.getApiKey(it) ?: "") }
        binding.cloudFirstSwitch.isChecked = keyStore.isCloudFirst
        // Ollama
        binding.ollamaEndpointField.setText(keyStore.ollamaEndpoint ?: "")
        binding.ollamaModelField.setText(keyStore.ollamaModel ?: "")
        updateOllamaFieldsVisibility(keyStore.selectedProvider == CloudProvider.OLLAMA)

        // Wake word
        binding.wakeWordSwitch.isChecked = com.lui.app.voice.WakeWordService.isRunning
        binding.wakeWordSwitch.setOnCheckedChangeListener { _, enabled ->
            val intent = android.content.Intent(requireContext(), com.lui.app.voice.WakeWordService::class.java)
            if (enabled) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(intent)
                } else {
                    requireContext().startService(intent)
                }
            } else {
                requireContext().stopService(intent)
            }
        }

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

        // Bridge — observe state changes (e.g., when LLM starts/stops it)
        bridgeStateListener = { running ->
            binding.root.post {
                binding.bridgeSwitch.setOnCheckedChangeListener(null) // prevent loop
                binding.bridgeSwitch.isChecked = running
                updateBridgeUI(running)
                setupBridgeSwitchListener()
            }
        }

        // Refresh agents list periodically when hub is open
        binding.root.postDelayed(object : Runnable {
            override fun run() {
                if (_binding != null && LuiBridgeService.isRunning) {
                    updateAgentsList()
                    binding.root.postDelayed(this, 3000)
                }
            }
        }, 3000)
        LuiBridgeService.addStateListener(bridgeStateListener!!)

        binding.bridgeSwitch.isChecked = LuiBridgeService.isRunning
        updateBridgeUI(LuiBridgeService.isRunning)
        when (keyStore.bridgePermissionTier) {
            "READ_ONLY" -> binding.rbReadOnly.isChecked = true
            "STANDARD" -> binding.rbStandard.isChecked = true
            "FULL" -> binding.rbFull.isChecked = true
        }
    }

    private fun setupListeners() {
        // LLM
        binding.providerGroup.setOnCheckedChangeListener { _, id ->
            val p = when (id) {
                R.id.rbGemini -> CloudProvider.GEMINI
                R.id.rbClaude -> CloudProvider.CLAUDE
                R.id.rbOpenAI -> CloudProvider.OPENAI
                R.id.rbOllama -> CloudProvider.OLLAMA
                else -> null
            }
            keyStore.selectedProvider = p
            val isOllama = p == CloudProvider.OLLAMA
            updateOllamaFieldsVisibility(isOllama)
            if (!isOllama) {
                p?.let { binding.apiKeyField.setText(keyStore.getApiKey(it) ?: "") }
            }
            updateStatus(); viewModel.refreshCloudConfig()
        }
        binding.apiKeyField.addTextChangedListener(watcher {
            keyStore.selectedProvider?.let { keyStore.setApiKey(it, binding.apiKeyField.text.toString()) }
            updateStatus(); viewModel.refreshCloudConfig()
        })
        binding.ollamaEndpointField.addTextChangedListener(watcher {
            keyStore.ollamaEndpoint = binding.ollamaEndpointField.text.toString()
            updateStatus(); viewModel.refreshCloudConfig()
        })
        binding.ollamaModelField.addTextChangedListener(watcher {
            keyStore.ollamaModel = binding.ollamaModelField.text.toString()
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

        // PersonaPlex
        binding.personaPlexSwitch.isChecked = keyStore.personaPlexEnabled
        binding.personaPlexUrlField.setText(keyStore.personaPlexUrl ?: "")
        binding.personaPlexRoleField.setText(keyStore.personaPlexRole ?: "")
        binding.personaPlexSwitch.setOnCheckedChangeListener { _, enabled ->
            keyStore.personaPlexEnabled = enabled
            updatePersonaPlexStatus()
        }
        binding.personaPlexUrlField.addTextChangedListener(watcher {
            keyStore.personaPlexUrl = binding.personaPlexUrlField.text.toString()
            updatePersonaPlexStatus()
        })
        binding.personaPlexRoleField.addTextChangedListener(watcher {
            keyStore.personaPlexRole = binding.personaPlexRoleField.text.toString()
        })

        // Voice selector
        val ppVoices = listOf(
            "NATF0.pt" to "Natural Female 1",
            "NATF1.pt" to "Natural Female 2",
            "NATF2.pt" to "Natural Female 3",
            "NATF3.pt" to "Natural Female 4",
            "NATM0.pt" to "Natural Male 1",
            "NATM1.pt" to "Natural Male 2",
            "NATM2.pt" to "Natural Male 3",
            "NATM3.pt" to "Natural Male 4",
            "VARF0.pt" to "Variety Female 1",
            "VARF1.pt" to "Variety Female 2",
            "VARF2.pt" to "Variety Female 3",
            "VARF3.pt" to "Variety Female 4",
            "VARF4.pt" to "Variety Female 5",
            "VARM0.pt" to "Variety Male 1",
            "VARM1.pt" to "Variety Male 2",
            "VARM2.pt" to "Variety Male 3",
            "VARM3.pt" to "Variety Male 4",
            "VARM4.pt" to "Variety Male 5",
        )
        val ppVoiceNames = ppVoices.map { it.second }
        val ppAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, ppVoiceNames)
        ppAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.personaPlexVoiceSpinner.adapter = ppAdapter
        val savedVoice = keyStore.personaPlexVoice
        val savedIdx = ppVoices.indexOfFirst { it.first == savedVoice }
        if (savedIdx >= 0) binding.personaPlexVoiceSpinner.setSelection(savedIdx)
        binding.personaPlexVoiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                keyStore.personaPlexVoice = ppVoices[position].first
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updatePersonaPlexStatus()

        // Bridge
        setupBridgeSwitchListener()

        // Relay
        binding.relaySwitch.isChecked = keyStore.relayEnabled
        binding.relayUrlField.setText(keyStore.relayUrl ?: SecureKeyStore.DEFAULT_RELAY_URL)
        binding.relayUrlField.addTextChangedListener(watcher {
            keyStore.relayUrl = binding.relayUrlField.text.toString()
        })
        binding.relaySwitch.setOnCheckedChangeListener { _, enabled ->
            keyStore.relayEnabled = enabled
            updateRelayStatus()
        }

        binding.tierGroup.setOnCheckedChangeListener { _, id ->
            val tier = when (id) {
                R.id.rbReadOnly -> "READ_ONLY"
                R.id.rbStandard -> "STANDARD"
                R.id.rbFull -> "FULL"
                else -> "STANDARD"
            }
            keyStore.bridgePermissionTier = tier
            BridgeProtocol.currentTier = BridgeProtocol.BridgeTier.valueOf(tier)
            updateTierDescription(tier)
        }

        binding.bridgeTokenText.setOnClickListener {
            val token = LuiBridgeService.getAuthToken(requireContext())
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Bridge Token", token))
            binding.bridgeTokenText.text = "Token copied!"
            binding.bridgeTokenText.postDelayed({
                binding.bridgeTokenText.text = "Token: $token (tap to copy)"
            }, 2000)
        }

        // Local model download
        binding.btnDownloadModel.setOnClickListener { startModelDownload() }
        binding.btnCancelDownload.setOnClickListener { cancelModelDownload() }
        binding.btnDeleteModel.setOnClickListener { deleteModel() }

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

    private fun updateOllamaFieldsVisibility(isOllama: Boolean) {
        val vis = if (isOllama) View.VISIBLE else View.GONE
        binding.ollamaEndpointField.visibility = vis
        binding.ollamaModelField.visibility = vis
        // Hide API key for Ollama (no key needed)
        binding.apiKeyField.visibility = if (isOllama) View.GONE else View.VISIBLE
    }

    private fun setupBridgeSwitchListener() {
        binding.bridgeSwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled) {
                val intent = android.content.Intent(requireContext(), LuiBridgeService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(intent)
                } else {
                    requireContext().startService(intent)
                }
                binding.root.postDelayed({ updateBridgeUI(true) }, 1000)
            } else {
                requireContext().stopService(android.content.Intent(requireContext(), LuiBridgeService::class.java))
                updateBridgeUI(false)
            }
        }
    }

    private fun updateAgentsList() {
        val agents = AgentRegistry.registeredAgents
        binding.agentsLabel.visibility = View.VISIBLE
        binding.agentsListText.visibility = View.VISIBLE
        if (agents.isEmpty()) {
            binding.agentsListText.text = "No agents connected"
            binding.agentsListText.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_gray_dark))
        } else {
            val sb = StringBuilder()
            for (a in agents) {
                val status = if (a.conn.isOpen) "\u25CF" else "\u25CB" // filled/empty circle
                sb.appendLine("$status ${a.name} — ${a.description}")
                if (a.capabilities.isNotEmpty()) {
                    sb.appendLine("  ${a.capabilities.joinToString(", ")}")
                }
            }
            binding.agentsListText.text = sb.toString().trim()
            binding.agentsListText.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_white))
        }
    }

    private fun updateRelayStatus() {
        val connected = LuiBridgeService.isRelayConnected
        val enabled = keyStore.relayEnabled
        if (enabled && connected) {
            binding.relayStatusText.text = "Relay connected"
            binding.relayStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_green))
        } else if (enabled) {
            binding.relayStatusText.text = "Relay enabled — will connect when bridge restarts"
            binding.relayStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_amber))
        } else {
            binding.relayStatusText.text = "Relay disabled"
            binding.relayStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_gray_dark))
        }
        binding.relayStatusText.visibility = View.VISIBLE
    }

    private fun updateBridgeUI(running: Boolean) {
        val views = listOf(binding.bridgeStatusText, binding.bridgeUrlText, binding.bridgeTokenText,
            binding.tierLabel, binding.tierGroup, binding.tierDescription)

        val relayViews = listOf(binding.relaySection, binding.relayDescription,
            binding.relayToggleRow, binding.relayUrlField, binding.relayStatusText)

        if (running) {
            views.forEach { it.visibility = View.VISIBLE }
            relayViews.forEach { it.visibility = View.VISIBLE }
            updateRelayStatus()
            updateAgentsList()
            val url = LuiBridgeService.getConnectionUrl(requireContext()) ?: "ws://unknown:${LuiBridgeServer.DEFAULT_PORT}"
            val token = LuiBridgeService.getAuthToken(requireContext())
            val tier = keyStore.bridgePermissionTier

            binding.bridgeStatusText.text = "Bridge active"
            binding.bridgeStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_green))
            binding.bridgeUrlText.text = url
            binding.bridgeTokenText.text = "Token: $token (tap to copy)"
            updateTierDescription(tier)
        } else {
            views.forEach { it.visibility = View.GONE }
            relayViews.forEach { it.visibility = View.GONE }
            binding.agentsLabel.visibility = View.GONE
            binding.agentsListText.visibility = View.GONE
        }
    }

    private fun updateTierDescription(tier: String) {
        val desc = when (tier) {
            "READ_ONLY" -> "16 tools: device state, sensors, battery, time, location. No side effects."
            "STANDARD" -> "44 tools: + controls, navigation, apps, read personal data. Restricted tools prompt on-device."
            "FULL" -> "70 tools: everything including SMS, calls, screen control. Use with trusted agents only."
            else -> ""
        }
        binding.tierDescription.text = desc
        binding.tierDescription.visibility = View.VISIBLE
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
        if (llmProvider == CloudProvider.OLLAMA && keyStore.ollamaEndpoint != null) {
            val model = keyStore.ollamaModel ?: llmProvider.defaultModel
            results.add("green:Ollama ($model) configured")
        } else {
            val llmKey = llmProvider?.let { keyStore.getApiKey(it) }
            if (llmProvider != null && llmKey != null) results.add("green:${llmProvider.displayName} configured")
        }

        if (keyStore.cloudSpeechEnabled && keyStore.getSpeechKey(keyStore.speechProvider) != null)
            results.add("green:${keyStore.speechProvider.displayName} configured")

        if (results.isEmpty()) {
            binding.hubStatusText.text = "No connections configured"
        } else {
            showColoredResults(results)
        }
    }

    private fun updateLocalModelStatus() {
        val modelsDir = File(requireContext().filesDir, "models")
        val modelFile = File(modelsDir, LocalModel.MODEL_FILENAME)
        val partFile = File(modelsDir, "${LocalModel.MODEL_FILENAME}.part")

        if (modelFile.exists() && modelFile.length() > 1_000_000) {
            val sizeMb = "%.1f".format(modelFile.length() / 1_048_576.0)
            binding.localModelStatus.text = "\u25CF ${LocalModel.MODEL_FILENAME} (${sizeMb}MB)"
            binding.localModelStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_green))
            binding.btnDownloadModel.visibility = View.GONE
            binding.btnDeleteModel.visibility = View.VISIBLE
            binding.btnCancelDownload.visibility = View.GONE
            binding.modelProgressBar.visibility = View.GONE
            binding.modelProgressText.visibility = View.GONE
        } else if (partFile.exists() && partFile.length() > 0) {
            val sizeMb = "%.1f".format(partFile.length() / 1_048_576.0)
            binding.localModelStatus.text = "Partial download: ${sizeMb}MB"
            binding.localModelStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_amber))
            binding.btnDownloadModel.text = "Resume"
            binding.btnDownloadModel.visibility = View.VISIBLE
            binding.btnDeleteModel.visibility = View.GONE
            binding.btnCancelDownload.visibility = View.GONE
        } else {
            binding.localModelStatus.text = "No local model installed"
            binding.localModelStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_gray_dark))
            binding.btnDownloadModel.text = "Download"
            binding.btnDownloadModel.visibility = View.VISIBLE
            binding.btnDeleteModel.visibility = View.GONE
            binding.btnCancelDownload.visibility = View.GONE
        }
    }

    private fun startModelDownload() {
        binding.btnDownloadModel.visibility = View.GONE
        binding.btnCancelDownload.visibility = View.VISIBLE
        binding.btnDeleteModel.visibility = View.GONE
        binding.modelProgressBar.visibility = View.VISIBLE
        binding.modelProgressBar.progress = 0
        binding.modelProgressText.visibility = View.VISIBLE
        binding.modelProgressText.text = "Connecting..."
        binding.localModelStatus.text = "Downloading..."
        binding.localModelStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_blue))

        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            ModelDownloader.downloadModel(
                requireContext(),
                url = ModelDownloader.DEFAULT_MODEL_URL,
                filename = ModelDownloader.DEFAULT_MODEL_FILENAME
            ).collect { progress ->
                if (progress.error != null) {
                    binding.localModelStatus.text = "Error: ${progress.error}"
                    binding.localModelStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_amber))
                    binding.modelProgressBar.visibility = View.GONE
                    binding.modelProgressText.visibility = View.GONE
                    binding.btnCancelDownload.visibility = View.GONE
                    binding.btnDownloadModel.visibility = View.VISIBLE
                    binding.btnDownloadModel.text = "Retry"
                    return@collect
                }

                if (progress.done) {
                    updateLocalModelStatus()
                    // Trigger model load
                    viewModel.loadLocalModel()
                    return@collect
                }

                binding.modelProgressBar.progress = progress.percent
                binding.modelProgressText.text = "${progress.megabytesDownloaded}/${progress.totalMegabytes} MB  •  ${progress.speedMbps} MB/s  •  ${progress.percent}%"
            }
        }
    }

    private fun cancelModelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        binding.btnCancelDownload.visibility = View.GONE
        binding.modelProgressBar.visibility = View.GONE
        binding.modelProgressText.visibility = View.GONE
        updateLocalModelStatus()
    }

    private fun deleteModel() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete Local Model")
            .setMessage("Remove ${LocalModel.MODEL_FILENAME} from device?")
            .setPositiveButton("Delete") { _, _ ->
                ModelDownloader.deleteModel(requireContext())
                updateLocalModelStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePersonaPlexStatus() {
        val enabled = keyStore.personaPlexEnabled
        val url = keyStore.personaPlexUrl
        if (enabled && !url.isNullOrBlank()) {
            binding.personaPlexStatus.text = "PersonaPlex enabled — long-press mic to start conversation"
            binding.personaPlexStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_green))
        } else if (enabled) {
            binding.personaPlexStatus.text = "Enter server URL to connect"
            binding.personaPlexStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_amber))
        } else {
            binding.personaPlexStatus.text = "Using standard voice pipeline (STT → LLM → TTS)"
            binding.personaPlexStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.lui_gray_dark))
        }
    }

    private fun testAllConnections() {
        binding.btnTest.isEnabled = false
        binding.hubStatusText.text = "Testing..."

        viewLifecycleOwner.lifecycleScope.launch {
            val results = mutableListOf<String>()

            // Test LLM
            val llmProvider = keyStore.selectedProvider
            if (llmProvider != null) {
                val llmKey = keyStore.getApiKey(llmProvider) ?: ""
                // Ollama doesn't need a key, just needs endpoint configured
                val shouldTest = llmProvider == CloudProvider.OLLAMA || llmKey.isNotBlank()
                if (shouldTest) {
                    val ok = withContext(Dispatchers.IO) { testLlm(llmProvider, llmKey) }
                    results.add(if (ok) "green:${llmProvider.displayName} connected" else "red:${llmProvider.displayName} failed")
                }
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
                CloudProvider.OLLAMA -> {
                    val baseUrl = keyStore.ollamaEndpoint ?: provider.endpoint
                    val endpoint = if (baseUrl.contains("/chat/completions")) baseUrl
                                   else "${baseUrl.trimEnd('/')}/v1/chat/completions"
                    val model = keyStore.ollamaModel ?: provider.defaultModel
                    (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true; connectTimeout = 10000
                        outputStream.write("""{"model":"$model","max_tokens":50,"messages":[{"role":"user","content":"hi"}]}""".toByteArray())
                    }
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

    override fun onDestroyView() {
        bridgeStateListener?.let { LuiBridgeService.removeStateListener(it) }
        bridgeStateListener = null
        super.onDestroyView()
        _binding = null
    }
}
