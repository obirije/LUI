package com.lui.app.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lui.app.helper.LuiLogger
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

/**
 * Outbound WebSocket client that connects to a relay server.
 * This allows agents outside the LAN to reach LUI through the relay.
 *
 * Flow:
 *   LUI connects OUT to relay → relay assigns a session →
 *   remote agents connect to relay → relay forwards messages bidirectionally →
 *   LUI handles them via BridgeProtocol (same as local bridge)
 *
 * Relay URL format: wss://relay.example.com/connect?device_token=XXX
 */
class RelayClient(
    private val appContext: Context,
    private val relayUrl: String,
    private val deviceToken: String
) {
    companion object {
        private const val TAG = "RelayClient"
        private const val INITIAL_RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val CONNECTION_LOST_TIMEOUT_SEC = 30
    }

    private var client: WebSocketClient? = null
    private var reconnectAttempts = 0
    private var isRunning = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var pendingReconnect: Runnable? = null
    var onStatusChange: ((String) -> Unit)? = null

    fun connect() {
        if (isRunning) return
        isRunning = true
        reconnectAttempts = 0
        doConnect()
    }

    fun disconnect() {
        isRunning = false
        cancelPendingReconnect()
        client?.close()
        client = null
        onStatusChange?.invoke("disconnected")
        LuiLogger.i(TAG, "Relay disconnected")
    }

    /**
     * Skip the remaining backoff wait and reconnect immediately.
     * Safe to call from network-available callbacks; no-op if already connected
     * or if no reconnect is pending.
     */
    fun reconnectNow() {
        if (!isRunning || isConnected) return
        val pending = pendingReconnect ?: return
        reconnectHandler.removeCallbacks(pending)
        pendingReconnect = null
        reconnectAttempts = 0
        LuiLogger.i(TAG, "reconnectNow() — skipping backoff")
        doConnect()
    }

    /**
     * Unconditionally close any current socket and restart the connection.
     * Use this when the underlying network transitioned (e.g. Wi-Fi lost then
     * regained) — the old socket may look open but be dead, and we don't want
     * to wait for ping timeout to discover that.
     */
    fun forceReconnect() {
        if (!isRunning) return
        LuiLogger.i(TAG, "forceReconnect() — closing stale socket")
        cancelPendingReconnect()
        reconnectAttempts = 0
        try { client?.close() } catch (_: Exception) {}
        // onClose will fire and schedule a reconnect via scheduleReconnect; but
        // we also kick off immediately for minimal latency.
        doConnect()
    }

    val isConnected: Boolean get() = client?.isOpen == true

    private fun cancelPendingReconnect() {
        pendingReconnect?.let { reconnectHandler.removeCallbacks(it) }
        pendingReconnect = null
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        reconnectAttempts++
        // Exponential backoff with jitter. Shift cap at 5 keeps max base at
        // INITIAL * 32 = 64s; final coerceAtMost then clamps to MAX_RECONNECT_DELAY_MS.
        val baseDelay = (INITIAL_RECONNECT_DELAY_MS shl (reconnectAttempts - 1).coerceAtMost(5))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)
        val jitter = (Math.random() * 1000).toLong()
        val delay = baseDelay + jitter
        LuiLogger.i(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
        onStatusChange?.invoke("reconnecting ($reconnectAttempts)")

        cancelPendingReconnect()
        val runnable = Runnable {
            pendingReconnect = null
            if (isRunning) doConnect()
        }
        pendingReconnect = runnable
        reconnectHandler.postDelayed(runnable, delay)
    }

    private fun doConnect() {
        if (!isRunning) return

        // Connect to /device path — no token in URL
        val baseUrl = relayUrl.trimEnd('/')
        val fullUrl = if (baseUrl.endsWith("/device")) baseUrl else "$baseUrl/device"
        val uri = URI(fullUrl)
        LuiLogger.i(TAG, "Connecting to relay: ${uri.host}")
        onStatusChange?.invoke("connecting")

        client = object : WebSocketClient(uri) {
            override fun onOpen(handshake: ServerHandshake?) {
                reconnectAttempts = 0
                LuiLogger.i(TAG, "Connected to relay, authenticating...")

                // Send auth as first message — token NOT in URL
                send(org.json.JSONObject().apply {
                    put("type", "auth")
                    put("device_token", deviceToken)
                }.toString())

                // Expose the connection for agent registration via relay
                BridgeProtocol.relayConnection = this.connection
                onStatusChange?.invoke("connected")
            }

            override fun onMessage(message: String?) {
                if (message == null) return
                // Handle incoming messages the same way as local bridge
                val response = BridgeProtocol.handleMessage(appContext, message)
                if (response != null) send(response)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                LuiLogger.i(TAG, "Relay connection closed: code=$code reason=$reason")
                onStatusChange?.invoke("disconnected")
                if (isRunning) scheduleReconnect()
            }

            override fun onError(ex: Exception?) {
                LuiLogger.e(TAG, "Relay error: ${ex?.message}", ex)
            }
        }.apply {
            // Application-level ping/pong: send a ping every N seconds; close if
            // no pong within 2N. Prevents Fly edge from dropping idle connections.
            connectionLostTimeout = CONNECTION_LOST_TIMEOUT_SEC
        }

        try {
            client?.connect()
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Relay connect failed: ${e.message}", e)
            onStatusChange?.invoke("error")
            if (isRunning) scheduleReconnect()
        }
    }
}
