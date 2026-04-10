package com.lui.app.bridge

import android.content.Context
import com.lui.app.data.ToolCall
import com.lui.app.helper.LuiLogger
import com.lui.app.interceptor.ActionExecutor
import com.lui.app.interceptor.actions.ActionResult
import com.lui.app.llm.DeviceContext
import com.lui.app.llm.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP (Model Context Protocol) implementation for LUI.
 * Speaks JSON-RPC 2.0 — compatible with Claude Code, Cursor, and any MCP client.
 *
 * Lifecycle:
 *   Client sends:  initialize → initialized notification → tools/list → tools/call
 *
 * MCP Spec: https://modelcontextprotocol.io/specification/2025-03-26
 */
object BridgeProtocol {

    // Set by RelayClient when connected — used for agent registration via relay
    var relayConnection: org.java_websocket.WebSocket? = null

    private const val PROTOCOL_VERSION = "2025-03-26"
    private const val SERVER_NAME = "lui-android"
    private const val SERVER_VERSION = "0.1.0"
    private const val JSONRPC = "2.0"

    /**
     * Bridge permission tiers:
     *   READ_ONLY  — device state queries only (safe, no side effects)
     *   STANDARD   — read + reversible device controls + navigation + apps
     *   FULL       — all tools including SMS, calls, screenshots, screen control
     *
     * Configurable via SecureKeyStore.bridgePermissionTier
     */
    enum class BridgeTier { READ_ONLY, STANDARD, FULL }

    private val READ_ONLY_TOOLS = setOf(
        "get_time", "get_date", "device_info", "battery", "wifi_info", "storage_info",
        "get_location", "get_distance", "get_steps", "get_proximity", "get_light",
        "now_playing", "read_clipboard", "screen_time", "bridge_status",
        "read_screen",
    )

    private val STANDARD_TOOLS = READ_ONLY_TOOLS + setOf(
        // Reversible device controls
        "toggle_flashlight", "set_volume", "set_brightness", "toggle_dnd",
        "toggle_rotation", "set_ringer", "set_screen_timeout", "keep_screen_on",
        "play_pause", "next_track", "previous_track", "route_audio",
        // Navigation
        "navigate", "search_map",
        // Apps (non-destructive)
        "open_app", "open_app_search", "open_settings", "open_settings_wifi",
        "open_settings_bluetooth", "open_lui",
        // Read personal data
        "read_notifications", "read_calendar", "read_sms",
        "search_contact", "get_digest", "get_2fa_code", "query_media",
        // Meta
        "undo",
    )

    private val FULL_TOOLS = STANDARD_TOOLS + setOf(
        // Communication (sends messages, makes calls)
        "send_sms", "make_call", "create_contact", "create_event",
        // Notifications management
        "clear_notifications", "clear_digest", "config_triage",
        // Screen control
        "find_and_tap", "type_text", "scroll_down", "press_back", "press_home",
        "take_screenshot", "lock_screen", "split_screen",
        // System
        "download_file", "set_wallpaper", "bedtime_mode",
        "start_bridge", "stop_bridge",
    )

    var currentTier: BridgeTier = BridgeTier.STANDARD

    /**
     * Callback for requesting on-device user approval for restricted tools.
     * Set by the ViewModel/Activity. Called on bridge thread.
     * Returns true if approved, false if denied.
     * If null, restricted tools are blocked outright.
     */
    var approvalCallback: ((toolName: String, description: String) -> Boolean)? = null

    private fun getAllowedTools(): Set<String> = when (currentTier) {
        BridgeTier.READ_ONLY -> READ_ONLY_TOOLS
        BridgeTier.STANDARD -> STANDARD_TOOLS
        BridgeTier.FULL -> FULL_TOOLS
    }

