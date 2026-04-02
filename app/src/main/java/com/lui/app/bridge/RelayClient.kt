package com.lui.app.bridge

import android.content.Context
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
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    private var client: WebSocketClient? = null
    private var reconnectAttempts = 0
    private var isRunning = false
    var onStatusChange: ((String) -> Unit)? = null

    fun connect() {
        if (isRunning) return
        isRunning = true
        reconnectAttempts = 0
        doConnect()
    }

    fun disconnect() {
        isRunning = false
        client?.close()
        client = null
        onStatusChange?.invoke("disconnected")
        LuiLogger.i(TAG, "Relay disconnected")
    }

    val isConnected: Boolean get() = client?.isOpen == true

    private fun doConnect() {
        if (!isRunning) return

        // Append /device path and token if not already in URL
        val separator = if (relayUrl.contains("?")) "&" else "?"
        val fullUrl = if (relayUrl.contains("device_token")) relayUrl
                      else if (relayUrl.endsWith("/device") || relayUrl.endsWith("/device/"))
                          "$relayUrl${separator}device_token=$deviceToken"
                      else "$relayUrl/device?device_token=$deviceToken"
        val uri = URI(fullUrl)
        LuiLogger.i(TAG, "Connecting to relay: ${uri.host}")
        onStatusChange?.invoke("connecting")

        client = object : WebSocketClient(uri) {
            override fun onOpen(handshake: ServerHandshake?) {
                reconnectAttempts = 0
                LuiLogger.i(TAG, "Connected to relay")
                onStatusChange?.invoke("connected")

                // Announce capabilities
                send(org.json.JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", "relay/announce")
                    put("params", org.json.JSONObject().apply {
                        put("device", "lui-android")
                        put("tools", com.lui.app.llm.ToolRegistry.tools.size)
                        put("tier", BridgeProtocol.currentTier.name)
                    })
                }.toString())
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

                // Auto-reconnect if still running
                if (isRunning && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++
                    val delay = RECONNECT_DELAY_MS * reconnectAttempts
                    LuiLogger.i(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
                    onStatusChange?.invoke("reconnecting ($reconnectAttempts)")
                    Thread {
                        Thread.sleep(delay)
                        if (isRunning) doConnect()
                    }.start()
                }
            }

            override fun onError(ex: Exception?) {
                LuiLogger.e(TAG, "Relay error: ${ex?.message}", ex)
            }
        }

        try {
            client?.connect()
        } catch (e: Exception) {
            LuiLogger.e(TAG, "Relay connect failed: ${e.message}", e)
            onStatusChange?.invoke("error")
        }
    }
}
