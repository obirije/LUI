package com.lui.app.interceptor.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.view.KeyEvent

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
}
