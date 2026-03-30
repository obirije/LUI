package com.lui.app.interceptor.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object AppLauncher {

    data class AppInfo(
        val label: String,
        val packageName: String
    )

    fun openApp(context: Context, name: String): ActionResult {
        val match = findApp(context, name)
            ?: return ActionResult.Failure("Couldn't find an app called \"$name\".")

        return try {
            val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val activities = launcher.getActivityList(match.packageName, Process.myUserHandle())
            if (activities.isNullOrEmpty()) {
                return ActionResult.Failure("Couldn't launch ${match.label}.")
            }
            val component = activities[0].componentName
            launcher.startMainActivity(component, Process.myUserHandle(), null, null)
            ActionResult.Success("Opening ${match.label}.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't launch ${match.label}: ${e.message}")
        }
    }

    fun makeCall(context: Context, target: String): ActionResult {
        val digits = target.replace(Regex("[^0-9+]"), "")
        if (digits.length >= 3) {
            return dialNumber(context, digits)
        }

        // Target is a name — try to resolve via contacts
        val resolved = resolveContactNumber(context, target)
        if (resolved != null) {
            return dialNumber(context, resolved.second, resolved.first)
        }

        // Can't resolve — open dialer
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Success("Couldn't find \"$target\" in contacts. Opening dialer.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't open dialer.")
        }
    }

    private fun dialNumber(context: Context, number: String, name: String? = null): ActionResult {
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Success("Calling ${name ?: number}.")
        } catch (e: SecurityException) {
            ActionResult.Failure("I need phone call permission to do that.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't make call: ${e.message}")
        }
    }

    /** Returns Pair(displayName, phoneNumber) or null */
    private fun resolveContactNumber(context: Context, name: String): Pair<String, String>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return null

        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$name%")

            context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(0)
                    val number = cursor.getString(1)
                    Pair(displayName, number)
                } else null
            }
        } catch (e: Exception) { null }
    }

    /** Deep-link into an app with a search query — "play Despacito on Spotify" */
    fun openAppWithQuery(context: Context, app: String, query: String): ActionResult {
        val deepLink = getDeepLink(app.lowercase(), query)
        if (deepLink != null) {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Success("Opening $app with \"$query\".")
            } catch (e: Exception) {
                // Fallback to just opening the app
                openApp(context, app)
            }
        }
        return openApp(context, app)
    }

    private fun getDeepLink(app: String, query: String): String? {
        val encoded = Uri.encode(query)
        return when {
            app.contains("spotify") -> "spotify:search:$encoded"
            app.contains("youtube music") -> "https://music.youtube.com/search?q=$encoded"
            app.contains("youtube") -> "https://www.youtube.com/results?search_query=$encoded"
            app.contains("netflix") -> "https://www.netflix.com/search?q=$encoded"
            app.contains("amazon") && app.contains("music") -> "https://music.amazon.com/search/$encoded"
            app.contains("chrome") || app.contains("browser") -> "https://www.google.com/search?q=$encoded"
            app.contains("google") && app.contains("map") -> "geo:0,0?q=$encoded"
            else -> null
        }
    }

    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { resolveInfo ->
                val label = resolveInfo.loadLabel(pm).toString()
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg != context.packageName) AppInfo(label, pkg) else null
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun findApp(context: Context, query: String): AppInfo? {
        val apps = getInstalledApps(context)
        val queryLower = query.lowercase().trim()

        apps.find { it.label.lowercase() == queryLower }?.let { return it }
        apps.find { it.label.lowercase().startsWith(queryLower) }?.let { return it }
        apps.find { it.label.lowercase().contains(queryLower) }?.let { return it }

        val queryWords = queryLower.split(" ")
        apps.find { app ->
            val labelLower = app.label.lowercase()
            queryWords.all { labelLower.contains(it) }
        }?.let { return it }

        return null
    }
}
