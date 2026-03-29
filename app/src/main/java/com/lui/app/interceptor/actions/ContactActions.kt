package com.lui.app.interceptor.actions

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object ContactActions {

    fun searchContact(context: Context, query: String): ActionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            return ActionResult.Failure("I need contacts permission. Grant it in Settings > Apps > LUI > Permissions.")
        }

        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$query%")

            val cursor: Cursor? = context.contentResolver.query(uri, projection, selection, args, null)
            val results = mutableListOf<String>()

            cursor?.use {
                while (it.moveToNext() && results.size < 5) {
                    val name = it.getString(0)
                    val number = it.getString(1)
                    results.add("$name: $number")
                }
            }

            if (results.isEmpty()) {
                ActionResult.Success("No contacts found for \"$query\".")
            } else {
                ActionResult.Success(results.joinToString("\n"))
            }
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't search contacts: ${e.message}")
        }
    }

    fun createContact(context: Context, name: String, number: String): ActionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            return ActionResult.Failure("I need contacts permission. Grant it in Settings > Apps > LUI > Permissions.")
        }

        return try {
            val ops = ArrayList<android.content.ContentProviderOperation>()

            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build())

            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build())

            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            ActionResult.Success("Contact \"$name\" created with number $number.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't create contact: ${e.message}")
        }
    }
}
