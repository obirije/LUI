package com.lui.app.bridge

import android.content.Context
import com.lui.app.helper.LuiLogger
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * WebSocket server that exposes LUI's 67 tools to remote agents.
 * Runs on a configurable port (default 8765).
 * Authenticates connections via bearer token in the first message.
 *
 * Remote agents connect and send JSON-RPC messages to call tools,
 * list available tools, or query device state.
 */
class LuiBridgeServer(
    private val appContext: Context,
    private val authToken: String,
    port: Int = DEFAULT_PORT
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        const val DEFAULT_PORT = 8765
        private const val TAG = "BridgeServer"
        private const val MAX_CONNECTIONS = 3
        private const val MAX_AUTH_ATTEMPTS = 5
        private const val RATE_LIMIT_PER_SECOND = 10
    }

    // Track authenticated connections
    private val authenticatedClients = mutableSetOf<WebSocket>()
    private val authAttempts = mutableMapOf<String, Int>() // IP -> failed attempts
    private val messageTimestamps = mutableMapOf<WebSocket, MutableList<Long>>() // rate limiting
    private var onConnectionChange: ((Int) -> Unit)? = null

    fun setConnectionListener(listener: (Int) -> Unit) {
        onConnectionChange = listener
    }

    val connectedCount: Int get() = authenticatedClients.size

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        val address = conn.remoteSocketAddress?.toString() ?: "unknown"
        val ip = address.substringBefore(":")

        // Connection limit
        if (authenticatedClients.size >= MAX_CONNECTIONS) {
            LuiLogger.w(TAG, "Connection rejected — max $MAX_CONNECTIONS reached: $address")
            conn.close(1008, "Maximum connections reached")
            return
        }

        // Check auth attempt limit
        if ((authAttempts[ip] ?: 0) >= MAX_AUTH_ATTEMPTS) {
            LuiLogger.w(TAG, "Connection rejected — too many failed auth from $ip")
            conn.close(1008, "Too many failed authentication attempts")
            return
        }

        LuiLogger.i(TAG, "New connection from $address — awaiting auth")

        // Check if auth token is in the URL query or headers
        val uri = handshake?.resourceDescriptor ?: ""
        val headerToken = handshake?.getFieldValue("Authorization")?.removePrefix("Bearer ")?.trim()
        val queryToken = if (uri.contains("token=")) {
            uri.substringAfter("token=").substringBefore("&")
        } else null

        if (headerToken == authToken || queryToken == authToken) {
            authenticatedClients.add(conn)
            LuiLogger.i(TAG, "Auto-authenticated via header/query: $address")
            conn.send("""{"jsonrpc":"2.0","id":"auth","result":{"authenticated":true,"message":"Authenticated via header. Send 'initialize' to begin MCP session."}}""")
            onConnectionChange?.invoke(connectedCount)
            BridgeEvents.onBridgeConnect(address)
        }
        // Otherwise, wait for auth message
    }

    override fun onMessage(conn: WebSocket, message: String?) {
        if (message == null) return

        // If not authenticated, first message must be auth
        if (conn !in authenticatedClients) {
            if (handleAuth(conn, message)) return
            conn.send("""{"jsonrpc":"2.0","error":{"code":-32000,"message":"Not authenticated. Send: {\"method\":\"auth\",\"params\":{\"token\":\"your_token\"}}"}}""")
            return
        }

        // Rate limiting
        val now = System.currentTimeMillis()
        val timestamps = messageTimestamps.getOrPut(conn) { mutableListOf() }
        timestamps.add(now)
        timestamps.removeAll { it < now - 1000 }
        if (timestamps.size > RATE_LIMIT_PER_SECOND) {
            conn.send("""{"jsonrpc":"2.0","error":{"code":-32000,"message":"Rate limit exceeded. Max $RATE_LIMIT_PER_SECOND requests/second."}}""")
            return
        }

        // Handle agent registration (needs the connection object)
        try {
            val json = org.json.JSONObject(message)
            if (json.optString("method") == "lui/register") {
                val id = json.opt("id")
                val result = AgentRegistry.registerAgent(conn, json.optJSONObject("params"))
                val resp = org.json.JSONObject().put("jsonrpc", "2.0").put("result", result)
                if (id != null) resp.put("id", id)
                conn.send(resp.toString())
                return
            }
        } catch (_: Exception) {}

        // Authenticated — handle protocol message
        val response = BridgeProtocol.handleMessage(appContext, message)
        if (response != null) conn.send(response)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        val wasAuthenticated = conn in authenticatedClients
        authenticatedClients.remove(conn)
        AgentRegistry.unregisterAgent(conn)
        messageTimestamps.remove(conn)
        val address = conn.remoteSocketAddress?.toString() ?: "unknown"
        LuiLogger.i(TAG, "Connection closed: $address (code=$code)")
        onConnectionChange?.invoke(connectedCount)
        if (wasAuthenticated) BridgeEvents.onBridgeDisconnect(address)
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        LuiLogger.e(TAG, "WebSocket error: ${ex?.message}", ex)
    }

    override fun onStart() {
        LuiLogger.i(TAG, "Bridge server started on port $port")
    }

    private fun handleAuth(conn: WebSocket, message: String): Boolean {
        return try {
            val json = org.json.JSONObject(message)
            if (json.optString("method") == "auth") {
                val token = json.optJSONObject("params")?.optString("token", "") ?: ""
                if (token == authToken) {
                    authenticatedClients.add(conn)
                    val address = conn.remoteSocketAddress?.toString() ?: "unknown"
                    LuiLogger.i(TAG, "Authenticated: $address")
                    conn.send("""{"jsonrpc":"2.0","id":"auth","result":{"authenticated":true,"message":"Authenticated. Send 'initialize' to begin MCP session. ${com.lui.app.llm.ToolRegistry.tools.size} tools available."}}""")
                    onConnectionChange?.invoke(connectedCount)
                    BridgeEvents.onBridgeConnect(address)
                    true
                } else {
                    val ip = conn.remoteSocketAddress?.toString()?.substringBefore(":") ?: ""
                    authAttempts[ip] = (authAttempts[ip] ?: 0) + 1
                    LuiLogger.w(TAG, "Auth failed: wrong token from $ip (attempt ${authAttempts[ip]})")
                    conn.send("""{"jsonrpc":"2.0","error":{"code":-32000,"message":"Invalid token"}}""")
                    if ((authAttempts[ip] ?: 0) >= MAX_AUTH_ATTEMPTS) conn.close(1008, "Too many failed attempts")
                    true
                }
            } else false
        } catch (e: Exception) { false }
    }

    /**
     * Send a message to all authenticated clients (e.g., notifications, events)
     */
    fun broadcastToAuthenticated(message: String) {
        for (client in authenticatedClients.toList()) {
            try {
                if (client.isOpen) client.send(message)
            } catch (e: Exception) {
                LuiLogger.e(TAG, "Broadcast error: ${e.message}")
            }
        }
    }
}
