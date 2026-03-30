package com.lui.app.interceptor.actions

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.lui.app.helper.LuiAccessibilityService

object SystemActions {

    private var flashlightOn = false

    fun toggleFlashlight(context: Context, desiredState: String? = null): ActionResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ActionResult.Failure("No camera found.")

            flashlightOn = when (desiredState) {
                "on" -> {
                    if (flashlightOn) return ActionResult.Success("Flashlight is already on.")
                    true
                }
                "off" -> {
                    if (!flashlightOn) return ActionResult.Success("Flashlight is already off.")
                    false
                }
                else -> !flashlightOn // toggle
            }

            cameraManager.setTorchMode(cameraId, flashlightOn)
            ActionResult.Success("Flashlight ${if (flashlightOn) "on" else "off"}.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't toggle flashlight.")
        }
    }

    fun openWifiSettings(context: Context): ActionResult {
        return try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionResult.Success("Opening Wi-Fi settings.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't open Wi-Fi settings.")
        }
    }

    fun openBluetoothSettings(context: Context): ActionResult {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionResult.Success("Opening Bluetooth settings.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't open Bluetooth settings.")
        }
    }

    fun openSettings(context: Context): ActionResult {
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionResult.Success("Opening settings.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't open settings.")
        }
    }

    fun setVolume(context: Context, direction: String): ActionResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (direction) {
                "up" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "max" -> {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                }
                else -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            }
            ActionResult.Success("Volume $direction.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't change volume.")
        }
    }

    fun setBrightness(context: Context, level: String): ActionResult {
        if (!Settings.System.canWrite(context)) {
            return try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Failure("I need permission to change brightness. Please enable it in the settings that just opened.")
            } catch (e: Exception) {
                ActionResult.Failure("I need the Modify System Settings permission to change brightness.")
            }
        }

        return try {
            // Disable auto-brightness
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)

            val brightness = when (level) {
                "up" -> minOf((getCurrentBrightness(context) + 50), 255)
                "down" -> maxOf((getCurrentBrightness(context) - 50), 10)
                "max" -> 255
                "low", "min" -> 10
                else -> {
                    val pct = level.replace("%", "").toIntOrNull()
                    if (pct != null) (pct * 255 / 100).coerceIn(10, 255)
                    else 128
                }
            }

            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
            ActionResult.Success("Brightness set to ${brightness * 100 / 255}%.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't change brightness.")
        }
    }

    private fun getCurrentBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) { 128 }
    }

    fun toggleDnd(context: Context): ActionResult {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            return try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Failure("I need Do Not Disturb access. Please enable LUI in the settings that just opened.")
            } catch (e: Exception) {
                ActionResult.Failure("I need Do Not Disturb access permission.")
            }
        }

        return try {
            val currentFilter = nm.currentInterruptionFilter
            if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                ActionResult.Success("Do Not Disturb on.")
            } else {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                ActionResult.Success("Do Not Disturb off.")
            }
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't toggle Do Not Disturb.")
        }
    }

    fun toggleRotation(context: Context): ActionResult {
        if (!Settings.System.canWrite(context)) {
            return try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Failure("I need permission to change rotation. Please enable it in the settings that just opened.")
            } catch (e: Exception) {
                ActionResult.Failure("I need the Modify System Settings permission.")
            }
        }

        return try {
            val current = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            val newValue = if (current == 1) 0 else 1
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, newValue)
            ActionResult.Success("Auto-rotate ${if (newValue == 1) "on" else "off"}.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't toggle rotation.")
        }
    }

    // ── Accessibility Global Actions ──

    fun lockScreen(context: Context): ActionResult {
        val service = LuiAccessibilityService.instance
            ?: return ActionResult.Failure("I need accessibility access to lock the screen. Enable LUI in Settings > Accessibility.")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
            ActionResult.Success("Phone locked.")
        } else {
            ActionResult.Failure("Couldn't lock screen. Requires Android 9+.")
        }
    }

    fun takeScreenshot(context: Context): ActionResult {
        val service = LuiAccessibilityService.instance
            ?: return ActionResult.Failure("I need accessibility access to take screenshots. Enable LUI in Settings > Accessibility.")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)) {
            ActionResult.Success("Screenshot taken.")
        } else {
            ActionResult.Failure("Couldn't take screenshot. Requires Android 9+.")
        }
    }

    fun splitScreen(context: Context): ActionResult {
        val service = LuiAccessibilityService.instance
            ?: return ActionResult.Failure("I need accessibility access for split screen.")
        return if (service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)) {
            ActionResult.Success("Toggled split screen.")
        } else {
            ActionResult.Failure("Couldn't toggle split screen.")
        }
    }

    // ── Screen Timeout ──

    fun setScreenTimeout(context: Context, duration: String): ActionResult {
        if (!Settings.System.canWrite(context)) {
            return try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Failure("I need permission to change screen timeout.")
            } catch (e: Exception) {
                ActionResult.Failure("I need the Modify System Settings permission.")
            }
        }

        val millis = when (duration.lowercase().trim()) {
            "15s", "15 seconds" -> 15000
            "30s", "30 seconds" -> 30000
            "1m", "1 min", "1 minute" -> 60000
            "2m", "2 min", "2 minutes" -> 120000
            "5m", "5 min", "5 minutes" -> 300000
            "10m", "10 min", "10 minutes" -> 600000
            "30m", "30 min", "30 minutes" -> 1800000
            "never", "off", "always on" -> Int.MAX_VALUE
            else -> duration.replace(Regex("[^0-9]"), "").toIntOrNull()?.let { it * 1000 } ?: 60000
        }

        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, millis)
            val display = if (millis >= Int.MAX_VALUE) "never" else "${millis / 1000}s"
            ActionResult.Success("Screen timeout set to $display.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't change screen timeout.")
        }
    }

    // ── Keep Screen On ──

    private var wakeLock: PowerManager.WakeLock? = null

    fun keepScreenOn(context: Context, enable: Boolean): ActionResult {
        return try {
            if (enable) {
                if (wakeLock?.isHeld == true) return ActionResult.Success("Screen is already being kept on.")
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "LUI:keepScreenOn"
                )
                wakeLock?.acquire(30 * 60 * 1000L) // 30 min max
                ActionResult.Success("Screen will stay on (30 min max).")
            } else {
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                ActionResult.Success("Screen keep-on released.")
            }
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't change screen wake: ${e.message}")
        }
    }
}

sealed class ActionResult {
    data class Success(val message: String) : ActionResult()
    data class Failure(val message: String) : ActionResult()
}
