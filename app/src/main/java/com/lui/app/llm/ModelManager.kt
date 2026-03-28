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
            "/storage/emulated/0/Download/${LocalModel.MODEL_FILENAME}"
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
