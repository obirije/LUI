package com.lui.app.voice

import android.content.Context
import android.content.Intent
import com.lui.app.helper.LuiLogger
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoiceEngine"
        private const val TTS_SAMPLE_RATE = 24000
    }

    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _transcription = MutableSharedFlow<String>(replay = 0)
    val transcription: SharedFlow<String> = _transcription

    private val _finalTranscript = MutableSharedFlow<String>(replay = 0)
    val finalTranscript: SharedFlow<String> = _finalTranscript

    private val _autoListenStarted = MutableSharedFlow<Unit>(replay = 0)
    val autoListenStarted: SharedFlow<Unit> = _autoListenStarted

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: OfflineTts? = null
    private var cloudTts: CloudTts? = null
    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var referenceAudio: FloatArray? = null
    private var referenceSampleRate = 0

    // Two-stage pipeline: sentences go in, generated audio comes out, player plays them
    private var sentenceChannel: Channel<String>? = null
    private data class AudioChunk(val samples: FloatArray, val sampleRate: Int)
    private var audioChannel: Channel<AudioChunk>? = null
    private var producerJob: Job? = null
    private var playerJob: Job? = null

    var conversationMode = false

    // PersonaPlex real-time voice conversation
    val personaPlex = PersonaPlexClient()
    private var keyStoreRef: com.lui.app.data.SecureKeyStore? = null
    val personaPlexEnabled: Boolean get() = keyStoreRef?.personaPlexEnabled == true && !keyStoreRef?.personaPlexUrl.isNullOrBlank()

    val isReady: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun initialize(keyStore: com.lui.app.data.SecureKeyStore? = null) {
        try { initPocketTts() } catch (e: Exception) { Log.e(TAG, "Local TTS init failed", e) }
        if (keyStore != null) {
            cloudTts = CloudTts(keyStore)
            keyStoreRef = keyStore
            Log.i(TAG, "Cloud TTS available: ${cloudTts?.isEnabled}, PersonaPlex: $personaPlexEnabled")
        }
    }

    // Fall back to local TTS if cloud TTS fails repeatedly
    private var cloudTtsFailCount = 0
    val isUsingCloudTts: Boolean get() = cloudTts?.isEnabled == true && cloudTtsFailCount < 3
    private val useCloudTts: Boolean get() = isUsingCloudTts

    private fun initPocketTts() {
        val ttsDir = File(context.filesDir, "models/tts/sherpa-onnx-pocket-tts-int8-2026-01-26")
        val mainFile = File(ttsDir, "lm_main.int8.onnx")
        if (!mainFile.exists()) { Log.w(TAG, "Pocket TTS not found"); return }

        val pc = OfflineTtsPocketModelConfig()
        pc.lmMain = mainFile.absolutePath
        pc.lmFlow = File(ttsDir, "lm_flow.int8.onnx").absolutePath
        pc.encoder = File(ttsDir, "encoder.onnx").absolutePath
        pc.decoder = File(ttsDir, "decoder.int8.onnx").absolutePath
        pc.textConditioner = File(ttsDir, "text_conditioner.onnx").absolutePath
        pc.vocabJson = File(ttsDir, "vocab.json").absolutePath
        pc.tokenScoresJson = File(ttsDir, "token_scores.json").absolutePath

        val mc = OfflineTtsModelConfig(); mc.pocket = pc; mc.numThreads = 2
        val tc = OfflineTtsConfig(); tc.model = mc
        tts = OfflineTts(null, tc)

        val refFile = File(ttsDir, "test_wavs/bria.wav")
        if (refFile.exists()) loadReferenceAudio(refFile)
        Log.i(TAG, "Pocket TTS initialized")
    }

    private fun loadReferenceAudio(wavFile: File) {
        try {
            val bytes = wavFile.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(22)
            val ch = buf.short.toInt(); val sr = buf.int
            buf.position(34); val bps = buf.short.toInt()
            buf.position(12)
            while (buf.remaining() > 8) {
                val id = ByteArray(4); buf.get(id); val sz = buf.int
                if (String(id) == "data") {
                    val n = sz / (bps / 8) / ch
                    val s = FloatArray(n)
                    for (i in 0 until n) {
                        s[i] = if (bps == 16) buf.short.toFloat() / 32768f else buf.get().toFloat() / 128f
                        for (c in 1 until ch) { if (bps == 16) buf.short else buf.get() }
                    }
                    referenceAudio = s; referenceSampleRate = sr; return
                } else buf.position(buf.position() + sz)
            }
        } catch (e: Exception) { Log.e(TAG, "Ref audio failed", e) }
    }

    private fun makeConfig(): GenerationConfig {
        return GenerationConfig().also {
            it.speed = 1.15f; it.sid = 0
            it.referenceAudio = referenceAudio
            it.referenceSampleRate = referenceSampleRate
            it.referenceText = ""
        }
    }

    // ---- PersonaPlex ----

    /**
     * Start a PersonaPlex real-time conversation session.
     * Mic audio streams directly to the server, spoken responses play back immediately.
     * Returns the transcript flows for tool detection.
     */
    fun startPersonaPlex(url: String, rolePrompt: String? = null) {
        if (personaPlex.isConnected) return
        stopSpeaking()
        stopListening()

        val textPrompt = rolePrompt ?: "You are LUI (pronounced Louie), a helpful, direct, and subtly witty phone assistant. Keep responses to 1-2 sentences."
        personaPlex.connect(baseUrl = url, voicePrompt = "NATF2.pt", textPrompt = textPrompt)
        _state.value = State.LISTENING
        LuiLogger.i(TAG, "PersonaPlex conversation started → $url")
    }

    fun stopPersonaPlex() {
        personaPlex.disconnect()
        _state.value = State.IDLE
        LuiLogger.i(TAG, "PersonaPlex conversation stopped")
    }

    /**
     * Feed a tool result into PersonaPlex so it speaks the response naturally.
     */
    fun injectPersonaPlexContext(text: String) {
        if (personaPlex.isConnected) {
            personaPlex.injectContext(text)
        }
    }

    // ---- STT ----

    fun startListening() {
        if (_state.value == State.LISTENING) return
        if (_state.value == State.SPEAKING) stopSpeaking()
        _state.value = State.LISTENING
        Handler(Looper.getMainLooper()).post { createAndStartRecognizer() }
    }

    private fun createAndStartRecognizer() {
        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                speechRecognizer?.destroy(); speechRecognizer = null
                CoroutineScope(Dispatchers.Main).launch {
                    if (conversationMode && (error == 7 || error == 6)) {
                        _state.value = State.LISTENING; delay(500); createAndStartRecognizer()
                    } else _state.value = State.IDLE
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase()?.trim() ?: ""
                CoroutineScope(Dispatchers.Main).launch {
                    if (text.isNotBlank()) _finalTranscript.emit(text)
                    else if (conversationMode) { delay(300); startListening() }
                    _state.value = State.PROCESSING
                }
                speechRecognizer?.destroy(); speechRecognizer = null
            }
            override fun onPartialResults(pr: Bundle?) {
                val text = pr?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase()?.trim() ?: ""
                if (text.isNotBlank()) CoroutineScope(Dispatchers.Main).launch { _transcription.emit(text) }
            }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    fun stopListening() {
        speechRecognizer?.stopListening(); speechRecognizer?.destroy(); speechRecognizer = null
        if (_state.value == State.LISTENING) _state.value = State.PROCESSING
    }

    // ---- TTS: producer/player pipeline ----

    /**
     * Start the TTS pipeline. Call speakSentence() to feed sentences,
     * then speakDone() when all sentences are queued.
     *
     * Producer: takes sentences, generates audio (can pre-generate next while current plays)
     * Player: takes generated audio, plays sequentially
     */
    fun startPipeline() {
        stopSpeaking() // Clean up any previous pipeline

        sentenceChannel = Channel(Channel.UNLIMITED)
        audioChannel = Channel(Channel.UNLIMITED)

        // Producer: generate audio from sentences as fast as possible
        producerJob = scope.launch {
            val config = makeConfig()
            for (sentence in sentenceChannel!!) {
                if (!isActive) break
                try {
                    Log.d(TAG, "Generating: ${sentence.take(40)}...")
                    val audio = tts!!.generateWithConfig(sentence.trim(), config)
                    val sr = if (audio.sampleRate > 0) audio.sampleRate else TTS_SAMPLE_RATE
                    audioChannel!!.send(AudioChunk(audio.samples, sr))
                } catch (e: CancellationException) { break }
                catch (e: Exception) { Log.e(TAG, "TTS gen failed", e) }
            }
            audioChannel!!.close() // Signal player that no more audio coming
        }

        // Player: play audio chunks in order
        playerJob = scope.launch {
            for (chunk in audioChannel!!) {
                if (!isActive) break
                withContext(Dispatchers.Main) {
                    if (_state.value != State.SPEAKING) _state.value = State.SPEAKING
                }
                playAudio(chunk.samples, chunk.sampleRate)
            }
            // All audio played
            withContext(Dispatchers.Main) {
                _state.value = State.IDLE
                if (conversationMode) autoListen()
            }
        }
    }

    // Cloud TTS sentence queue — ensures sequential playback
    private val cloudSentenceQueue = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var cloudPlayerJob: Job? = null

    private fun ensureCloudPlayer() {
        if (cloudPlayerJob?.isActive == true) return
        cloudPlayerJob = scope.launch {
            for (sentence in cloudSentenceQueue) {
                if (!isActive) break
                if (sentence == "__CLOUD_DONE__") {
                    withContext(Dispatchers.Main) {
                        _state.value = State.IDLE
                        if (conversationMode) autoListen()
                    }
                    continue
                }
                withContext(Dispatchers.Main) {
                    if (_state.value != State.SPEAKING) _state.value = State.SPEAKING
                }
                var played = false
                cloudTts!!.speakStreaming(sentence) { track ->
                    audioTrack = track
                    if (track != null) played = true
                }
                if (!played) {
                    // Cloud TTS failed — fall back to local for this sentence and future ones
                    cloudTtsFailCount++
                    com.lui.app.helper.LuiLogger.w("TTS", "Cloud TTS failed ($cloudTtsFailCount), falling back to local")
                    if (tts != null && referenceAudio != null) {
                        // Speak locally
                        try {
                            val config = makeConfig()
                            val audio = tts!!.generateWithConfig(sentence.trim(), config)
                            val sr = if (audio.sampleRate > 0) audio.sampleRate else TTS_SAMPLE_RATE
                            withContext(Dispatchers.Main) {
                                if (_state.value != State.SPEAKING) _state.value = State.SPEAKING
                            }
                            playAudio(audio.samples, sr)
                        } catch (e: Exception) {
                            com.lui.app.helper.LuiLogger.e("TTS", "Local fallback also failed", e)
                        }
                    }
                }
            }
        }
    }

    /**
     * Queue a sentence. Routes to cloud TTS or local pipeline.
     */
    fun speakSentence(sentence: String) {
        if (sentence.isBlank()) return

        com.lui.app.helper.LuiLogger.i("TTS", "speakSentence: useCloud=$useCloudTts, cloudEnabled=${cloudTts?.isEnabled}, localTts=${tts != null}, refAudio=${referenceAudio != null}, sentence=${sentence.take(40)}")

        if (useCloudTts) {
            ensureCloudPlayer()
            cloudSentenceQueue.trySend(sentence)
            return
        }

        // Local: use producer/player pipeline
        if (tts == null || referenceAudio == null) return
        if (sentenceChannel == null || sentenceChannel!!.isClosedForSend) startPipeline()
        scope.launch { sentenceChannel?.trySend(sentence) }
    }

    /**
     * Signal all sentences queued.
     */
    fun speakDone() {
        if (useCloudTts) {
            // Queue a sentinel, then after it's consumed transition state
            scope.launch {
                // Wait for queue to drain by sending and receiving a marker
                cloudSentenceQueue.send("__CLOUD_DONE__")
            }
            return
        }
        sentenceChannel?.close()
    }

    /**
     * Speak complete text.
     */
    fun speak(text: String) {
        if (text.isBlank()) {
            _state.value = State.IDLE; if (conversationMode) autoListen(); return
        }

        com.lui.app.helper.LuiLogger.i("TTS", "speak: useCloud=$useCloudTts, localTts=${tts != null}, refAudio=${referenceAudio != null}, text=${text.take(40)}")

        if (useCloudTts) {
            scope.launch {
                withContext(Dispatchers.Main) { _state.value = State.SPEAKING }
                cloudTts!!.speakStreaming(text) { track -> audioTrack = track }
                withContext(Dispatchers.Main) {
                    _state.value = State.IDLE
                    if (conversationMode) autoListen()
                }
            }
            return
        }

        if (tts == null || referenceAudio == null) {
            _state.value = State.IDLE; if (conversationMode) autoListen(); return
        }
        startPipeline()
        scope.launch {
            sentenceChannel?.send(text)
            sentenceChannel?.close()
        }
    }

    private fun autoListen() {
        CoroutineScope(Dispatchers.Main).launch {
            delay(70)
            _autoListenStarted.emit(Unit)
            startListening()
        }
    }

    fun stopSpeaking() {
        // Cancel cloud player
        cloudPlayerJob?.cancel(); cloudPlayerJob = null
        // Cancel local pipeline
        producerJob?.cancel(); playerJob?.cancel()
        producerJob = null; playerJob = null
        sentenceChannel?.close(); audioChannel?.close()
        sentenceChannel = null; audioChannel = null
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        if (_state.value == State.SPEAKING) _state.value = State.IDLE
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        val shorts = ShortArray(samples.size) { (samples[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return

        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM).build()

        if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); return }
        audioTrack = track
        track.play()
        track.write(shorts, 0, shorts.size)
        Thread.sleep((samples.size * 1000L) / sampleRate + 5)
        track.stop(); track.release(); audioTrack = null
    }

    fun release() {
        stopListening(); stopSpeaking(); personaPlex.disconnect(); scope.cancel(); tts?.release(); tts = null
    }
}
