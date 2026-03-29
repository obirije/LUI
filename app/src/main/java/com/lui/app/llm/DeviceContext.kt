package com.lui.app.llm

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Gathers real-time device state to inject into the LLM system prompt.
 * This lets the model answer questions about the phone and make informed decisions.
 */
object DeviceContext {

    fun gather(context: Context): String {
        val sb = StringBuilder()

        // Time & Date
        val now = Date()
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
        sb.appendLine("Current time: ${timeFormat.format(now)}")
        sb.appendLine("Current date: ${dateFormat.format(now)}")
        sb.appendLine("Timezone: ${TimeZone.getDefault().displayName}")

        // Battery
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            sb.appendLine("Battery: ${level}%${if (charging) " (charging)" else ""}")
        } catch (_: Exception) {}

        // Connectivity
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val connected = caps != null
            val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            sb.appendLine("Network: ${if (!connected) "offline" else if (wifi) "Wi-Fi" else if (cellular) "cellular" else "connected"}")
        } catch (_: Exception) {}

        // Volume
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val ringer = when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            sb.appendLine("Volume: $vol/$max, Ringer: $ringer")
        } catch (_: Exception) {}

        // Brightness
        try {
            val brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (brightness >= 0) sb.appendLine("Brightness: ${brightness * 100 / 255}%")
        } catch (_: Exception) {}

        // Device
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")

        return sb.toString().trim()
    }
}
