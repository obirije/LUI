package com.lui.app.helper

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The Bouncer: Intercepts all notifications and triages them into buckets.
 *
 * Bucket A (Urgent): Passes through — banking, rides, pinned contacts, calls
 * Bucket B (Noise): Silently killed, stored for Evening Digest
 * Bucket C (Auto-Action): 2FA codes extracted and stored for retrieval
 */
class LuiNotificationListener : NotificationListenerService() {

    companion object {
        private const val MAX_STORED = 50
        private const val MAX_DIGEST = 100
        private const val MAX_2FA = 10

        val recentNotifications = mutableListOf<NotificationInfo>()
        val digestNotifications = mutableListOf<NotificationInfo>()
        val pending2faCodes = mutableListOf<TwoFactorCode>()

        // Configurable: apps/contacts that are always urgent
        val urgentApps = mutableSetOf(
            "com.google.android.apps.maps",     // Ride/navigation
            "com.ubercab",                       // Uber
            "me.lyft.android",                   // Lyft
            "com.whatsapp",                      // WhatsApp (configurable)
        )
        val urgentKeywords = mutableSetOf(
            "bank", "payment", "transaction", "transfer", "fraud", "security alert",
            "delivery", "arriving", "arrived", "emergency"
        )

        // Apps that are always noise
        val noiseApps = mutableSetOf(
            "com.google.android.gm",             // Gmail (batch)
            "com.google.android.apps.magazines", // Google News
            "com.linkedin.android",              // LinkedIn
            "com.twitter.android",               // X/Twitter
        )

        // 2FA code pattern
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

    override fun onListenerConnected() {
        instance = this
        LuiLogger.i("BOUNCER", "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        LuiLogger.i("BOUNCER", "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val app = sbn.packageName

        if (title.isBlank() && text.isBlank()) return
        // Skip our own notifications
        if (app == applicationContext.packageName) return

        val bucket = classify(app, title, text)
        val info = NotificationInfo(app, title, text, sbn.postTime, sbn.key, bucket)

        LuiLogger.d("BOUNCER", "[${bucket.name}] $app: $title — ${text.take(60)}")

        when (bucket) {
            Bucket.URGENT -> {
                // Pass through — store in recent
                synchronized(recentNotifications) {
                    recentNotifications.add(0, info)
                    while (recentNotifications.size > MAX_STORED) recentNotifications.removeAt(recentNotifications.size - 1)
                }
            }
            Bucket.NOISE -> {
                // Kill the notification, store for digest
                try { cancelNotification(sbn.key) } catch (_: Exception) {}
                synchronized(digestNotifications) {
                    digestNotifications.add(0, info)
                    while (digestNotifications.size > MAX_DIGEST) digestNotifications.removeAt(digestNotifications.size - 1)
                }
            }
            Bucket.AUTO_ACTION -> {
                // Extract 2FA code, store for retrieval
                val code = extract2faCode(text) ?: extract2faCode(title)
                if (code != null) {
                    synchronized(pending2faCodes) {
                        pending2faCodes.add(0, TwoFactorCode(code, app, "$title: $text", sbn.postTime))
                        while (pending2faCodes.size > MAX_2FA) pending2faCodes.removeAt(pending2faCodes.size - 1)
                    }
                    LuiLogger.i("BOUNCER", "2FA code captured: $code from $app")
                }
                // Also store in recent so user can see it
                synchronized(recentNotifications) {
                    recentNotifications.add(0, info)
                    while (recentNotifications.size > MAX_STORED) recentNotifications.removeAt(recentNotifications.size - 1)
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

    private fun classify(app: String, title: String, text: String): Bucket {
        val combined = "$title $text".lowercase()

        // Check for 2FA first
        if (CODE_KEYWORDS.any { combined.contains(it) } && CODE_REGEX.containsMatchIn(combined)) {
            return Bucket.AUTO_ACTION
        }

        // Check urgent apps
        if (app in urgentApps) return Bucket.URGENT

        // Check urgent keywords
        if (urgentKeywords.any { combined.contains(it) }) return Bucket.URGENT

        // Check noise apps
        if (app in noiseApps) return Bucket.NOISE

        // Phone/SMS are always urgent
        if (app.contains("dialer") || app.contains("phone") || app.contains("messaging") || app.contains("sms")) {
            return Bucket.URGENT
        }

        // Default: urgent (pass through) — be conservative
        return Bucket.URGENT
    }

    private fun extract2faCode(text: String): String? {
        val lower = text.lowercase()
        if (!CODE_KEYWORDS.any { lower.contains(it) }) return null
        return CODE_REGEX.find(text)?.groupValues?.get(1)
    }
}
