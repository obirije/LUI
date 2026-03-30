package com.lui.app.interceptor.actions

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.lui.app.helper.LuiNotificationListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NotificationActions {

    fun isListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat?.contains(context.packageName) == true
    }

    private fun requireListener(context: Context): ActionResult? {
        if (isListenerEnabled(context)) return null
        return try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Failure("I need notification access. Please enable LUI in the settings that just opened.")
        } catch (e: Exception) {
            ActionResult.Failure("I need notification access. Enable it in Settings > Apps > Special access > Notification access.")
        }
    }

    fun readNotifications(context: Context, count: Int = 10): ActionResult {
        requireListener(context)?.let { return it }

        val notifications = synchronized(LuiNotificationListener.recentNotifications) {
            LuiNotificationListener.recentNotifications.take(count).toList()
        }

        if (notifications.isEmpty()) return ActionResult.Success("No recent notifications.")

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val sb = StringBuilder()
        for (n in notifications) {
            val appName = getAppName(context, n.app)
            val time = timeFormat.format(Date(n.timestamp))
            val bucket = when (n.bucket) {
                LuiNotificationListener.Companion.Bucket.URGENT -> ""
                LuiNotificationListener.Companion.Bucket.NOISE -> " [digest]"
                LuiNotificationListener.Companion.Bucket.AUTO_ACTION -> " [2FA]"
            }
            sb.appendLine("$appName ($time)$bucket: ${n.title}${if (n.text.isNotBlank()) " — ${n.text}" else ""}")
        }

        return ActionResult.Success(sb.toString().trim())
    }

    fun clearNotifications(context: Context): ActionResult {
        requireListener(context)?.let { return it }

        return try {
            LuiNotificationListener.instance?.cancelAllNotifications()
            synchronized(LuiNotificationListener.recentNotifications) {
                LuiNotificationListener.recentNotifications.clear()
            }
            ActionResult.Success("All notifications cleared.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't clear notifications: ${e.message}")
        }
    }

    /**
     * Get the Evening Digest — batched noise notifications summarized.
     */
    fun getDigest(context: Context): ActionResult {
        val digest = synchronized(LuiNotificationListener.digestNotifications) {
            LuiNotificationListener.digestNotifications.toList()
        }

        if (digest.isEmpty()) return ActionResult.Success("No batched notifications. Everything was passed through.")

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val sb = StringBuilder("Digest (${digest.size} batched notifications):\n")
        // Group by app
        val grouped = digest.groupBy { it.app }
        for ((app, notifs) in grouped) {
            val appName = getAppName(context, app)
            sb.appendLine("\n$appName (${notifs.size}):")
            for (n in notifs.take(5)) {
                val time = timeFormat.format(Date(n.timestamp))
                sb.appendLine("  $time: ${n.title}${if (n.text.isNotBlank()) " — ${n.text.take(60)}" else ""}")
            }
            if (notifs.size > 5) sb.appendLine("  ...and ${notifs.size - 5} more")
        }

        return ActionResult.Success(sb.toString().trim())
    }

    /**
     * Clear the digest.
     */
    fun clearDigest(context: Context): ActionResult {
        synchronized(LuiNotificationListener.digestNotifications) {
            val count = LuiNotificationListener.digestNotifications.size
            LuiNotificationListener.digestNotifications.clear()
            return ActionResult.Success("Digest cleared ($count notifications).")
        }
    }

    /**
     * Get the most recent 2FA code.
     */
    fun get2faCode(context: Context): ActionResult {
        val codes = synchronized(LuiNotificationListener.pending2faCodes) {
            LuiNotificationListener.pending2faCodes.toList()
        }

        if (codes.isEmpty()) return ActionResult.Success("No pending 2FA codes.")

        val latest = codes[0]
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val time = timeFormat.format(Date(latest.timestamp))
        val appName = getAppName(context, latest.app)

        return ActionResult.Success("Latest 2FA code: ${latest.code} (from $appName at $time)")
    }

    /**
     * Configure triage: add an app to urgent or noise list.
     */
    fun configTriage(app: String, bucket: String): ActionResult {
        val lower = bucket.lowercase()
        return when {
            lower.contains("urgent") || lower.contains("important") -> {
                LuiNotificationListener.urgentApps.add(app)
                LuiNotificationListener.noiseApps.remove(app)
                ActionResult.Success("$app marked as urgent — notifications will pass through.")
            }
            lower.contains("noise") || lower.contains("silent") || lower.contains("digest") -> {
                LuiNotificationListener.noiseApps.add(app)
                LuiNotificationListener.urgentApps.remove(app)
                ActionResult.Success("$app marked as noise — notifications will go to digest.")
            }
            else -> ActionResult.Failure("Unknown bucket. Use 'urgent' or 'noise'.")
        }
    }

    private fun getAppName(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }
}
