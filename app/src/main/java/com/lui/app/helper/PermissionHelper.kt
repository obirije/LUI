package com.lui.app.helper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionHelper {

    data class PermissionRequest(
        val permission: String,
        val explanation: String
    )

    fun getRequiredPermission(toolName: String): PermissionRequest? {
        return when (toolName) {
            "send_sms" -> PermissionRequest(
                Manifest.permission.SEND_SMS,
                "I need SMS permission to send messages for you."
            )
            "search_contact" -> PermissionRequest(
                Manifest.permission.READ_CONTACTS,
                "I need contacts permission to look up your address book."
            )
            "create_contact" -> PermissionRequest(
                Manifest.permission.WRITE_CONTACTS,
                "I need contacts permission to save new contacts."
            )
            "make_call" -> PermissionRequest(
                Manifest.permission.CALL_PHONE,
                "I need phone permission to make calls directly."
            )
            "get_location", "get_distance" -> PermissionRequest(
                Manifest.permission.ACCESS_FINE_LOCATION,
                "I need location permission to find where you are."
            )
            "read_sms" -> PermissionRequest(
                Manifest.permission.READ_SMS,
                "I need SMS permission to read your messages."
            )
            "read_calendar" -> PermissionRequest(
                Manifest.permission.READ_CALENDAR,
                "I need calendar permission to check your schedule."
            )
            "get_steps" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                PermissionRequest(
                    Manifest.permission.ACTIVITY_RECOGNITION,
                    "I need activity recognition permission to read your step count."
                )
            } else null
            "bluetooth_devices", "get_heart_rate", "get_spo2", "get_sleep", "get_activity",
            "get_stress", "get_hrv", "get_temperature",
            "ring_battery", "ring_status", "find_ring" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                PermissionRequest(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    "I need Bluetooth permission to see your connected devices."
                )
            } else null
            else -> null
        }
    }

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Open the app's permission settings page so the user can grant manually.
     */
    fun openAppPermissionSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
