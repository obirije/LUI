package com.lui.app.interceptor.actions

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

object DeviceInfoActions {

    fun getTime(context: Context): ActionResult {
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        return ActionResult.Success("It's $time.")
    }

    fun getDate(context: Context): ActionResult {
        val date = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
        return ActionResult.Success("It's $date.")
    }

    fun getDeviceInfo(context: Context): ActionResult {
        val sb = StringBuilder()
        val now = Date()
        sb.appendLine("Time: ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)}")
        sb.appendLine("Date: ${SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(now)}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")

        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            sb.appendLine("Battery: ${level}%${if (charging) " (charging)" else ""}")
        } catch (_: Exception) {}

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val net = when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "connected"
            }
            sb.appendLine("Network: $net")
        } catch (_: Exception) {}

        return ActionResult.Success(sb.toString().trim())
    }
}
