package com.lui.app.interceptor.actions

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.lui.app.audio.AmbientSoundPlayer
import com.lui.app.helper.LuiLogger

/**
 * Tools for stress relief and digital wellbeing: ambient sounds and wellness mode.
 */
object WellnessActions {

    /** Snapshot of device state to restore when wellness mode is turned off. */
    private data class PriorState(
        val brightness: Int,
        val brightnessMode: Int,
        val dndFilter: Int
    )

    @Volatile private var priorState: PriorState? = null
    val isWellnessModeActive: Boolean get() = priorState != null

    fun playRelaxingSound(context: Context, type: String): ActionResult {
        val sound = AmbientSoundPlayer.Sound.match(type)
            ?: return ActionResult.Failure(
                "Unknown sound '$type'. Try: ${AmbientSoundPlayer.Sound.entries.joinToString { it.displayName.lowercase() }}."
            )

        return AmbientSoundPlayer.play(context, sound, wellnessMode = false).fold(
            onSuccess = { ActionResult.Success("Playing ${sound.displayName}. Say 'stop sound' when you're done.") },
            onFailure = { e -> ActionResult.Failure(e.message ?: "Couldn't play ${sound.displayName}.") }
        )
    }

    fun stopRelaxingSound(context: Context): ActionResult {
        val wasPlaying = AmbientSoundPlayer.stop(context)
        return if (wasPlaying) ActionResult.Success("Sound stopped.")
        else ActionResult.Success("No ambient sound was playing.")
    }

    /**
     * Enter wellness mode: play calming sound + DND on + dim screen.
     * Captures prior brightness and DND state so stopWellnessMode can restore.
     */
    /**
     * Pick a sound based on time of day + current ring stress level.
     * Used when wellness mode is started without an explicit sound choice.
     */
    private fun autoPickSound(context: Context): AmbientSoundPlayer.Sound {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val stress = try {
            HealthActions.getRingService(context).stress.value
        } catch (_: Exception) { -1 }

        // High stress (>85) overrides time of day — go straight for calming music
        if (stress in 86..100) return AmbientSoundPlayer.Sound.PIANO

        // Elevated stress (75-85): grounding rain rhythm
        if (stress in 75..85) return AmbientSoundPlayer.Sound.RAIN

        // Otherwise pick by time of day
        return when (hour) {
            in 22..23, in 0..5 -> AmbientSoundPlayer.Sound.BROWN_NOISE // late night / sleep
            in 6..8            -> AmbientSoundPlayer.Sound.FOREST      // morning
            in 9..11           -> AmbientSoundPlayer.Sound.PIANO       // mid-morning calm
            in 12..15          -> AmbientSoundPlayer.Sound.WHITE_NOISE // afternoon focus
            in 16..18          -> AmbientSoundPlayer.Sound.OCEAN       // late afternoon
            in 19..21          -> AmbientSoundPlayer.Sound.FIRE        // evening
            else               -> AmbientSoundPlayer.Sound.RAIN        // fallback
        }
    }

    fun startWellnessMode(context: Context, soundType: String = ""): ActionResult {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Permission check — we need both Modify System Settings and DND access.
        // Open the settings screen automatically so the user can grant it in one tap.
        if (!Settings.System.canWrite(context)) {
            return try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Failure("Wellness mode needs permission to dim the screen. I opened the settings — grant 'Modify system settings' to LUI, then try again.")
            } catch (e: Exception) {
                ActionResult.Failure("Wellness mode needs 'Modify system settings'. Enable it in Settings > Apps > Special access > Modify system settings > LUI.")
            }
        }
        if (!nm.isNotificationPolicyAccessGranted) {
            return try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Failure("Wellness mode needs Do Not Disturb access. I opened the settings — enable LUI there, then try again.")
            } catch (e: Exception) {
                ActionResult.Failure("Wellness mode needs Do Not Disturb access. Enable it in Settings > Apps > Special access > Do Not Disturb.")
            }
        }

        // Capture prior state ONCE (if already in wellness mode, leave snapshot alone)
        if (priorState == null) {
            val currentBrightness = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Exception) { 128 }
            val currentMode = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
            } catch (_: Exception) { Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC }
            priorState = PriorState(
                brightness = currentBrightness,
                brightnessMode = currentMode,
                dndFilter = nm.currentInterruptionFilter
            )
        }

        // Apply wellness settings — auto-pick if the LLM didn't specify a sound
        val explicit = AmbientSoundPlayer.Sound.match(soundType)
        val sound = explicit ?: autoPickSound(context)
        val pickedNote = if (explicit == null) " (auto-picked for this time of day)" else ""
        val soundResult = AmbientSoundPlayer.play(context, sound, wellnessMode = true)

        try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 30)
        } catch (e: Exception) {
            LuiLogger.e("Wellness", "Couldn't dim screen: ${e.message}")
        }

        try {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } catch (e: Exception) {
            LuiLogger.e("Wellness", "Couldn't enable DND: ${e.message}")
        }

        val soundNote = soundResult.fold(
            onSuccess = { "${sound.displayName}${pickedNote} playing" },
            onFailure = { "(sound unavailable — ${it.message?.take(80)})" }
        )
        return ActionResult.Success("Wellness mode on: $soundNote, notifications muted, screen dimmed. Say 'stop wellness mode' when you're ready to come back.")
    }

    /** Exit wellness mode: stop sound, restore brightness and DND. */
    fun stopWellnessMode(context: Context): ActionResult {
        AmbientSoundPlayer.stop(context)
        val prior = priorState
        if (prior == null) {
            return ActionResult.Success("Sound stopped. Wellness mode wasn't active.")
        }

        try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, prior.brightnessMode)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, prior.brightness)
        } catch (e: Exception) {
            LuiLogger.e("Wellness", "Couldn't restore brightness: ${e.message}")
        }

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(prior.dndFilter)
            }
        } catch (e: Exception) {
            LuiLogger.e("Wellness", "Couldn't restore DND: ${e.message}")
        }

        priorState = null
        return ActionResult.Success("Wellness mode off. Welcome back.")
    }

    fun listRelaxingSounds(context: Context): ActionResult {
        val available = AmbientSoundPlayer.availableSounds(context)
        if (available.isEmpty()) {
            return ActionResult.Failure(
                "No ambient sounds bundled yet. Add OGG/MP3 files to res/raw/ (amb_rain, amb_ocean, amb_fire, amb_wind, amb_forest, amb_white_noise, amb_crickets)."
            )
        }
        val all = AmbientSoundPlayer.Sound.entries
        val sb = StringBuilder("Available ambient sounds:\n")
        for (s in all) {
            val status = if (s in available) "✓" else "—"
            sb.appendLine("  $status ${s.displayName}")
        }
        val current = AmbientSoundPlayer.currentlyPlaying
        if (current != null) sb.appendLine("\nNow playing: ${current.displayName}")
        return ActionResult.Success(sb.toString().trim())
    }
}
