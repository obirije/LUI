package com.lui.app.interceptor.actions

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object StorageActions {

    fun getStorageInfo(context: Context): ActionResult {
        return try {
            val internal = StatFs(Environment.getDataDirectory().path)
            val totalInternal = internal.totalBytes / (1024 * 1024 * 1024.0)
            val freeInternal = internal.availableBytes / (1024 * 1024 * 1024.0)
            val usedInternal = totalInternal - freeInternal

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val totalRam = memInfo.totalMem / (1024 * 1024 * 1024.0)
            val freeRam = memInfo.availMem / (1024 * 1024 * 1024.0)

            val sb = StringBuilder()
            sb.appendLine("Storage: ${String.format("%.1f", usedInternal)} GB used of ${String.format("%.1f", totalInternal)} GB (${String.format("%.1f", freeInternal)} GB free)")
            sb.appendLine("RAM: ${String.format("%.1f", freeRam)} GB free of ${String.format("%.1f", totalRam)} GB")
            if (memInfo.lowMemory) sb.appendLine("Warning: Low memory!")

            ActionResult.Success(sb.toString().trim())
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't read storage info: ${e.message}")
        }
    }

    fun downloadFile(context: Context, url: String, filename: String?): ActionResult {
        // Validate URL — only allow HTTPS
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return ActionResult.Failure("Invalid URL. Only http:// and https:// URLs are supported.")
        }
        // Block internal network and localhost
        val host = Uri.parse(url).host?.lowercase() ?: ""
        if (host == "localhost" || host.startsWith("127.") || host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
            return ActionResult.Failure("Cannot download from local network addresses.")
        }

        return try {
            val uri = Uri.parse(url)
            val name = filename ?: uri.lastPathSegment ?: "download_${System.currentTimeMillis()}"
            val request = DownloadManager.Request(uri).apply {
                setTitle(name)
                setDescription("Downloaded by LUI")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            ActionResult.Success("Downloading \"$name\" to Downloads folder.")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't start download: ${e.message}")
        }
    }

    fun getWifiInfo(context: Context): ActionResult {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            if (info.networkId == -1) {
                return ActionResult.Success("Not connected to Wi-Fi.")
            }

            @Suppress("DEPRECATION")
            val ssid = info.ssid?.replace("\"", "") ?: "Unknown"
            val rssi = info.rssi
            val speed = info.linkSpeed // Mbps
            val freq = info.frequency // MHz
            val band = if (freq > 4900) "5 GHz" else "2.4 GHz"
            val signal = WifiManager.calculateSignalLevel(rssi, 5)
            val signalDesc = when (signal) {
                0 -> "Very weak"
                1 -> "Weak"
                2 -> "Fair"
                3 -> "Good"
                4 -> "Excellent"
                else -> "Unknown"
            }

            ActionResult.Success("Connected to \"$ssid\" ($band, $signalDesc signal, ${speed} Mbps, ${rssi} dBm).")
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't read Wi-Fi info: ${e.message}")
        }
    }

    fun queryMedia(context: Context, type: String, dateFilter: String?): ActionResult {
        return try {
            val uri = when (type.lowercase()) {
                "photo", "photos", "image", "images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "video", "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "music", "audio", "songs" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.SIZE
            )

            // Date filter
            var selection: String? = null
            var args: Array<String>? = null
            if (dateFilter != null) {
                val cal = Calendar.getInstance()
                when (dateFilter.lowercase()) {
                    "today" -> {
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                    }
                    "yesterday" -> {
                        cal.add(Calendar.DAY_OF_YEAR, -1)
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                    }
                    "this week" -> cal.add(Calendar.DAY_OF_YEAR, -7)
                    "this month" -> cal.add(Calendar.MONTH, -1)
                    else -> cal.add(Calendar.DAY_OF_YEAR, -1) // default to today
                }
                selection = "${MediaStore.MediaColumns.DATE_ADDED} >= ?"
                args = arrayOf((cal.timeInMillis / 1000).toString())
            }

            val cursor = context.contentResolver.query(
                uri, projection, selection, args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )

            var count = 0
            val items = mutableListOf<String>()
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

            cursor?.use {
                count = it.count
                while (it.moveToNext() && items.size < 10) {
                    val name = it.getString(0) ?: "Unknown"
                    val dateAdded = it.getLong(1) * 1000
                    val time = timeFormat.format(Date(dateAdded))
                    items.add("$name ($time)")
                }
            }

            val label = dateFilter ?: "all time"
            val typeLabel = type.lowercase()
            if (count == 0) {
                ActionResult.Success("No ${typeLabel}s found for $label.")
            } else {
                val sb = StringBuilder("$count ${typeLabel}(s) from $label:\n")
                items.forEach { sb.appendLine(it) }
                if (count > 10) sb.appendLine("...and ${count - 10} more")
                ActionResult.Success(sb.toString().trim())
            }
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't query media: ${e.message}")
        }
    }

    fun routeAudio(context: Context, target: String): ActionResult {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            when (target.lowercase()) {
                "speaker", "speakerphone" -> {
                    am.isSpeakerphoneOn = true
                    am.isBluetoothScoOn = false
                    ActionResult.Success("Audio routed to speaker.")
                }
                "bluetooth", "bt", "headset" -> {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                    am.isSpeakerphoneOn = false
                    ActionResult.Success("Audio routed to Bluetooth.")
                }
                "earpiece", "phone" -> {
                    am.isSpeakerphoneOn = false
                    am.isBluetoothScoOn = false
                    ActionResult.Success("Audio routed to earpiece.")
                }
                else -> ActionResult.Failure("Unknown audio target. Use: speaker, bluetooth, or earpiece.")
            }
        } catch (e: Exception) {
            ActionResult.Failure("Couldn't route audio: ${e.message}")
        }
    }
}
