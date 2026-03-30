package com.lui.app.interceptor.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

object SmsActions {

    fun sendSms(context: Context, number: String, message: String): ActionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            return ActionResult.Failure("I need SMS permission to send messages. Grant it in Settings > Apps > LUI > Permissions.")
        }

        // Resolve contact name to number if it's not digits
        val resolved = resolveNumber(context, number)
        val displayName = if (resolved != number) "$number ($resolved)" else resolved

        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(resolved, null, parts, null, null)
            ActionResult.Success("SMS sent to $displayName.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't send SMS: ${e.message}")
        }
    }

    /** If input looks like a name, resolve via contacts. Otherwise return as-is. */
    private fun resolveNumber(context: Context, input: String): String {
        val digits = input.replace(Regex("[^0-9+]"), "")
        if (digits.length >= 3) return digits // Already a number

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return input

        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$input%")

            context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else input
            } ?: input
        } catch (e: Exception) { input }
    }
}
