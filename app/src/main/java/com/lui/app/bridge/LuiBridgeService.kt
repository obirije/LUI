package com.lui.app.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
        val isRelayConnected: Boolean get() = instance?.relay?.isConnected == true

        // Observable state for UI sync
        private val stateListeners = mutableListOf<(Boolean) -> Unit>()
        fun addStateListener(listener: (Boolean) -> Unit) { stateListeners.add(listener) }
        fun removeStateListener(listener: (Boolean) -> Unit) { stateListeners.remove(listener) }
        private fun notifyState(running: Boolean) { stateListeners.forEach { it(running) } }

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
    private var relay: RelayClient? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting bridge...", 0))

        // Move all server/relay startup off the main thread
        Thread {
            val token = getAuthToken(this)
            val port = LuiBridgeServer.DEFAULT_PORT

            // Load permission tier from settings
            val tierName = SecureKeyStore(this).bridgePermissionTier
            BridgeProtocol.currentTier = try {
                BridgeProtocol.BridgeTier.valueOf(tierName)
            } catch (_: Exception) { BridgeProtocol.BridgeTier.STANDARD }
            LuiLogger.i(TAG, "Bridge permission tier: ${BridgeProtocol.currentTier}")

            try {
                server = LuiBridgeServer(applicationContext, token, port).apply {
                    setConnectionListener { count ->
                        updateNotification(count)
                    }
                    isReuseAddr = true
                    start()
                }

                BridgeEvents.setServer(server)

                val ip = getLocalIpAddress(this) ?: "unknown"
                LuiLogger.i(TAG, "Bridge started at ws://$ip:$port")

                // Start relay if configured
                val keyStore = SecureKeyStore(this)
                val relayUrl = keyStore.relayUrl
                if (keyStore.relayEnabled && relayUrl != null) {
                    relay = RelayClient(applicationContext, relayUrl, token).apply {
                        onStatusChange = { status ->
                            LuiLogger.i(TAG, "Relay: $status")
                            updateNotification(server?.connectedCount ?: 0)
                        }
                        connect()
                    }
                    registerNetworkCallback()
                }

                updateNotification(0)
                notifyState(true)
            } catch (e: Exception) {
                LuiLogger.e(TAG, "Failed to start bridge: ${e.message}", e)
                android.os.Handler(android.os.Looper.getMainLooper()).post { stopSelf() }
            }
        }.start()

        return START_STICKY
    }

    override fun onDestroy() {
        BridgeEvents.setServer(null)
        unregisterNetworkCallback()
        relay?.disconnect()
        relay = null
        try {
            server?.stop(1000)
            server = null
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Error stopping bridge: ${e.message}")
        }
        instance = null
        notifyState(false)
        LuiLogger.i(TAG, "Bridge service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LUI Bridge",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "WebSocket bridge for remote agent connections"
                setShowBadge(false)
                setSound(null, null) // No sound, but visible
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
        val body = "ws://$ip:$port"
        val relayStatus = if (relay?.isConnected == true) "\nRelay: connected" else if (relay != null) "\nRelay: reconnecting" else ""
        val bigText = "URL: ws://$ip:$port\nToken: $token\nTier: ${BridgeProtocol.currentTier.name}$relayStatus"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .setSmallIcon(R.drawable.status_dot)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(connections: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification("", connections))
    }

    @Volatile private var networkWasLost = false

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (networkWasLost) {
                    // Came back from a genuine outage — the existing WS may
                    // look alive but be half-dead. Force a fresh socket.
                    networkWasLost = false
                    LuiLogger.i(TAG, "Network returned after loss — forcing relay reconnect")
                    relay?.forceReconnect()
                } else {
                    // Normal availability signal (e.g. service start, secondary
                    // network appearing) — just skip any pending backoff.
                    LuiLogger.i(TAG, "Network available — poking relay reconnect")
                    relay?.reconnectNow()
                }
            }

            override fun onLost(network: Network) {
                networkWasLost = true
                LuiLogger.i(TAG, "Network lost")
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Failed to register network callback: ${e.message}", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        networkCallback = null
    }
}
