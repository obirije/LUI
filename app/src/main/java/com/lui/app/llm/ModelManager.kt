package com.lui.app.llm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Handles model file management — copying from staging to app internal storage.
 */
object ModelManager {

    private const val TAG = "ModelManager"

    /**
     * Check common locations for the model file and copy to app internal storage if found.
     * Returns true if the model is available in internal storage.
     */
    fun ensureModel(context: Context): Boolean {
        val targetDir = File(context.filesDir, "models")
        val targetFile = File(targetDir, LocalModel.MODEL_FILENAME)

        if (targetFile.exists() && targetFile.length() > 1_000_000) {
            Log.d(TAG, "Model already in place: ${targetFile.absolutePath}")
            return true
        }

        // Check staging locations
        val stagingPaths = listOf(
            "/data/local/tmp/${LocalModel.MODEL_FILENAME}",
            "/sdcard/Download/${LocalModel.MODEL_FILENAME}",
            "/storage/emulated/0/Download/${LocalModel.MODEL_FILENAME}",
        )

        for (path in stagingPaths) {
            val source = File(path)
            if (source.exists() && source.length() > 1_000_000) {
                Log.d(TAG, "Found model at $path, copying to internal storage...")
                return try {
                    targetDir.mkdirs()
                    copyFile(source, targetFile)
                    Log.d(TAG, "Model copied successfully (${targetFile.length()} bytes)")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy model", e)
                    false
                }
            }
        }

        Log.d(TAG, "Model not found in any staging location")
        return false
    }

    /**
     * Copy voice models (STT + VAD) from staging to internal storage.
     */
    fun ensureVoiceModels(context: Context): Boolean {
        val sttDir = File(context.filesDir, "models/stt")
        val vadDir = File(context.filesDir, "models/vad")

        val sttFiles = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt"
        )
        val vadFile = "silero_vad.onnx"

        // Check if already in place
        val sttReady = sttFiles.all { File(sttDir, it).exists() }
        val vadReady = File(vadDir, vadFile).exists()
        if (sttReady && vadReady) return true

        // Try copying from staging
        val stagingDirs = listOf("/data/local/tmp")

        for (staging in stagingDirs) {
            // STT models
            if (!sttReady) {
                val sttSource = File(staging, "stt")
                if (sttSource.exists() && sttFiles.all { File(sttSource, it).exists() }) {
                    sttDir.mkdirs()
                    for (f in sttFiles) {
                        try { copyFile(File(sttSource, f), File(sttDir, f)) } catch (e: Exception) { return false }
                    }
                    Log.d(TAG, "STT models copied")
                }
            }

            // VAD model
            if (!vadReady) {
                val vadSource = File(staging, "vad/$vadFile")
                if (vadSource.exists()) {
                    vadDir.mkdirs()
                    try { copyFile(vadSource, File(vadDir, vadFile)) } catch (e: Exception) { return false }
                    Log.d(TAG, "VAD model copied")
                }
            }
        }

        return sttFiles.all { File(sttDir, it).exists() } && File(vadDir, vadFile).exists()
    }

    /**
     * Copy TTS model (Kokoro) from staging to internal storage.
     */
    fun ensureTtsModel(context: Context): Boolean {
        // Try Pocket TTS first
        val pocketDir = File(context.filesDir, "models/tts/sherpa-onnx-pocket-tts-int8-2026-01-26")
        if (File(pocketDir, "lm_main.int8.onnx").exists()) return true

        val pocketStaging = File("/data/local/tmp/tts/sherpa-onnx-pocket-tts-int8-2026-01-26")
        if (pocketStaging.exists() && File(pocketStaging, "lm_main.int8.onnx").exists()) {
            return try {
                copyDir(pocketStaging, pocketDir)
                Log.d(TAG, "Pocket TTS model copied")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy Pocket TTS model", e)
                false
            }
        }

        // Fall back to Piper
        val piperDir = File(context.filesDir, "models/tts/vits-piper-en_US-lessac-medium")
        if (File(piperDir, "en_US-lessac-medium.onnx").exists()) return true

        val piperStaging = File("/data/local/tmp/tts/vits-piper-en_US-lessac-medium")
        if (piperStaging.exists() && File(piperStaging, "en_US-lessac-medium.onnx").exists()) {
            return try {
                copyDir(piperStaging, piperDir)
                Log.d(TAG, "Piper TTS model copied")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy Piper TTS model", e)
                false
            }
        }

        Log.d(TAG, "No TTS model found")
        return false
    }

    private fun copyDir(source: File, target: File) {
        target.mkdirs()
        source.listFiles()?.forEach { file ->
            val dest = File(target, file.name)
            if (file.isDirectory) {
                copyDir(file, dest)
            } else {
                copyFile(file, dest)
            }
        }
    }

    private fun copyFile(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
            }
        }
    }
}
