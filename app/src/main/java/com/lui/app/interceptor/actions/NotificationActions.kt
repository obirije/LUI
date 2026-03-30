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

    fun readNotifications(context: Context, count: Int = 10): ActionResult {
        if (!isListenerEnabled(context)) {
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

        val notifications = synchronized(LuiNotificationListener.recentNotifications) {
            LuiNotificationListener.recentNotifications.take(count).toList()
        }

        if (notifications.isEmpty()) {
            return ActionResult.Success("No recent notifications.")
        }

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val sb = StringBuilder()
        for (n in notifications) {
            val appName = getAppName(context, n.app)
            val time = timeFormat.format(Date(n.timestamp))
            sb.appendLine("$appName ($time): ${n.title}${if (n.text.isNotBlank()) " — ${n.text}" else ""}")
        }

        return ActionResult.Success(sb.toString().trim())
    }

    fun clearNotifications(context: Context): ActionResult {
        if (!isListenerEnabled(context)) {
            return ActionResult.Failure("I need notification access to clear notifications.")
        }

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
