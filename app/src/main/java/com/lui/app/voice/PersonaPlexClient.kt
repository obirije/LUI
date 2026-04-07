package com.lui.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import com.lui.app.helper.LuiLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-time audio-to-audio client for PersonaPlex (NVIDIA).
 *
 * Protocol (PersonaPlex moshi.server):
 * - WebSocket at /api/chat with query params: voice_prompt, text_prompt
 * - Binary framing: first byte = type tag, rest = payload
 * - Type 0x00: handshake (server→client)
 * - Type 0x01: Ogg/Opus audio (bidirectional)
 * - Type 0x02: UTF-8 text transcript (server→client)
 * - Audio: 24kHz mono Ogg/Opus pages
 */
class PersonaPlexClient {

    companion object {
        private const val TAG = "PersonaPlex"
        private const val SAMPLE_RATE = 24000
        private const val CHANNELS = 1
        private const val FRAME_SIZE_MS = 20 // 20ms Opus frames
        private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000 // 480 samples per frame
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val _userTranscript = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val userTranscript: SharedFlow<String> = _userTranscript

    private val _assistantTranscript = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val assistantTranscript: SharedFlow<String> = _assistantTranscript

    private var webSocket: WebSocketClient? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var opusEncoder: MediaCodec? = null
    private var opusDecoder: MediaCodec? = null
    private var oggWriter: OggOpusWriter? = null
    private var oggReader: OggOpusReader? = null
    private var headersSent = false
    private var recordingJob: Job? = null
    private var scope: CoroutineScope? = null
    private var handshakeReceived = false

    // Accumulate text tokens into sentences
    private val textBuffer = StringBuilder()

    // Callback for parallel STT — receives same PCM data sent to PersonaPlex
    var onPcmCaptured: ((ShortArray, Int) -> Unit)? = null

    /**
     * Connect to a PersonaPlex server.
     * @param baseUrl e.g. "https://talk.writerlm.com" or "ws://192.168.1.100:8998"
     * @param voicePrompt e.g. "NATF2.pt" — filename of voice embedding on server
     * @param textPrompt role/system prompt
     */
    fun connect(
        baseUrl: String,
        voicePrompt: String = "NATF2.pt",
        textPrompt: String = "You are LUI, a helpful phone assistant. Keep responses to 1-2 sentences."
    ) {
        if (_state.value == State.CONNECTED || _state.value == State.CONNECTING) return

        _state.value = State.CONNECTING
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        handshakeReceived = false
        textBuffer.clear()

        val wsUrl = buildWsUrl(baseUrl, voicePrompt, textPrompt)
        LuiLogger.i(TAG, "Connecting to: $wsUrl")

        try {
            webSocket = object : WebSocketClient(URI(wsUrl)) {
                override fun onOpen(handshake: ServerHandshake?) {
                    LuiLogger.i(TAG, "WebSocket open, waiting for handshake...")
                }

                override fun onMessage(message: String) {
                    // Text messages are not expected in this protocol
                    LuiLogger.d(TAG, "Text message: ${message.take(100)}")
                }

                override fun onMessage(bytes: ByteBuffer) {
                    if (bytes.remaining() < 1) return
                    val tag = bytes.get().toInt() and 0xFF
                    val payload = ByteArray(bytes.remaining())
                    bytes.get(payload)

                    when (tag) {
                        0x00 -> {
                            // Handshake
                            LuiLogger.i(TAG, "Handshake received")
                            handshakeReceived = true
                            _state.value = State.CONNECTED
                            startMicCapture()
                            startPlayback()
                        }
                        0x01 -> {
                            // Audio — Ogg/Opus pages from server
                            LuiLogger.d(TAG, "Audio frame received: ${payload.size} bytes")
                            playOpusAudio(payload)
                        }
                        0x02 -> {
                            // Text transcript
                            val text = String(payload, Charsets.UTF_8)
                            LuiLogger.d(TAG, "Text token: ${text.take(50)}")
                            handleTextToken(text)
                        }
                        else -> LuiLogger.d(TAG, "Unknown tag: $tag")
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    LuiLogger.i(TAG, "Disconnected: $reason (code=$code)")
                    _state.value = State.DISCONNECTED
                    stopInternal()
                }

                override fun onError(ex: Exception?) {
                    LuiLogger.e(TAG, "Error: ${ex?.message}", ex)
                    _state.value = State.ERROR
                }
            }

            webSocket?.connect()
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Connection failed: ${e.message}", e)
            _state.value = State.ERROR
        }
    }

    fun injectContext(text: String) {
        // PersonaPlex doesn't have a text injection mechanism in the current protocol.
        // The workaround: we'd need to send audio of the text via TTS, or wait for
        // a future protocol extension. For now, log it.
        LuiLogger.i(TAG, "Context injection (not supported in current protocol): ${text.take(60)}")
    }

    fun disconnect() {
        LuiLogger.i(TAG, "Disconnecting")
        stopInternal()
        try { webSocket?.close() } catch (_: Exception) {}
        webSocket = null
        _state.value = State.DISCONNECTED
    }

    val isConnected: Boolean get() = _state.value == State.CONNECTED

    // ── Audio capture (mic → Opus → server) ──

    @Suppress("MissingPermission")
    private fun startMicCapture() {
        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (bufSize <= 0) {
            LuiLogger.e(TAG, "Invalid AudioRecord buffer: $bufSize")
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            LuiLogger.e(TAG, "AudioRecord init failed")
            audioRecord?.release()
            audioRecord = null
            return
        }

        // Enable Acoustic Echo Cancellation — critical for full-duplex
        // Without this, the mic picks up the speaker output and creates a feedback loop
        val sessionId = audioRecord!!.audioSessionId
        if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            try {
                val aec = android.media.audiofx.AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
                LuiLogger.i(TAG, "AEC enabled (session $sessionId)")
            } catch (e: Exception) {
                LuiLogger.w(TAG, "AEC not available: ${e.message}")
            }
        } else {
            LuiLogger.w(TAG, "AEC not available on this device")
        }

        // Enable Noise Suppression
        if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
            try {
                val ns = android.media.audiofx.NoiseSuppressor.create(sessionId)
                ns?.enabled = true
                LuiLogger.i(TAG, "Noise suppressor enabled")
            } catch (e: Exception) {
                LuiLogger.w(TAG, "NS not available: ${e.message}")
            }
        }

        // Initialize Opus encoder + Ogg framer
        try {
            initOpusEncoder()
            oggWriter = OggOpusWriter(SAMPLE_RATE, CHANNELS)
            headersSent = false
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Opus encoder init failed: ${e.message}", e)
            return
        }

        audioRecord?.startRecording()
        LuiLogger.i(TAG, "Mic capture started (${SAMPLE_RATE}Hz)")

        recordingJob = scope?.launch(Dispatchers.IO) {
            val pcmBuffer = ShortArray(FRAME_SAMPLES)
            while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: break
                if (read > 0 && handshakeReceived) {
                    encodeAndSendAudio(pcmBuffer, read)
                    // Fork PCM to parallel STT for tool detection
                    onPcmCaptured?.invoke(pcmBuffer.copyOf(read), read)
                }
            }
        }
    }

    private fun initOpusEncoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 24000)
        format.setInteger(MediaFormat.KEY_COMPLEXITY, 5)

        opusEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        opusEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        opusEncoder?.start()
        LuiLogger.i(TAG, "Opus encoder started")
    }

    private fun encodeAndSendAudio(pcm: ShortArray, count: Int) {
        val encoder = opusEncoder ?: return
        val ws = webSocket ?: return
        val ogg = oggWriter ?: return

        try {
            // Send Ogg headers on first audio frame
            if (!headersSent) {
                val headers = ogg.getHeaderPages()
                val headerFrame = ByteArray(1 + headers.size)
                headerFrame[0] = 0x01
                System.arraycopy(headers, 0, headerFrame, 1, headers.size)
                ws.send(headerFrame)
                headersSent = true
                LuiLogger.i(TAG, "Sent Ogg headers (${headers.size} bytes)")
            }

            // Feed PCM to encoder
            val inputIdx = encoder.dequeueInputBuffer(5000)
            if (inputIdx >= 0) {
                val inputBuf = encoder.getInputBuffer(inputIdx) ?: return
                inputBuf.clear()
                val byteCount = count * 2
                val pcmBytes = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) pcmBytes.putShort(pcm[i])
                pcmBytes.flip()
                inputBuf.put(pcmBytes)
                encoder.queueInputBuffer(inputIdx, 0, byteCount, 0, 0)
            }

            // Read encoded Opus packets and wrap in Ogg pages
            val bufInfo = MediaCodec.BufferInfo()
            var outputIdx = encoder.dequeueOutputBuffer(bufInfo, 5000)
            while (outputIdx >= 0) {
                val outputBuf = encoder.getOutputBuffer(outputIdx) ?: break
                val opusPacket = ByteArray(bufInfo.size)
                outputBuf.get(opusPacket)
                encoder.releaseOutputBuffer(outputIdx, false)

                // Wrap in Ogg page
                val oggPage = ogg.writePacket(opusPacket, FRAME_SAMPLES)

                // Send with 0x01 tag
                val frame = ByteArray(1 + oggPage.size)
                frame[0] = 0x01
                System.arraycopy(oggPage, 0, frame, 1, oggPage.size)
                ws.send(frame)

                outputIdx = encoder.dequeueOutputBuffer(bufInfo, 0)
            }
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Encode/send error: ${e.message}", e)
        }
    }

    // ── Audio playback (server Opus → speaker) ──

    private fun startPlayback() {
        // MediaCodec Opus decoder always outputs at 48kHz regardless of input sample rate
        val playbackRate = 48000
        val minBuf = AudioTrack.getMinBufferSize(playbackRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(playbackRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            audioTrack?.play()
            LuiLogger.i(TAG, "Playback started")
        }

        // Initialize Opus decoder + Ogg reader
        try {
            oggReader = OggOpusReader()
            initOpusDecoder()
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Opus decoder init failed: ${e.message}", e)
        }
    }

    private fun initOpusDecoder() {
        // Android MediaCodec Opus decoder requires very specific CSD format:
        // CSD-0: OpusHead (19 bytes)
        // CSD-1: pre-skip value as 64-bit nanoseconds
        // CSD-2: seek pre-roll as 64-bit nanoseconds

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS)

        // CSD-0: Standard OpusHead identification header
        val csd0 = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        csd0.put("OpusHead".toByteArray()) // 8 bytes magic
        csd0.put(1)                        // version
        csd0.put(CHANNELS.toByte())        // channel count
        csd0.putShort(0)                   // pre-skip (samples)
        csd0.putInt(SAMPLE_RATE)           // input sample rate
        csd0.putShort(0)                   // output gain
        csd0.put(0)                        // channel mapping family
        csd0.flip()
        format.setByteBuffer("csd-0", csd0)

        // CSD-1: Pre-skip in nanoseconds (0)
        val csd1 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        csd1.putLong(0)
        csd1.flip()
        format.setByteBuffer("csd-1", csd1)

        // CSD-2: Seek pre-roll in nanoseconds (80ms = 80000000 ns)
        val csd2 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        csd2.putLong(80000000L)
        csd2.flip()
        format.setByteBuffer("csd-2", csd2)

        opusDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        opusDecoder?.configure(format, null, null, 0)
        opusDecoder?.start()
        LuiLogger.i(TAG, "Opus decoder started (CSD-0/1/2 configured)")
    }

    private fun playOpusAudio(oggData: ByteArray) {
        val reader = oggReader ?: return
        val decoder = opusDecoder ?: return
        val track = audioTrack ?: return

        try {
            // Extract raw Opus packets from Ogg pages
            val opusPackets = reader.feed(oggData)
            if (opusPackets.isNotEmpty()) {
                LuiLogger.d(TAG, "Decoded ${opusPackets.size} Opus packets from ${oggData.size} bytes")
            }

            for (packet in opusPackets) {
                val inputIdx = decoder.dequeueInputBuffer(5000)
                if (inputIdx >= 0) {
                    val inputBuf = decoder.getInputBuffer(inputIdx) ?: continue
                    inputBuf.clear()
                    inputBuf.put(packet)
                    decoder.queueInputBuffer(inputIdx, 0, packet.size, 0, 0)
                } else {
                    LuiLogger.w(TAG, "Decoder input buffer not available")
                    continue
                }

                val bufInfo = MediaCodec.BufferInfo()
                var outputIdx = decoder.dequeueOutputBuffer(bufInfo, 5000)
                if (outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    LuiLogger.i(TAG, "Decoder output format changed: ${decoder.outputFormat}")
                    outputIdx = decoder.dequeueOutputBuffer(bufInfo, 5000)
                }
                while (outputIdx >= 0) {
                    val outputBuf = decoder.getOutputBuffer(outputIdx) ?: break
                    val pcm = ShortArray(bufInfo.size / 2)
                    outputBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                    decoder.releaseOutputBuffer(outputIdx, false)
                    LuiLogger.d(TAG, "Playing ${pcm.size} samples")
                    track.write(pcm, 0, pcm.size)
                    outputIdx = decoder.dequeueOutputBuffer(bufInfo, 0)
                }
            }
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Decode/play error: ${e.message}", e)
        }
    }

    // ── Text handling ──

    private fun handleTextToken(token: String) {
        textBuffer.append(token)

        // Emit accumulated text when we hit sentence boundaries
        val text = textBuffer.toString()
        val lastSentenceEnd = maxOf(text.lastIndexOf('.'), text.lastIndexOf('!'), text.lastIndexOf('?'), text.lastIndexOf('\n'))

        if (lastSentenceEnd >= 0) {
            val sentence = text.substring(0, lastSentenceEnd + 1).trim()
            if (sentence.isNotBlank()) {
                scope?.launch { _assistantTranscript.emit(sentence) }
            }
            textBuffer.clear()
            if (lastSentenceEnd + 1 < text.length) {
                textBuffer.append(text.substring(lastSentenceEnd + 1))
            }
        }
    }

    // ── URL building ──

    private fun buildWsUrl(baseUrl: String, voicePrompt: String, textPrompt: String): String {
        val wsBase = when {
            baseUrl.startsWith("wss://") || baseUrl.startsWith("ws://") -> baseUrl
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> "wss://$baseUrl"
        }.trimEnd('/')

        val encodedVoice = URLEncoder.encode(voicePrompt, "UTF-8")
        val encodedPrompt = URLEncoder.encode(textPrompt, "UTF-8")
        return "$wsBase/api/chat?voice_prompt=$encodedVoice&text_prompt=$encodedPrompt"
    }

    // ── Cleanup ──

    private fun stopInternal() {
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        try { opusEncoder?.stop(); opusEncoder?.release() } catch (_: Exception) {}
        opusEncoder = null
        oggWriter = null
        headersSent = false

        try { opusDecoder?.stop(); opusDecoder?.release() } catch (_: Exception) {}
        opusDecoder = null
        oggReader = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        // Flush remaining text
        val remaining = textBuffer.toString().trim()
        if (remaining.isNotBlank()) {
            scope?.launch { _assistantTranscript.emit(remaining) }
        }
        textBuffer.clear()

        scope?.cancel()
        scope = null
    }
}
