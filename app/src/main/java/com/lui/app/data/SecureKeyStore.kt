package com.lui.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lui.app.llm.CloudProvider
import com.lui.app.llm.SpeechProvider

class SecureKeyStore(context: Context) {

    companion object {
        private const val TAG = "SecureKeyStore"
        private const val PREFS_NAME = "lui_secure_prefs"
    }

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // If encrypted prefs fail, try clearing and recreating before falling back
        Log.e(TAG, "EncryptedSharedPreferences failed, attempting recovery", e)
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e2: Exception) {
            Log.e(TAG, "SECURITY WARNING: Using unencrypted storage as last resort", e2)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // ---- LLM ----

    var selectedProvider: CloudProvider?
        get() = CloudProvider.fromName(prefs.getString("cloud_provider", null))
        set(value) = prefs.edit().putString("cloud_provider", value?.name).apply()

    var isCloudFirst: Boolean
        get() = prefs.getString("llm_mode", "local_first") == "cloud_first"
        set(value) = prefs.edit().putString("llm_mode", if (value) "cloud_first" else "local_first").apply()

    fun getApiKey(provider: CloudProvider): String? =
        prefs.getString("api_key_${provider.name}", null)?.takeIf { it.isNotBlank() }

    fun setApiKey(provider: CloudProvider, key: String?) =
        prefs.edit().putString("api_key_${provider.name}", key ?: "").apply()

    val hasCloudConfigured: Boolean
        get() {
            val provider = selectedProvider ?: return false
            return getApiKey(provider) != null
        }

    // ---- Speech (unified STT + TTS) ----

    var speechProvider: SpeechProvider
        get() = SpeechProvider.fromName(prefs.getString("speech_provider", null)) ?: SpeechProvider.DEEPGRAM
        set(value) = prefs.edit().putString("speech_provider", value.name).apply()

    var cloudSpeechEnabled: Boolean
        get() = prefs.getBoolean("cloud_speech_enabled", false)
        set(value) = prefs.edit().putBoolean("cloud_speech_enabled", value).apply()

    fun getSpeechKey(provider: SpeechProvider): String? =
        prefs.getString("speech_key_${provider.name}", null)?.takeIf { it.isNotBlank() }

    fun setSpeechKey(provider: SpeechProvider, key: String?) =
        prefs.edit().putString("speech_key_${provider.name}", key ?: "").apply()

    var selectedVoiceId: String?
        get() = prefs.getString("speech_voice_id", null)
        set(value) = prefs.edit().putString("speech_voice_id", value).apply()

    var selectedVoiceName: String?
        get() = prefs.getString("speech_voice_name", null)
        set(value) = prefs.edit().putString("speech_voice_name", value).apply()

    val hasCloudSpeechConfigured: Boolean
        get() = cloudSpeechEnabled && getSpeechKey(speechProvider) != null

    // ---- BYOS Bridge ----

    fun getBridgeToken(): String? =
        prefs.getString("bridge_token", null)?.takeIf { it.isNotBlank() }

    fun saveBridgeToken(token: String) =
        prefs.edit().putString("bridge_token", token).apply()

    var isBridgeEnabled: Boolean
        get() = prefs.getBoolean("bridge_enabled", false)
        set(value) = prefs.edit().putBoolean("bridge_enabled", value).apply()

    var bridgePermissionTier: String
        get() = prefs.getString("bridge_tier", "STANDARD") ?: "STANDARD"
        set(value) = prefs.edit().putString("bridge_tier", value).apply()

    // ---- Relay ----

    var relayUrl: String?
        get() = prefs.getString("relay_url", null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString("relay_url", value ?: "").apply()

    var relayEnabled: Boolean
        get() = prefs.getBoolean("relay_enabled", false)
        set(value) = prefs.edit().putBoolean("relay_enabled", value).apply()
}
