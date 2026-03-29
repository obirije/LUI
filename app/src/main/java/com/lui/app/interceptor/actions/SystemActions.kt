package com.lui.app.interceptor.actions

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings

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
}

sealed class ActionResult {
    data class Success(val message: String) : ActionResult()
    data class Failure(val message: String) : ActionResult()
}
