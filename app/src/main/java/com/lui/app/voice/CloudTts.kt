package com.lui.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.lui.app.data.SecureKeyStore
import com.lui.app.llm.SpeechProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CloudTts(private val keyStore: SecureKeyStore) {

    companion object {
        private const val TAG = "CloudTts"
    }

    val isEnabled: Boolean get() = keyStore.hasCloudSpeechConfigured

    suspend fun speakStreaming(text: String, audioTrackRef: (AudioTrack?) -> Unit) = withContext(Dispatchers.IO) {
        val provider = keyStore.speechProvider
        val key = keyStore.getSpeechKey(provider) ?: return@withContext

        try {
            com.lui.app.helper.LuiLogger.i("TTS", "Cloud TTS: provider=$provider, voice=${keyStore.selectedVoiceId}, text=${text.take(40)}")
            when (provider) {
                SpeechProvider.DEEPGRAM -> speakDeepgram(text, key, audioTrackRef)
                SpeechProvider.ELEVENLABS -> speakElevenLabs(text, key, audioTrackRef)
            }
            com.lui.app.helper.LuiLogger.i("TTS", "Cloud TTS completed")
        } catch (e: Exception) {
            com.lui.app.helper.LuiLogger.e("TTS", "Cloud TTS failed: ${e.message}", e)
        }
    }

    private fun speakDeepgram(text: String, key: String, audioTrackRef: (AudioTrack?) -> Unit) {
        val voice = keyStore.selectedVoiceId ?: "aura-2-thalia-en"
        val url = URL("https://api.deepgram.com/v1/speak?model=$voice&encoding=linear16&sample_rate=24000")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Token $key")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 10000
        }
        conn.outputStream.write(JSONObject().put("text", text).toString().toByteArray())

        val code = conn.responseCode
        com.lui.app.helper.LuiLogger.d("TTS", "Deepgram response code: $code")
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText()
            com.lui.app.helper.LuiLogger.e("TTS", "Deepgram TTS error $code: ${err?.take(200)}")
            conn.disconnect()
            return
        }

        streamPcmResponse(conn.inputStream, 24000, audioTrackRef)
        conn.disconnect()
    }

    private var elevenLabsVoiceId: String? = null

    private fun getElevenLabsVoiceId(key: String): String {
        if (elevenLabsVoiceId != null) return elevenLabsVoiceId!!

        try {
            val conn = (URL("https://api.elevenlabs.io/v1/voices").openConnection() as HttpURLConnection).apply {
                setRequestProperty("xi-api-key", key.trim())
                connectTimeout = 10000
            }
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val voices = JSONObject(response).optJSONArray("voices")
                if (voices != null && voices.length() > 0) {
                    // Log all available voices
                    for (i in 0 until voices.length()) {
                        val v = voices.getJSONObject(i)
                        Log.d(TAG, "Voice: ${v.optString("name")} (${v.optString("voice_id")}) category=${v.optString("category")}")
                    }

                    // Prefer: conversational labels, then "premade"/"default", then first available
                    val preferred = listOf("aria", "sarah", "roger", "chris", "laura", "charlie", "lily")
                    for (name in preferred) {
                        for (i in 0 until voices.length()) {
                            val v = voices.getJSONObject(i)
                            if (v.optString("name").lowercase().contains(name)) {
                                elevenLabsVoiceId = v.getString("voice_id")
                                Log.i(TAG, "Selected voice: ${v.optString("name")} ($elevenLabsVoiceId)")
                                conn.disconnect()
                                return elevenLabsVoiceId!!
                            }
                        }
                    }

                    // No preferred match — use first voice
                    elevenLabsVoiceId = voices.getJSONObject(0).getString("voice_id")
                    Log.i(TAG, "Using first voice: ${voices.getJSONObject(0).optString("name")} ($elevenLabsVoiceId)")
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch voices", e)
        }

        return elevenLabsVoiceId ?: "JBFqnCBsd6RMkjVDRZzb"
    }

    private fun speakElevenLabs(text: String, key: String, audioTrackRef: (AudioTrack?) -> Unit) {
        val trimmedKey = key.trim()
        val voiceId = keyStore.selectedVoiceId ?: getElevenLabsVoiceId(trimmedKey)
        val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream?output_format=pcm_24000")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("xi-api-key", trimmedKey)
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 10000
        }
        conn.outputStream.write(JSONObject()
            .put("text", text)
            .put("model_id", "eleven_flash_v2_5")
            .toString().toByteArray())

        val code = conn.responseCode
        com.lui.app.helper.LuiLogger.d("TTS", "ElevenLabs response code: $code")
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText()
            com.lui.app.helper.LuiLogger.e("TTS", "ElevenLabs TTS error $code: ${err?.take(200)}")
            conn.disconnect()
            return
        }

        streamPcmResponse(conn.inputStream, 24000, audioTrackRef)
        conn.disconnect()
    }

    private fun streamPcmResponse(input: InputStream, sampleRate: Int, audioTrackRef: (AudioTrack?) -> Unit) {
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
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM).build()

        if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); return }

        audioTrackRef(track)
        track.play()

        val buffer = ByteArray(4096)
        var leftover: Byte? = null
        var read: Int
        while (input.read(buffer).also { read = it } > 0) {
            var offset = 0
            var length = read

            // Prepend leftover byte from previous chunk if we had an odd read
            val workBuffer = if (leftover != null) {
                val combined = ByteArray(1 + read)
                combined[0] = leftover!!
                System.arraycopy(buffer, 0, combined, 1, read)
                leftover = null
                offset = 0
                length = combined.size
                combined
            } else {
                buffer
            }

            // If odd number of bytes, save the last one for next iteration
            if (length % 2 != 0) {
                leftover = workBuffer[length - 1]
                length -= 1
            }

            if (length > 0) {
                val shorts = ShortArray(length / 2)
                ByteBuffer.wrap(workBuffer, offset, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                track.write(shorts, 0, shorts.size)
            }
        }

        // Wait for AudioTrack to fully drain
        val remaining = track.playbackHeadPosition
        Thread.sleep(500)
        track.stop()
        track.release()
        audioTrackRef(null)
    }

    /**
     * Quick test — sends minimal request to verify the key works.
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val provider = keyStore.speechProvider
        val key = keyStore.getSpeechKey(provider) ?: return@withContext false
        try {
            when (provider) {
                SpeechProvider.DEEPGRAM -> {
                    val url = URL("https://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=linear16&sample_rate=24000")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Authorization", "Token $key")
                        setRequestProperty("Content-Type", "application/json")
                        doOutput = true; connectTimeout = 10000
                    }
                    conn.outputStream.write("""{"text":"test"}""".toByteArray())
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { "unknown" }
                        Log.e(TAG, "Deepgram test failed $code: $err")
                    }
                    conn.disconnect(); code in 200..299
                }
                SpeechProvider.ELEVENLABS -> {
                    val trimmedKey = key.trim()
                    val voiceId = "21m00Tcm4TlvDq8ikWAM"
                    val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=pcm_24000")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("xi-api-key", trimmedKey)
                        setRequestProperty("Content-Type", "application/json")
                        doOutput = true
                        connectTimeout = 10000
                    }
                    conn.outputStream.write("""{"text":"test","model_id":"eleven_flash_v2_5"}""".toByteArray())
                    val code = conn.responseCode
                    if (code !in 200..299 && code != 402) {
                        val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { "unknown" }
                        Log.e(TAG, "ElevenLabs test failed $code: $err")
                    }
                    conn.disconnect()
                    // 200 = works, 402 = key valid but voice needs paid plan (we'll fetch user's voices)
                    code in 200..299 || code == 402
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Speech test exception", e)
            false
        }
    }
}
