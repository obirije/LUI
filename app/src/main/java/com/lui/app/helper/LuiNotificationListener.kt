package com.lui.app.helper

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lui.app.data.DigestEntity
import com.lui.app.data.LuiDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Bouncer: Intercepts all notifications and triages them into buckets.
 * Only active when LUI is set as the default launcher.
 *
 * Bucket A (Urgent): Passes through — banking, rides, pinned contacts, calls
 * Bucket B (Noise): Silently killed, persisted to Room for Evening Digest
 * Bucket C (Auto-Action): 2FA codes extracted and persisted for retrieval
 */
class LuiNotificationListener : NotificationListenerService() {

    companion object {
        private const val MAX_MEMORY = 50

        // In-memory cache (fast access for tools)
        val recentNotifications = mutableListOf<NotificationInfo>()
        val pending2faCodes = mutableListOf<TwoFactorCode>()

        // Configurable triage rules
        val urgentApps = mutableSetOf(
            "com.google.android.apps.maps",
            "com.ubercab",
            "me.lyft.android",
            "com.whatsapp",
        )
        val urgentKeywords = mutableSetOf(
            "bank", "payment", "transaction", "transfer", "fraud", "security alert",
            "delivery", "arriving", "arrived", "emergency"
        )
        val noiseApps = mutableSetOf(
            "com.google.android.gm",
            "com.google.android.apps.magazines",
            "com.linkedin.android",
            "com.twitter.android",
        )

        private val CODE_REGEX = Regex("\\b(\\d{4,8})\\b")
        private val CODE_KEYWORDS = listOf("code", "otp", "verify", "verification", "2fa", "pin", "confirm")

        var instance: LuiNotificationListener? = null
            private set

        data class NotificationInfo(
            val app: String,
            val title: String,
            val text: String,
            val timestamp: Long,
            val key: String,
            val bucket: Bucket = Bucket.URGENT
        )

        data class TwoFactorCode(
            val code: String,
            val app: String,
            val fullText: String,
            val timestamp: Long
        )

        enum class Bucket { URGENT, NOISE, AUTO_ACTION }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var digestDao: com.lui.app.data.DigestDao

    override fun onListenerConnected() {
        instance = this
        digestDao = LuiDatabase.getInstance(applicationContext).digestDao()
        LuiLogger.i("BOUNCER", "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        LuiLogger.i("BOUNCER", "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        // Only triage when LUI is the active launcher
        if (!isLuiDefaultLauncher()) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val app = sbn.packageName

        if (title.isBlank() && text.isBlank()) return
        if (app == applicationContext.packageName) return

        val bucket = classify(app, title, text)
        val info = NotificationInfo(app, title, text, sbn.postTime, sbn.key, bucket)

        LuiLogger.d("BOUNCER", "[${bucket.name}] $app: $title — ${text.take(60)}")

        when (bucket) {
            Bucket.URGENT -> {
                synchronized(recentNotifications) {
                    recentNotifications.add(0, info)
                    while (recentNotifications.size > MAX_MEMORY) recentNotifications.removeAt(recentNotifications.size - 1)
                }
            }
            Bucket.NOISE -> {
                try { cancelNotification(sbn.key) } catch (_: Exception) {}
                // Persist to Room
                scope.launch {
                    digestDao.insert(DigestEntity(
                        app = app, title = title, text = text,
                        bucket = "NOISE", timestamp = sbn.postTime
                    ))
                }
            }
            Bucket.AUTO_ACTION -> {
                val code = extract2faCode(text) ?: extract2faCode(title)
                // Persist to Room
                scope.launch {
                    digestDao.insert(DigestEntity(
                        app = app, title = title, text = text,
                        bucket = "AUTO_ACTION", timestamp = sbn.postTime,
                        code = code
                    ))
                }
                if (code != null) {
                    synchronized(pending2faCodes) {
                        pending2faCodes.add(0, TwoFactorCode(code, app, "$title: $text", sbn.postTime))
                        while (pending2faCodes.size > 10) pending2faCodes.removeAt(pending2faCodes.size - 1)
                    }
                    LuiLogger.i("BOUNCER", "2FA code captured: $code from $app")
                }
                // Also keep in recent
                synchronized(recentNotifications) {
                    recentNotifications.add(0, info)
                    while (recentNotifications.size > MAX_MEMORY) recentNotifications.removeAt(recentNotifications.size - 1)
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        synchronized(recentNotifications) {
            recentNotifications.removeAll { it.key == sbn.key }
        }
    }

    private fun isLuiDefaultLauncher(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo?.activityInfo?.packageName == applicationContext.packageName
        } catch (_: Exception) { false }
    }

    private fun classify(app: String, title: String, text: String): Bucket {
        val combined = "$title $text".lowercase()

        if (CODE_KEYWORDS.any { combined.contains(it) } && CODE_REGEX.containsMatchIn(combined)) {
            return Bucket.AUTO_ACTION
        }
        if (app in urgentApps) return Bucket.URGENT
        if (urgentKeywords.any { combined.contains(it) }) return Bucket.URGENT
        if (app in noiseApps) return Bucket.NOISE
        if (app.contains("dialer") || app.contains("phone") || app.contains("messaging") || app.contains("sms")) {
            return Bucket.URGENT
        }
        return Bucket.URGENT
    }

    private fun extract2faCode(text: String): String? {
        val lower = text.lowercase()
        if (!CODE_KEYWORDS.any { lower.contains(it) }) return null
        return CODE_REGEX.find(text)?.groupValues?.get(1)
    }
}
