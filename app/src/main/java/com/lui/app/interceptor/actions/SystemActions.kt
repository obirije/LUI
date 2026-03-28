package com.lui.app.interceptor.actions

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.Settings

object SystemActions {

    private var flashlightOn = false

    fun toggleFlashlight(context: Context): ActionResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return ActionResult.Failure("No camera found.")

            flashlightOn = !flashlightOn
            cameraManager.setTorchMode(cameraId, flashlightOn)

            val state = if (flashlightOn) "on" else "off"
            ActionResult.Success("Flashlight $state.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't toggle flashlight: ${e.message}")
        }
    }

    fun openWifiSettings(context: Context): ActionResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Settings.Panel.ACTION_WIFI)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            ActionResult.Success("Opening Wi-Fi settings.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't open Wi-Fi settings.")
        }
    }

    fun openBluetoothSettings(context: Context): ActionResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            ActionResult.Success("Opening Bluetooth settings.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't open Bluetooth settings.")
        }
    }
}

sealed class ActionResult {
    data class Success(val message: String) : ActionResult()
    data class Failure(val message: String) : ActionResult()
}
