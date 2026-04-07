package com.lui.app.voice

import com.lui.app.helper.LuiLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parallel STT using Deepgram's streaming WebSocket API.
 * Processes the same PCM buffer captured for PersonaPlex — no extra mic needed.
 *
 * Emits partial and final transcripts for:
 * 1. Displaying what the user said in chat
 * 2. Routing tool calls via Interceptor
 *
 * Uses the same Deepgram API key configured for cloud TTS.
 */
class ParallelStt {

    companion object {
        private const val TAG = "ParallelSTT"
    }

    private var ws: WebSocketClient? = null
    private var scope: CoroutineScope? = null

    private val _transcript = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val transcript: SharedFlow<String> = _transcript

    private val _finalTranscript = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val finalTranscript: SharedFlow<String> = _finalTranscript

    var isInitialized = false
        private set

    /**
     * Connect to Deepgram streaming STT.
     * @param apiKey Deepgram API key (same one used for TTS)
     * @param sampleRate must match the mic capture rate (24000 for PersonaPlex)
     */
    fun initialize(apiKey: String, sampleRate: Int = 24000): Boolean {
        if (isInitialized) return true
        if (apiKey.isBlank()) {
            LuiLogger.w(TAG, "No Deepgram API key — parallel STT disabled")
            return false
        }

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val url = "wss://api.deepgram.com/v1/listen" +
            "?encoding=linear16" +
            "&sample_rate=$sampleRate" +
            "&channels=1" +
            "&interim_results=true" +
            "&punctuate=true" +
            "&model=nova-3" +
            "&language=en"

        return try {
            ws = object : WebSocketClient(URI(url), mapOf("Authorization" to "Token $apiKey")) {
                override fun onOpen(handshake: ServerHandshake?) {
                    LuiLogger.i(TAG, "Deepgram STT connected")
                    isInitialized = true
                }

                override fun onMessage(message: String) {
                    try {
                        val json = JSONObject(message)
                        val channel = json.optJSONObject("channel") ?: return
                        val alternatives = channel.optJSONArray("alternatives") ?: return
                        if (alternatives.length() == 0) return

                        val transcript = alternatives.getJSONObject(0).optString("transcript", "")
                        if (transcript.isBlank()) return

                        val isFinal = json.optBoolean("is_final", false)
                        val speechFinal = json.optBoolean("speech_final", false)

                        if (isFinal || speechFinal) {
                            LuiLogger.i(TAG, "Final: $transcript")
                            scope?.launch { _finalTranscript.emit(transcript) }
                        } else {
                            scope?.launch { _transcript.emit(transcript) }
                        }
                    } catch (e: Exception) {
                        LuiLogger.e(TAG, "Parse error: ${e.message}", e)
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    LuiLogger.i(TAG, "Deepgram STT disconnected: $reason")
                    isInitialized = false
                }

                override fun onError(ex: Exception?) {
                    LuiLogger.e(TAG, "Deepgram STT error: ${ex?.message}", ex)
                }
            }

            ws?.connect()
            // Don't set isInitialized here — wait for onOpen
            true
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Failed to connect: ${e.message}", e)
            false
        }
    }

    /**
     * Feed PCM samples from the mic capture loop.
     * Same buffer sent to PersonaPlex — just forked here.
     */
    fun feedPcm(samples: ShortArray, count: Int) {
        val socket = ws ?: return
        if (!socket.isOpen) return

        // Convert shorts to little-endian bytes
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(samples[i])
        socket.send(bytes.array())
    }

    fun release() {
        try {
            // Send close message to Deepgram
            ws?.send("{\"type\": \"CloseStream\"}")
            ws?.close()
        } catch (_: Exception) {}
        ws = null
        scope?.cancel()
        scope = null
        isInitialized = false
    }
}
