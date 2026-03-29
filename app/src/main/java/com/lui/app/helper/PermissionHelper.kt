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
