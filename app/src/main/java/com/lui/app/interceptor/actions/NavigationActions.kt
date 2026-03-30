package com.lui.app.interceptor.actions

import android.content.Context
import android.content.Intent
import android.net.Uri

object NavigationActions {

    fun navigate(context: Context, destination: String): ActionResult {
        return try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                ActionResult.Success("Navigating to $destination.")
            } else {
                // Fallback: open in browser
                val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(destination)}")
                context.startActivity(Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                ActionResult.Success("Opening directions to $destination.")
            }
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't navigate: ${e.message}")
        }
    }

    fun searchMap(context: Context, query: String): ActionResult {
        return try {
            val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Success("Searching for $query on the map.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't open map: ${e.message}")
        }
    }
}
