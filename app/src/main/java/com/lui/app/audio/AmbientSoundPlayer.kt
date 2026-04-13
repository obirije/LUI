package com.lui.app.audio

import android.content.Context
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import com.lui.app.helper.LuiLogger
import java.util.Calendar

/**
 * Loops bundled ambient/soothing sounds (rain, ocean, fire, wind, forest, white noise, crickets).
 *
 * Add CC0/royalty-free OGG or MP3 files to `app/src/main/res/raw/` with names matching
 * the [Sound] enum's rawName values. Missing files are handled gracefully — the tool reports
 * which sounds are available on this build.
 */
object AmbientSoundPlayer {
    private const val TAG = "AmbientSound"

    enum class Sound(val displayName: String, val rawName: String) {
        RAIN("Rain", "amb_rain"),
        THUNDER("Thunderstorm", "amb_thunder"),
        OCEAN("Ocean waves", "amb_ocean"),
        FIRE("Fire crackling", "amb_fire"),
        WIND("Wind", "amb_wind"),
        FOREST("Forest & birds", "amb_forest"),
        WHITE_NOISE("White noise", "amb_white_noise"),
        BROWN_NOISE("Brown noise", "amb_brown_noise"),
        CRICKETS("Night crickets", "amb_crickets"),
        PIANO("Clair de Lune (piano)", "amb_piano"),
        MEDITATION("Meditation bell", "amb_meditation");

        companion object {
            fun match(query: String): Sound? {
                val q = query.lowercase().trim()
                if (q.isBlank()) return null
                return entries.firstOrNull { s ->
                    q == s.name.lowercase() ||
                        q == s.rawName ||
                        s.displayName.lowercase().split(" ").any { it == q || q.contains(it) } ||
                        q.contains(s.name.lowercase().replace("_", " "))
                }
            }
        }
    }

    private var player: MediaPlayer? = null
    private var currentSound: Sound? = null
    private var priorSystemVolume: Int? = null
    private var inWellnessMode = false

    /**
     * Pick a playback volume (0.0–1.0) based on time of day and whether we're in wellness mode.
     * Wellness mode gets a small boost since the user explicitly wants to hear it.
     * Noise tracks (white/brown) are slightly quieter since pure noise carries more.
     */
    private fun pickVolume(sound: Sound, wellness: Boolean): Float {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val base = when (hour) {
            in 22..23, in 0..5 -> 0.30f   // sleep / late night
            in 6..8           -> 0.50f    // morning
            in 9..18          -> 0.60f    // daytime
            in 19..21         -> 0.45f    // evening
            else              -> 0.50f
        }
        val wellnessBoost = if (wellness) 0.08f else 0f
        val noiseDamp = if (sound == Sound.WHITE_NOISE || sound == Sound.BROWN_NOISE) -0.10f else 0f
        return (base + wellnessBoost + noiseDamp).coerceIn(0.15f, 1.0f)
    }

    /**
     * Ensure the system MUSIC stream is audible before we start playback — if the user
     * has music volume at 0%, even a MediaPlayer at 1.0 is silent. Bumps to a minimum
     * level (typically ~30% of max) and captures the prior value so stop() can restore.
     */
    private fun ensureSystemVolumeAudible(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = (max * 0.35f).toInt().coerceAtLeast(1)
            if (cur < target) {
                priorSystemVolume = cur
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                LuiLogger.i(TAG, "Bumped music volume from $cur → $target (of $max)")
            }
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Couldn't check/bump system volume: ${e.message}")
        }
    }

    private fun restoreSystemVolume(context: Context) {
        val prior = priorSystemVolume ?: return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, prior, 0)
            LuiLogger.i(TAG, "Restored music volume to $prior")
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Couldn't restore system volume: ${e.message}")
        }
        priorSystemVolume = null
    }

    @Synchronized
    fun play(context: Context, sound: Sound, wellnessMode: Boolean = false): Result<Unit> {
        stop(context)
        val resId = context.resources.getIdentifier(sound.rawName, "raw", context.packageName)
        if (resId == 0) {
            return Result.failure(Resources.NotFoundException(
                "Sound '${sound.displayName}' isn't bundled yet. Add ${sound.rawName}.ogg/.mp3 to res/raw/."
            ))
        }
        return try {
            val mp = MediaPlayer.create(context, resId) ?: return Result.failure(
                IllegalStateException("MediaPlayer couldn't decode ${sound.rawName}")
            )
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.isLooping = true
            val vol = pickVolume(sound, wellnessMode)
            mp.setVolume(vol, vol)
            ensureSystemVolumeAudible(context)
            mp.start()
            player = mp
            currentSound = sound
            inWellnessMode = wellnessMode
            LuiLogger.i(TAG, "Playing ${sound.displayName} at ${"%.0f".format(vol * 100)}% (wellness=$wellnessMode)")
            Result.success(Unit)
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Failed to play ${sound.displayName}: ${e.message}", e)
            player = null
            currentSound = null
            Result.failure(e)
        }
    }

    @Synchronized
    fun stop(context: Context? = null): Boolean {
        val was = currentSound
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
        currentSound = null
        inWellnessMode = false
        if (context != null) restoreSystemVolume(context)
        return was != null
    }

    val currentlyPlaying: Sound? get() = currentSound

    fun availableSounds(context: Context): List<Sound> = Sound.entries.filter {
        context.resources.getIdentifier(it.rawName, "raw", context.packageName) != 0
    }
}