    fun handleMessage(context: Context, message: String): String? {
        return try {
            val json = JSONObject(message)
            val method = json.optString("method", "")
            val id = json.opt("id") // Can be string, number, or null (notification)

            LuiLogger.i("MCP", "← $method (id=$id)")

            when (method) {
                // ── Lifecycle ──
                "initialize" -> handleInitialize(id)
                "notifications/initialized" -> { null } // Client acknowledgment, no response needed
                "ping" -> rpcResult(id, JSONObject())

                // ── Tools ──
                "tools/list" -> handleToolsList(id)
                "tools/call" -> handleToolsCall(context, id, json.optJSONObject("params"))

                // ── Events ──
                "lui/subscribe" -> rpcResult(id, BridgeEvents.handleSubscribe(json.optJSONObject("params")))

                // ── Agent Registry (bidirectional) ──
                "lui/register" -> {
                    // Register agent — use relayConn if available (relay-forwarded)
                    val conn = relayConnection
                    if (conn != null) {
                        val result = AgentRegistry.registerAgent(conn, json.optJSONObject("params"))
                        rpcResult(id, result)
                    } else {
                        rpcError(id, -32601, "Agent registration requires a WebSocket connection")
                    }
                }
                "lui/agents" -> rpcResult(id, AgentRegistry.listAgents())
                "lui/response" -> rpcResult(id, AgentRegistry.handleAgentResponse(json.optJSONObject("params")))

                // ── Resources (device state as MCP resource) ──
                "resources/list" -> handleResourcesList(id)
                "resources/read" -> handleResourcesRead(context, id, json.optJSONObject("params"))

                // ── Backward compat with our old protocol ──
                "tool_call" -> handleLegacyToolCall(context, id, json.optJSONObject("params"))
                "list_tools" -> handleToolsList(id)
                "device_state" -> handleLegacyDeviceState(context, id)
                "auth" -> {
                    // Validate token even for relay-forwarded messages
                    val token = json.optJSONObject("params")?.optString("token", "") ?: ""
                    val expectedToken = com.lui.app.bridge.LuiBridgeService.getAuthToken(context)
                    val toolCount = com.lui.app.llm.ToolRegistry.tools.size
                    if (token == expectedToken) {
                        """{"jsonrpc":"2.0","id":"auth","result":{"authenticated":true,"message":"Authenticated via relay. $toolCount tools available."}}"""
                    } else {
                        """{"jsonrpc":"2.0","id":"auth","error":{"code":-32000,"message":"Invalid token"}}"""
                    }
                }

                else -> rpcError(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            LuiLogger.e("MCP", "Protocol error: ${e.message}", e)
            rpcError(null, -32700, "Parse error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════

    private fun handleInitialize(id: Any?): String {
        val capabilities = JSONObject().apply {
            put("tools", JSONObject().put("listChanged", true))
            put("resources", JSONObject().put("subscribe", false).put("listChanged", false))
        }

        val serverInfo = JSONObject().apply {
            put("name", SERVER_NAME)
            put("version", SERVER_VERSION)
        }

        val result = JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", capabilities)
            put("serverInfo", serverInfo)
            put("instructions", "LUI is an Android agent runtime with ${ToolRegistry.tools.size} tools. Permission tier: ${currentTier.name} (${getAllowedTools().size} tools accessible). Tier can be changed in LUI Connection Hub.")
        }

        LuiLogger.i("MCP", "→ Initialized (${ToolRegistry.tools.size} tools)")
        return rpcResult(id, result)
    }

    // ═══════════════════════════════════════
    //  TOOLS
    // ═══════════════════════════════════════

    private fun handleToolsList(id: Any?): String {
        val allowed = getAllowedTools()
        val tools = JSONArray()
        for (tool in ToolRegistry.tools.filter { it.name in allowed }) {
            val t = JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                put("inputSchema", buildInputSchema(tool))
            }
            tools.put(t)
        }

        return rpcResult(id, JSONObject().put("tools", tools))
    }

    private fun handleToolsCall(context: Context, id: Any?, params: JSONObject?): String {
        if (params == null) return rpcError(id, -32602, "Missing params")

        val toolName = params.optString("name", "")
        if (toolName.isBlank()) return rpcError(id, -32602, "Missing tool name")

        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val args = mutableMapOf<String, String>()
        for (k in arguments.keys()) args[k] = arguments.optString(k, "")

        // Enforce permission tier for remote agents
        val allowed = getAllowedTools()
        if (toolName !in allowed) {
            // Tool not in current tier — request on-device approval if callback is set
            val callback = approvalCallback
            if (callback != null) {
                val desc = buildApprovalDescription(toolName, arguments)
                LuiLogger.i("MCP", "Requesting on-device approval for: $toolName")
                val approved = callback(toolName, desc)
                if (!approved) {
                    LuiLogger.i("MCP", "User DENIED: $toolName")
                    return rpcError(id, -32001, "User denied permission for '$toolName' on-device.")
                }
                LuiLogger.i("MCP", "User APPROVED: $toolName")
                // Fall through to execute
            } else {
                LuiLogger.w("MCP", "BLOCKED tool '$toolName' (tier=${currentTier.name})")
                val tierNeeded = when {
                    toolName in FULL_TOOLS -> "FULL"
                    toolName in STANDARD_TOOLS -> "STANDARD"
                    else -> "unknown"
                }
                return rpcError(id, -32001, "Tool '$toolName' is not allowed at permission tier '${currentTier.name}'. Requires tier '$tierNeeded'. Change in LUI Connection Hub or enable on-device approval.")
            }
        }

        LuiLogger.i("MCP", "Calling tool: $toolName $args")

        val toolCall = ToolCall(toolName, args)
        val result = ActionExecutor.execute(context, toolCall)

        val content = JSONArray()
        when (result) {
            is ActionResult.Success -> {
                LuiLogger.i("MCP", "→ OK: ${result.message.take(100)}")
                content.put(JSONObject().put("type", "text").put("text", result.message))
                return rpcResult(id, JSONObject().put("content", content).put("isError", false))
            }
            is ActionResult.Failure -> {
                LuiLogger.w("MCP", "→ FAIL: ${result.message}")
                content.put(JSONObject().put("type", "text").put("text", result.message))
                return rpcResult(id, JSONObject().put("content", content).put("isError", true))
            }
        }
    }

    private fun buildInputSchema(tool: ToolRegistry.ToolDef): JSONObject {
        val schema = JSONObject().apply {
            put("type", "object")
        }
        val properties = JSONObject()
        val required = JSONArray()

        for (p in tool.parameters) {
            val prop = JSONObject().apply {
                put("type", p.type)
                put("description", p.description)
                p.enum?.let { put("enum", JSONArray(it)) }
            }
            properties.put(p.name, prop)
            if (p.required) required.put(p.name)
        }

        schema.put("properties", properties)
        if (required.length() > 0) schema.put("required", required)
        return schema
    }

    private fun buildApprovalDescription(tool: String, args: JSONObject): String {
        return when (tool) {
            "send_sms" -> "Send SMS to ${args.optString("number", "?")}:\n\"${args.optString("message", "")}\""
            "make_call" -> "Call ${args.optString("target", "?")}"
            "read_sms" -> "Read your SMS messages${args.optString("from", "").let { if (it.isNotBlank()) " from $it" else "" }}"
            "take_screenshot" -> "Take a screenshot of your screen"
            "lock_screen" -> "Lock your phone"
            "download_file" -> "Download file from: ${args.optString("url", "?").take(60)}"
            "type_text" -> "Type text into current app: \"${args.optString("text", "").take(40)}\""
            "find_and_tap" -> "Tap \"${args.optString("query", "?")}\" on screen"
            "create_contact" -> "Create contact: ${args.optString("name", "?")} ${args.optString("number", "")}"
            "create_event" -> "Create event: ${args.optString("title", "?")} on ${args.optString("date", "?")}"
            else -> "Execute: $tool"
        }
    }

    // ═══════════════════════════════════════
    //  RESOURCES (device state)
    // ═══════════════════════════════════════

    private fun handleResourcesList(id: Any?): String {
        val resources = JSONArray().apply {
            put(JSONObject().apply {
                put("uri", "lui://device/state")
                put("name", "Device State")
                put("description", "Real-time device state: time, battery, network, volume, brightness, device model")
                put("mimeType", "text/plain")
            })
            put(JSONObject().apply {
                put("uri", "lui://device/tools")
                put("name", "Tool Summary")
                put("description", "Summary of all available LUI tools and their capabilities")
                put("mimeType", "text/plain")
            })
        }
        return rpcResult(id, JSONObject().put("resources", resources))
    }

    private fun handleResourcesRead(context: Context, id: Any?, params: JSONObject?): String {
        val uri = params?.optString("uri", "") ?: ""
        val contents = JSONArray()

        when (uri) {
            "lui://device/state" -> {
                val state = DeviceContext.gather(context)
                contents.put(JSONObject().put("uri", uri).put("mimeType", "text/plain").put("text", state))
            }
            "lui://device/tools" -> {
                val summary = ToolRegistry.tools.joinToString("\n") { "- ${it.name}: ${it.description}" }
                contents.put(JSONObject().put("uri", uri).put("mimeType", "text/plain").put("text", summary))
            }
            else -> return rpcError(id, -32602, "Unknown resource: $uri")
        }

        return rpcResult(id, JSONObject().put("contents", contents))
    }

    // ═══════════════════════════════════════
    //  LEGACY (backward compat)
    // ═══════════════════════════════════════

    private fun handleLegacyToolCall(context: Context, id: Any?, params: JSONObject?): String {
        // Convert old format {tool, args} to MCP format {name, arguments}
        val mcpParams = JSONObject().apply {
            put("name", params?.optString("tool", "") ?: "")
            put("arguments", params?.optJSONObject("args") ?: JSONObject())
        }
        return handleToolsCall(context, id, mcpParams)
    }

    private fun handleLegacyDeviceState(context: Context, id: Any?): String {
        val state = DeviceContext.gather(context)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", state))
        return rpcResult(id, JSONObject().put("content", content))
    }

    // ═══════════════════════════════════════
    //  JSON-RPC 2.0 HELPERS
    // ═══════════════════════════════════════

    private fun rpcResult(id: Any?, result: JSONObject): String {
        return JSONObject().apply {
            put("jsonrpc", JSONRPC)
            if (id != null) put("id", id)
            put("result", result)
        }.toString()
    }

    private fun rpcError(id: Any?, code: Int, message: String): String {
        return JSONObject().apply {
            put("jsonrpc", JSONRPC)
            if (id != null) put("id", id)
            put("error", JSONObject().put("code", code).put("message", message))
        }.toString()
    }
}
