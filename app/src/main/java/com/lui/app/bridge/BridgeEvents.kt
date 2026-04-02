package com.lui.app.bridge

import com.lui.app.helper.LuiLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Event streaming for the BYOS bridge.
 * Pushes real-time events to connected agents via JSON-RPC notifications.
 *
 * Event format (MCP notification — no id, no response expected):
 * {"jsonrpc":"2.0","method":"notifications/lui/event","params":{"type":"notification","data":{...}}}
 *
 * Event types:
 *   notification    — new notification received
 *   notification_2fa — 2FA code captured
 *   call_incoming   — incoming phone call
 *   call_missed     — missed call
 *   battery_change  — charging started/stopped, level milestone
 *   bridge_connect  — agent connected to bridge
 *   bridge_disconnect — agent disconnected
 *   media_change    — track changed, playback started/stopped
 */
object BridgeEvents {

    private var server: LuiBridgeServer? = null

    // Agents can subscribe to specific event types
    private val subscriptions = mutableMapOf<String, MutableSet<String>>() // clientId -> event types
    private var allEventsEnabled = true // Default: send all events to all clients

    fun setServer(bridgeServer: LuiBridgeServer?) {
        server = bridgeServer
    }

    fun handleSubscribe(params: JSONObject?): JSONObject {
        val types = params?.optJSONArray("types")
        if (types != null) {
            // Specific subscription (future: per-client)
            val typeList = mutableListOf<String>()
            for (i in 0 until types.length()) typeList.add(types.getString(i))
            LuiLogger.i("EVENTS", "Subscription: $typeList")
        }
        return JSONObject().put("subscribed", true)
    }

    // ── Event emitters ──

    fun onNotification(app: String, title: String, text: String, bucket: String) {
        emit("notification", JSONObject().apply {
            put("app", app)
            put("title", title)
            put("text", text)
            put("bucket", bucket) // URGENT, NOISE, AUTO_ACTION
        })
    }

    fun on2faCode(code: String, app: String) {
        emit("notification_2fa", JSONObject().apply {
            put("code", code)
            put("app", app)
        })
    }

    fun onCallIncoming(caller: String) {
        emit("call_incoming", JSONObject().apply {
            put("caller", caller)
        })
    }

    fun onCallMissed(caller: String) {
        emit("call_missed", JSONObject().apply {
            put("caller", caller)
        })
    }

    fun onBatteryChange(level: Int, charging: Boolean) {
        emit("battery_change", JSONObject().apply {
            put("level", level)
            put("charging", charging)
        })
    }

    fun onBridgeConnect(address: String) {
        emit("bridge_connect", JSONObject().apply {
            put("address", address)
        })
    }

    fun onBridgeDisconnect(address: String) {
        emit("bridge_disconnect", JSONObject().apply {
            put("address", address)
        })
    }

    fun onMediaChange(title: String, artist: String, playing: Boolean) {
        emit("media_change", JSONObject().apply {
            put("title", title)
            put("artist", artist)
            put("playing", playing)
        })
    }

    // ── Core emit ──

    private fun emit(type: String, data: JSONObject) {
        val s = server ?: return

        val event = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "notifications/lui/event")
            put("params", JSONObject().apply {
                put("type", type)
                put("timestamp", System.currentTimeMillis())
                put("data", data)
            })
        }

        LuiLogger.d("EVENTS", "→ $type: ${data.toString().take(80)}")
        s.broadcastToAuthenticated(event.toString())
    }
}
