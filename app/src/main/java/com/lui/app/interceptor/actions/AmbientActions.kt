package com.lui.app.interceptor.actions

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build

object AmbientActions {

    /**
     * Get full ambient device context — battery, charging, network, Bluetooth, audio state.
     */
    fun getAmbientContext(context: Context): ActionResult {
        return try {
            val sb = StringBuilder()

            // Battery & charging
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = (level * 100) / scale
                val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val chargingSource = when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    else -> null
                }
                val temp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
                sb.appendLine("Battery: $pct%${if (chargingSource != null) ", charging ($chargingSource)" else ", not charging"}")
                sb.appendLine("Battery temperature: ${String.format("%.1f", temp)}°C")
            }

            // Network
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            if (caps != null) {
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "Unknown"
                }
                val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                sb.appendLine("Network: $type${if (metered) " (metered)" else ""}")

                // Wi-Fi details
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val info = wm.connectionInfo
                    @Suppress("DEPRECATION")
                    val ssid = info?.ssid?.removeSurrounding("\"") ?: "unknown"
                    val rssi = info?.rssi ?: 0
                    val signal = WifiManager.calculateSignalLevel(rssi, 5)
                    sb.appendLine("Wi-Fi: $ssid (signal $signal/4)")
                }
            } else {
                sb.appendLine("Network: disconnected")
            }

            // Bluetooth
            try {
                val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val btAdapter = btManager?.adapter
                if (btAdapter != null) {
                    val btEnabled = btAdapter.isEnabled
                    sb.append("Bluetooth: ${if (btEnabled) "on" else "off"}")
                    if (btEnabled) {
                        try {
                            val bonded = btAdapter.bondedDevices
                            if (bonded.isNotEmpty()) {
                                val names = bonded.mapNotNull { it.name }.take(5)
                                sb.append(" (paired: ${names.joinToString(", ")})")
                            }
                        } catch (_: SecurityException) {
                            // BLUETOOTH_CONNECT not granted — that's fine
                        }
                    }
                    sb.appendLine()
                }
            } catch (_: Exception) {
                // Bluetooth not available
            }

            // Audio
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val musicVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val musicMax = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val ringerMode = when (audioManager.ringerMode) {
                android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
                android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            val headphones = audioManager.isWiredHeadsetOn ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                    })
            sb.appendLine("Audio: volume $musicVolume/$musicMax, ringer $ringerMode${if (headphones) ", headphones connected" else ""}")

            // Screen brightness
            try {
                val brightness = android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, -1)
                if (brightness >= 0) {
                    val pct = (brightness * 100) / 255
                    sb.appendLine("Screen brightness: $pct%")
                }
            } catch (_: Exception) {}

            ActionResult.Success(sb.toString().trim())
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't read ambient context: ${e.message}")
        }
    }

    /**
     * Get Bluetooth connected devices.
     */
    fun getBluetoothDevices(context: Context): ActionResult {
        return try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return ActionResult.Failure("Bluetooth not available on this device.")

            val adapter = btManager.adapter
                ?: return ActionResult.Failure("No Bluetooth adapter found.")

            if (!adapter.isEnabled) return ActionResult.Success("Bluetooth is turned off.")

            val bonded = try {
                adapter.bondedDevices
            } catch (_: SecurityException) {
                return ActionResult.Failure("Bluetooth permission not granted.")
            }

            if (bonded.isNullOrEmpty()) return ActionResult.Success("Bluetooth is on but no paired devices.")

            val sb = StringBuilder("Paired Bluetooth devices:\n")
            for (device in bonded) {
                val name = try { device.name } catch (_: SecurityException) { "Unknown" }
                val type = when (device.type) {
                    android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
                    android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                    android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                    else -> "unknown"
                }
                sb.appendLine("- $name ($type)")
            }
            ActionResult.Success(sb.toString().trim())
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't read Bluetooth: ${e.message}")
        }
    }

    /**
     * Get network details — type, strength, metered status.
     */
    fun getNetworkState(context: Context): ActionResult {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return ActionResult.Success("No network connection.")
            val caps = cm.getNetworkCapabilities(net) ?: return ActionResult.Success("No network capabilities.")

            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Unknown"
            }
            val downMbps = caps.linkDownstreamBandwidthKbps / 1000
            val upMbps = caps.linkUpstreamBandwidthKbps / 1000
            val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

            ActionResult.Success("Network: $type, ${downMbps}Mbps down / ${upMbps}Mbps up, ${if (metered) "metered" else "unmetered"}${if (vpn) ", VPN active" else ""}")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't read network: ${e.message}")
        }
    }
}
