package com.lui.app.interceptor.actions

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process

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
        return if (digits.length >= 3) {
            try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$digits")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Success("Calling $digits.")
            } catch (e: SecurityException) {
                ActionResult.Failure("I need phone call permission to do that.")
            } catch (e: Exception) {
                ActionResult.Failure("Couldn't make call: ${e.message}")
            }
        } else {
            try {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Success("Opening the dialer. I can't search contacts yet.")
            } catch (e: Exception) {
                ActionResult.Failure("Couldn't open dialer.")
            }
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
