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
        val link = getDeepLink(app.lowercase().trim(), query) ?: return openApp(context, app)

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (link.pkg != null) setPackage(link.pkg)
            }
            // Verify the target app can handle it
            if (link.pkg != null && intent.resolveActivity(context.packageManager) == null) {
                // Package not installed — try without package constraint
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(link.uri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } else {
                context.startActivity(intent)
            }
            ActionResult.Success("Opening ${link.label} with \"$query\".")
        } catch (e: Exception) {
            openApp(context, app)
        }
    }

    private data class DeepLinkInfo(val uri: String, val pkg: String?, val label: String)

    private fun getDeepLink(app: String, query: String): DeepLinkInfo? {
        val q = Uri.encode(query)
        return when {
            // Music
            app.contains("spotify") -> DeepLinkInfo("spotify:search:$q", "com.spotify.music", "Spotify")
            app.contains("youtube music") || app.contains("yt music") -> DeepLinkInfo("https://music.youtube.com/search?q=$q", "com.google.android.apps.youtube.music", "YouTube Music")
            app.contains("amazon music") -> DeepLinkInfo("https://music.amazon.com/search/$q", "com.amazon.mp3", "Amazon Music")
            app.contains("soundcloud") -> DeepLinkInfo("soundcloud://search?q=$q", "com.soundcloud.android", "SoundCloud")
            app.contains("deezer") -> DeepLinkInfo("deezer://search/$q", "deezer.android.app", "Deezer")

            // Video
            app.contains("youtube") || app.contains("yt") -> DeepLinkInfo("https://www.youtube.com/results?search_query=$q", "com.google.android.youtube", "YouTube")
            app.contains("netflix") -> DeepLinkInfo("https://www.netflix.com/search?q=$q&jbv=app", "com.netflix.mediaclient", "Netflix")
            app.contains("tiktok") -> DeepLinkInfo("https://www.tiktok.com/search?q=$q", "com.zhiliaoapp.musically", "TikTok")
            app.contains("twitch") -> DeepLinkInfo("twitch://search?query=$q", "tv.twitch.android.app", "Twitch")

            // Social
            app.contains("twitter") || app.contains("x") && !app.contains("box") -> DeepLinkInfo("https://twitter.com/search?q=$q", "com.twitter.android", "X")
            app.contains("reddit") -> DeepLinkInfo("https://www.reddit.com/search/?q=$q", "com.reddit.frontpage", "Reddit")
            app.contains("instagram") || app.contains("insta") -> DeepLinkInfo("https://www.instagram.com/explore/tags/$q/", "com.instagram.android", "Instagram")

            // Messaging (with text/target)
            app.contains("whatsapp") -> DeepLinkInfo("https://wa.me/?text=$q", "com.whatsapp", "WhatsApp")
            app.contains("telegram") -> DeepLinkInfo("tg://resolve?domain=$q", "org.telegram.messenger", "Telegram")

            // Shopping
            app.contains("amazon") -> DeepLinkInfo("https://www.amazon.com/s?k=$q", "com.amazon.mShop.android.shopping", "Amazon")
            app.contains("ebay") -> DeepLinkInfo("https://www.ebay.com/sch/i.html?_nkw=$q", "com.ebay.mobile", "eBay")

            // Utilities
            app.contains("map") || app.contains("maps") -> DeepLinkInfo("geo:0,0?q=$q", "com.google.android.apps.maps", "Maps")
            app.contains("translate") -> DeepLinkInfo("https://translate.google.com/?text=$q", "com.google.android.apps.translate", "Translate")
            app.contains("play store") || app.contains("play") && app.contains("store") -> DeepLinkInfo("market://search?q=$q", "com.android.vending", "Play Store")
            app.contains("shazam") -> DeepLinkInfo("shazam://recognize", "com.shazam.android", "Shazam")

            // Ride sharing
            app.contains("uber") -> DeepLinkInfo("uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]=$q", "com.ubercab", "Uber")
            app.contains("lyft") -> DeepLinkInfo("lyft://ridetype?id=lyft&destination[address]=$q", "me.lyft.android", "Lyft")

            // Browser — use ACTION_WEB_SEARCH intent instead of URL for better handling
            app.contains("chrome") -> DeepLinkInfo("https://www.google.com/search?q=$q", "com.android.chrome", "Chrome")
            app.contains("browser") || app.contains("web") || app.contains("google") && !app.contains("map") -> DeepLinkInfo("https://www.google.com/search?q=$q", null, "Browser")

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
