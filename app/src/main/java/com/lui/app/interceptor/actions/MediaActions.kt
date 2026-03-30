package com.lui.app.interceptor.actions

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.BatteryManager
import android.provider.Settings
import android.view.KeyEvent
import com.lui.app.helper.LuiNotificationListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object MediaActions {

    fun playPause(context: Context): ActionResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)

            val playing = audioManager.isMusicActive
            ActionResult.Success(if (playing) "Playing." else "Paused.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't control media playback.")
        }
    }

    fun nextTrack(context: Context): ActionResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
            ActionResult.Success("Next track.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't skip track.")
        }
    }

    fun previousTrack(context: Context): ActionResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            ActionResult.Success("Previous track.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't go to previous track.")
        }
    }

    fun setRingerMode(context: Context, mode: String): ActionResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (mode) {
                "silent" -> audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                "vibrate" -> audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                "normal", "ring" -> audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            ActionResult.Success("Ringer set to $mode.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't change ringer mode.")
        }
    }

    fun getBattery(context: Context): ActionResult {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            val status = if (charging) "charging" else "not charging"
            ActionResult.Success("Battery is at $level%, $status.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't read battery level.")
        }
    }

    fun copyToClipboard(context: Context, text: String): ActionResult {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LUI", text))
            ActionResult.Success("Copied to clipboard.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't copy: ${e.message}")
        }
    }

    fun shareText(context: Context, text: String): ActionResult {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            ActionResult.Success("Opening share menu.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't share.")
        }
    }

    fun nowPlaying(context: Context): ActionResult {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = android.content.ComponentName(context, LuiNotificationListener::class.java)
            val controllers: List<MediaController> = try {
                msm.getActiveSessions(component)
            } catch (e: SecurityException) {
                return ActionResult.Failure("I need notification access to read what's playing. Enable LUI in notification access settings.")
            }

            if (controllers.isEmpty()) {
                return ActionResult.Success("Nothing is playing right now.")
            }

            val controller = controllers[0]
            val metadata = controller.metadata
            val state = controller.playbackState

            val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
            val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val album = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val appName = controller.packageName.substringAfterLast(".")

            val playing = state?.state == android.media.session.PlaybackState.STATE_PLAYING
            val statusText = if (playing) "Playing" else "Paused"

            val parts = mutableListOf(title)
            if (artist.isNotBlank()) parts.add("by $artist")
            if (album.isNotBlank()) parts.add("on $album")

            ActionResult.Success("$statusText: ${parts.joinToString(" ")} ($appName)")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't read media info: ${e.message}")
        }
    }

    fun readClipboard(context: Context): ActionResult {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!clipboard.hasPrimaryClip()) {
                return ActionResult.Success("Clipboard is empty.")
            }
            val clip = clipboard.primaryClip?.getItemAt(0)
            val text = clip?.text?.toString() ?: clip?.uri?.toString() ?: "Clipboard has non-text content."
            ActionResult.Success("Clipboard: $text")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't read clipboard: ${e.message}")
        }
    }

    fun getScreenTime(context: Context, appName: String? = null): ActionResult {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // Check if we have usage access
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val testStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis())
        if (testStats.isNullOrEmpty()) {
            return try {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Failure("I need usage access. Please enable LUI in the settings that just opened.")
            } catch (e: Exception) {
                ActionResult.Failure("I need usage access permission. Enable it in Settings > Apps > Special access > Usage access.")
            }
        }

        // Get today's stats
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, todayStart, now)
            ?.filter { it.totalTimeInForeground > 60000 } // >1 min
            ?.sortedByDescending { it.totalTimeInForeground }
            ?: return ActionResult.Success("No usage data for today.")

        if (appName != null && appName.isNotBlank()) {
            // Find specific app
            val match = stats.find { stat ->
                val label = getAppLabel(context, stat.packageName)
                label.lowercase().contains(appName.lowercase()) ||
                    stat.packageName.lowercase().contains(appName.lowercase())
            }
            return if (match != null) {
                val label = getAppLabel(context, match.packageName)
                val time = formatDuration(match.totalTimeInForeground)
                ActionResult.Success("$label: $time today.")
            } else {
                ActionResult.Success("No usage found for \"$appName\" today.")
            }
        }

        // Top 5 apps
        val top = stats.take(5).map { stat ->
            val label = getAppLabel(context, stat.packageName)
            val time = formatDuration(stat.totalTimeInForeground)
            "$label: $time"
        }
        return ActionResult.Success("Screen time today:\n${top.joinToString("\n")}")
    }

    private fun getAppLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) { packageName.substringAfterLast(".") }
    }

    private fun formatDuration(millis: Long): String {
        val hours = millis / 3600000
        val minutes = (millis % 3600000) / 60000
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }
}
