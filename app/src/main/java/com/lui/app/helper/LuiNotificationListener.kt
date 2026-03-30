package com.lui.app.helper

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Listens to all notifications on the device.
 * Must be enabled by the user in Settings > Notifications > Notification access.
 */
class LuiNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "LuiNotifListener"
        private const val MAX_STORED = 50

        // In-memory store of recent notifications — read by NotificationActions
        val recentNotifications = mutableListOf<NotificationInfo>()

        data class NotificationInfo(
            val app: String,
            val title: String,
            val text: String,
            val timestamp: Long,
            val key: String
        )

        var instance: LuiNotificationListener? = null
            private set
    }

    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val app = sbn.packageName

        if (title.isBlank() && text.isBlank()) return

        synchronized(recentNotifications) {
            recentNotifications.add(0, NotificationInfo(
                app = app,
                title = title,
                text = text,
                timestamp = sbn.postTime,
                key = sbn.key
            ))
            // Trim to max
            while (recentNotifications.size > MAX_STORED) {
                recentNotifications.removeAt(recentNotifications.size - 1)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        synchronized(recentNotifications) {
            recentNotifications.removeAll { it.key == sbn.key }
        }
    }
}
