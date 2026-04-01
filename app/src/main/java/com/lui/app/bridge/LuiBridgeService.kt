package com.lui.app.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import com.lui.app.MainActivity
import com.lui.app.R
import com.lui.app.data.SecureKeyStore
import com.lui.app.helper.LuiLogger
import java.util.UUID

/**
 * Foreground service that runs the BYOS WebSocket bridge.
 * Exposes LUI's tool system to remote agents over the local network.
 *
 * Start: context.startForegroundService(Intent(context, LuiBridgeService::class.java))
 * Stop:  context.stopService(Intent(context, LuiBridgeService::class.java))
 */
class LuiBridgeService : Service() {

    companion object {
        private const val CHANNEL_ID = "lui_bridge"
        private const val NOTIFICATION_ID = 9001
        private const val TAG = "BridgeService"

        var instance: LuiBridgeService? = null
            private set

        val isRunning: Boolean get() = instance?.server != null

        fun getConnectionUrl(context: Context): String? {
            if (!isRunning) return null
            val ip = getLocalIpAddress(context) ?: return null
            val port = instance?.server?.port ?: LuiBridgeServer.DEFAULT_PORT
            return "ws://$ip:$port"
        }

        fun getAuthToken(context: Context): String {
            val keyStore = SecureKeyStore(context)
            var token = keyStore.getBridgeToken()
            if (token == null) {
                token = UUID.randomUUID().toString().replace("-", "").take(32)
                keyStore.saveBridgeToken(token)
            }
            return token
        }

        private fun getLocalIpAddress(context: Context): String? {
            return try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ip = wm.connectionInfo.ipAddress
                if (ip == 0) return null
                "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
            } catch (e: Exception) { null }
        }
    }

    private var server: LuiBridgeServer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = getAuthToken(this)
        val port = LuiBridgeServer.DEFAULT_PORT

        startForeground(NOTIFICATION_ID, buildNotification("Starting bridge...", 0))

        try {
            server = LuiBridgeServer(applicationContext, token, port).apply {
                setConnectionListener { count ->
                    updateNotification(count)
                }
                isReuseAddr = true
                start()
            }

            val ip = getLocalIpAddress(this) ?: "unknown"
            LuiLogger.i(TAG, "Bridge started at ws://$ip:$port")
            updateNotification(0)
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Failed to start bridge: ${e.message}", e)
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        try {
            server?.stop(1000)
            server = null
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Error stopping bridge: ${e.message}")
        }
        instance = null
        LuiLogger.i(TAG, "Bridge service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LUI Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WebSocket bridge for remote agent connections"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, connections: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ip = getLocalIpAddress(this) ?: "No Wi-Fi"
        val port = server?.port ?: LuiBridgeServer.DEFAULT_PORT
        val token = getAuthToken(this)
        val title = if (connections > 0) "Bridge: $connections agent(s) connected" else "Bridge active"
        val body = "ws://$ip:$port | Token: ${token.take(8)}...${token.takeLast(4)}"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.status_dot)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(connections: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification("", connections))
    }
}
