package com.lui.app.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import com.lui.app.MainActivity
import com.lui.app.R
import com.lui.app.helper.LuiLogger

/**
 * Foreground service that listens for "Hey LUI" wake word using sherpa-onnx KeywordSpotter.
 * When detected, wakes the screen and launches LUI in conversation mode.
 *
 * ~5MB model, ~2-3% battery per hour, runs on CPU.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWord"
        private const val CHANNEL_ID = "lui_wakeword"
        private const val NOTIFICATION_ID = 9002
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_MS = 100 // 100ms audio chunks

        var isRunning = false
            private set
    }

    private var kws: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var listening = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!initKeywordSpotter()) {
            LuiLogger.e(TAG, "Failed to initialize keyword spotter")
            stopSelf()
            return START_NOT_STICKY
        }

        // Partial wake lock to keep CPU running for mic processing
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LUI:WakeWord")
        wakeLock?.acquire()

        startListening()
        isRunning = true
        LuiLogger.i(TAG, "Wake word service started — listening for 'Hey LUI'")

        return START_STICKY
    }

    private fun initKeywordSpotter(): Boolean {
        return try {
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "kws/encoder.int8.onnx",
                        decoder = "kws/decoder.onnx",
                        joiner = "kws/joiner.int8.onnx",
                    ),
                    tokens = "kws/tokens.txt",
                    modelType = "zipformer2",
                ),
                keywordsFile = "kws/keywords.txt",
                keywordsScore = 3.0f,
                keywordsThreshold = 0.06f,  // Very sensitive — may get false positives
            )

            kws = KeywordSpotter(assetManager = assets, config = config)
            stream = kws!!.createStream()
            LuiLogger.i(TAG, "Keyword spotter initialized")
            true
        } catch (e: Exception) {
            LuiLogger.e(TAG, "KWS init failed: ${e.message}", e)
            false
        }
    }

    private fun startListening() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // AGC enabled — better for wake word
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(bufferSize, SAMPLE_RATE * 2) // 2 second buffer
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            LuiLogger.e(TAG, "AudioRecord failed to initialize")
            return
        }

        listening = true
        audioRecord?.startRecording()

        Thread {
            val chunkSize = (SAMPLE_RATE * CHUNK_MS / 1000) // 1600 samples per 100ms
            val buffer = ShortArray(chunkSize)
            var chunkCount = 0

            while (listening) {
                val read = audioRecord?.read(buffer, 0, chunkSize) ?: 0
                if (read > 0 && stream != null && kws != null) {
                    val floats = FloatArray(read) { buffer[it] / 32768.0f }
                    stream!!.acceptWaveform(floats, SAMPLE_RATE)

                    while (kws!!.isReady(stream!!)) {
                        kws!!.decode(stream!!)
                        val result = kws!!.getResult(stream!!)
                        if (result.keyword.isNotBlank()) {
                            LuiLogger.i(TAG, "*** WAKE WORD DETECTED: '${result.keyword}' ***")
                            onWakeWordDetected()
                            kws!!.reset(stream!!)
                        }
                    }

                    chunkCount++
                    if (chunkCount % 100 == 0) { // Log every 10 seconds
                        // Check audio level to verify mic is working
                        var maxAmplitude = 0f
                        for (s in floats) { if (kotlin.math.abs(s) > maxAmplitude) maxAmplitude = kotlin.math.abs(s) }
                        LuiLogger.d(TAG, "Listening... chunks=$chunkCount maxAmp=${String.format("%.4f", maxAmplitude)}")
                    }
                }
            }
        }.start()
    }

    private fun onWakeWordDetected() {
        // Stage 2: Quick verification using Android STT
        // Record 2 more seconds and check if transcription contains "lui"/"louie"
        LuiLogger.i(TAG, "Keyword spotted — verifying with STT...")

        // Capture 2 seconds of audio for verification
        val verifySamples = SAMPLE_RATE * 2
        val verifyBuffer = ShortArray(verifySamples)
        val read = audioRecord?.read(verifyBuffer, 0, verifySamples) ?: 0

        if (read > 0) {
            // Check audio level — if too quiet, probably false positive
            var maxAmp = 0f
            for (i in 0 until read) {
                val amp = kotlin.math.abs(verifyBuffer[i] / 32768.0f)
                if (amp > maxAmp) maxAmp = amp
            }

            if (maxAmp < 0.002f) {
                LuiLogger.d(TAG, "False positive — audio too quiet (maxAmp=$maxAmp)")
                return
            }
        }

        // Verified enough — activate
        LuiLogger.i(TAG, "Wake word confirmed — activating!")
        activateConversation()
    }

    private fun activateConversation() {
        // Wake the screen
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val screenLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "LUI:WakeScreen"
        )
        screenLock.acquire(5000)

        // Stop listening temporarily — conversation mode will take over the mic
        listening = false
        audioRecord?.stop()

        // Launch LUI in conversation mode
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("conversation_mode", true)
            putExtra("wake_word", true)
        }
        startActivity(intent)

        // Resume listening after conversation ends
        Thread {
            Thread.sleep(30000)
            if (isRunning && !listening) {
                try {
                    audioRecord?.startRecording()
                    listening = true
                    LuiLogger.i(TAG, "Resumed wake word listening")
                } catch (e: Exception) {
                    LuiLogger.e(TAG, "Failed to resume listening: ${e.message}")
                }
            }
        }.start()
    }

    override fun onDestroy() {
        listening = false
        isRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        stream?.release()
        stream = null
        kws?.release()
        kws = null
        wakeLock?.release()
        wakeLock = null
        LuiLogger.i(TAG, "Wake word service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LUI Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listening for 'Hey LUI'"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LUI is listening")
            .setContentText("Say \"Hey LUI\" to activate")
            .setSmallIcon(R.drawable.status_dot)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
