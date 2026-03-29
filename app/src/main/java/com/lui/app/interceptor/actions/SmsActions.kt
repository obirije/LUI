package com.lui.app.interceptor.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

object SmsActions {

    fun sendSms(context: Context, number: String, message: String): ActionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            return ActionResult.Failure("I need SMS permission to send messages. Grant it in Settings > Apps > LUI > Permissions.")
        }

        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            ActionResult.Success("SMS sent to $number.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't send SMS: ${e.message}")
        }
    }
}
