package com.lui.app.interceptor.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    fun readSms(context: Context, from: String?, count: Int = 5): ActionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            return ActionResult.Failure("I need SMS permission to read messages.")
        }

        return try {
            val uri = Uri.parse("content://sms/inbox")
            val projection = arrayOf("address", "body", "date")

            // If 'from' specified, resolve to number and filter
            val selection: String?
            val args: Array<String>?
            if (from != null && from.isNotBlank()) {
                val resolved = resolveNumber(context, from)
                // Match on last 7 digits to handle formatting differences
                val digits = resolved.replace(Regex("[^0-9]"), "").takeLast(7)
                selection = "address LIKE ?"
                args = arrayOf("%$digits%")
            } else {
                selection = null
                args = null
            }

            val cursor = context.contentResolver.query(
                uri, projection, selection, args, "date DESC"
            )

            val messages = mutableListOf<String>()
            val timeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

            cursor?.use {
                while (it.moveToNext() && messages.size < count) {
                    val address = it.getString(0) ?: "?"
                    val body = it.getString(1) ?: ""
                    val date = it.getLong(2)
                    val time = timeFormat.format(Date(date))
                    val display = resolveContactName(context, address) ?: address
                    messages.add("$display ($time): ${body.take(100)}")
                }
            }

            if (messages.isEmpty()) {
                val qualifier = if (from != null) " from $from" else ""
                ActionResult.Success("No messages found$qualifier.")
            } else {
                ActionResult.Success(messages.joinToString("\n"))
            }
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't read messages: ${e.message}")
        }
    }

    private fun resolveContactName(context: Context, number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return null

        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }
}
