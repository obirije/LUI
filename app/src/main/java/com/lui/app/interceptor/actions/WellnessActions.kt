package com.lui.app.interceptor.actions

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.lui.app.audio.AceStepClient
import com.lui.app.audio.AmbientSoundPlayer
import com.lui.app.data.SecureKeyStore
import com.lui.app.helper.LuiLogger
import kotlinx.coroutines.runBlocking

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
            onSuccess = {
                val marker = " [playing:kind=ambient;sound=${sound.name};label=${sound.displayName}]"
                ActionResult.Success("Playing ${sound.displayName}. Say 'stop sound' when you're done.$marker")
            },
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
        // [playing:…] marker drives the NOW_PLAYING card renderer.
        val marker = if (soundResult.isSuccess)
            " [playing:kind=wellness;sound=${sound.name};label=${sound.displayName}]"
        else ""
        return ActionResult.Success(
            "Wellness mode on: $soundNote, notifications muted, screen dimmed. Say 'stop wellness mode' when you're ready to come back.$marker"
        )
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

    /**
     * Generate a one-off calming track via ACE-Step and play it on loop.
     * Falls back to the bundled PIANO sound when the endpoint isn't
     * configured or the call fails. Intentionally blocking — callers
     * should expect a 10-60s wait for generation.
     */
    fun generateRelaxingMusic(context: Context, prompt: String, durationSec: Int = 45): ActionResult {
        val store = SecureKeyStore(context)
        // Inherit wellness state so pickVolume keeps the wellness boost + time-of-day curve.
        val wellness = isWellnessModeActive
        if (!store.hasAceStepConfigured) {
            AmbientSoundPlayer.play(context, AmbientSoundPlayer.Sound.PIANO, wellnessMode = wellness).fold(
                onSuccess = { return ActionResult.Success("Music gen isn't configured yet — I put on Clair de Lune instead. Add an ACE-Step endpoint in Connection Hub to unlock generated tracks.") },
                onFailure = { return ActionResult.Failure("Music gen isn't configured and the fallback piano track isn't bundled either.") }
            )
        }

        val composed = if (prompt.isBlank()) composeDefaultPrompt(context) else prompt
        val result = runBlocking { AceStepClient.generate(context, composed, durationSec) }
        return result.fold(
            onSuccess = { file ->
                // Persist to library so it shows up in Connection Hub > Your tracks.
                runBlocking {
                    try {
                        val dao = com.lui.app.data.LuiDatabase.getInstance(context).generatedTrackDao()
                        dao.insert(
                            com.lui.app.data.GeneratedTrackEntity(
                                filename = file.name,
                                displayName = deriveTrackName(composed),
                                prompt = composed,
                                durationMs = durationSec * 1000L,
                                sizeBytes = file.length()
                            )
                        )
                    } catch (e: Exception) {
                        LuiLogger.e("Wellness", "Library insert failed: ${e.message}")
                    }
                }
                AmbientSoundPlayer.playFromFile(context, file, wellnessMode = wellness).fold(
                    onSuccess = {
                        val escaped = composed.take(60).replace(';', ',').replace('[', '(').replace(']', ')')
                        val marker = " [playing:kind=generated;file=${file.name};label=$escaped]"
                        ActionResult.Success("Generated and playing: \"${composed.take(80)}\". Saved to your library. Say 'stop sound' when you're done.$marker")
                    },
                    onFailure = { e -> ActionResult.Failure("Generated the track but couldn't play it: ${e.message}") }
                )
            },
            onFailure = { e ->
                // Soft fall-back so the user still gets calming audio
                AmbientSoundPlayer.play(context, AmbientSoundPlayer.Sound.PIANO, wellnessMode = wellness)
                ActionResult.Failure("Couldn't generate music (${e.message?.take(60)}). Put on Clair de Lune as a fallback.")
            }
        )
    }

    /** Turn a prompt into a short, human-readable default name — first 3-4
     *  content words, title-cased. Fallback is a timestamp. */
    private fun deriveTrackName(prompt: String): String {
        val words = prompt.replace(Regex("[,.;]+"), " ").split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .take(4)
        if (words.isEmpty()) return "Track ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        return words.joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercaseChar() } }
    }

    private fun composeDefaultPrompt(context: Context): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val stress = try { HealthActions.getRingService(context).stress.value } catch (_: Exception) { -1 }

        val mood = when {
            stress in 86..100 -> "deep calm, anxiety release, very slow tempo"
            stress in 75..85 -> "grounding, steady, gentle warmth"
            stress in 40..74 -> "relaxed focus, even mood"
            else -> "soft, spacious, comforting"
        }
        val tod = when (hour) {
            in 22..23, in 0..5 -> "night wind-down, sparse piano, low warm pads"
            in 6..8 -> "dawn, soft strings, hopeful"
            in 9..11 -> "morning clarity, airy piano"
            in 12..15 -> "afternoon focus, muted textures"
            in 16..18 -> "late afternoon, mellow sundown"
            else -> "evening calm, cello, rain overlay"
        }
        return "Ambient instrumental, $mood, $tod, 60-70 bpm, no vocals, no percussion"
    }

    /**
     * Start a guided breathing exercise. Returns a chat-ready string that
     * carries an embedded [breath:…] marker — the BREATHING card adapter
     * parses it to drive the pacer animation + phase timings.
     *
     * Patterns:
     *   "4-7-8" / "478" — inhale 4s, hold 7s, exhale 8s (deep parasympathetic)
     *   "box" / "4-4-4-4" — inhale 4, hold 4, exhale 4, hold 4 (calming + focusing)
     *   "5-5" / "paced" — inhale 5s, exhale 5s (no holds, easy default)
     */
    fun startBreathingExercise(context: Context, pattern: String = "4-7-8", cycles: Int = 4): ActionResult {
        val normalized = when (pattern.lowercase().trim().replace("-", "").replace(" ", "")) {
            "478", "" -> "478"
            "box", "4444" -> "box"
            "55", "paced" -> "55"
            else -> "478"
        }
        val cycleN = cycles.coerceIn(2, 12)

        val (inhale, holdIn, exhale, holdOut, name) = when (normalized) {
            "box" -> Quintuple(4, 4, 4, 4, "Box (4-4-4-4)")
            "55"  -> Quintuple(5, 0, 5, 0, "Paced (5-5)")
            else  -> Quintuple(4, 7, 8, 0, "4-7-8 calming")
        }
        val totalSec = (inhale + holdIn + exhale + holdOut) * cycleN

        // Embedded marker — adapter reads pattern + counts. Keep on a single
        // line so it survives Room round-trips into the chat history.
        val marker = "[breath:pattern=$normalized;cycles=$cycleN;in=$inhale;hold=$holdIn;out=$exhale;hold2=$holdOut]"
        val msg = "$name — ${cycleN} cycles, about ${totalSec}s. Follow the pacer. $marker"
        return ActionResult.Success(msg)
    }

    private data class Quintuple(val a: Int, val b: Int, val c: Int, val d: Int, val name: String)

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
