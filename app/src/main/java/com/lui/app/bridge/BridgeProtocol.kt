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

    private const val PROTOCOL_VERSION = "2025-03-26"
    private const val SERVER_NAME = "lui-android"
    private const val SERVER_VERSION = "0.1.0"
    private const val JSONRPC = "2.0"

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

                // ── Resources (device state as MCP resource) ──
                "resources/list" -> handleResourcesList(id)
                "resources/read" -> handleResourcesRead(context, id, json.optJSONObject("params"))

                // ── Backward compat with our old protocol ──
                "tool_call" -> handleLegacyToolCall(context, id, json.optJSONObject("params"))
                "list_tools" -> handleToolsList(id)
                "device_state" -> handleLegacyDeviceState(context, id)
                "auth" -> null // Handled by the server, not protocol

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
            put("instructions", "LUI is an Android agent runtime with ${ToolRegistry.tools.size} tools for device control, communication, navigation, sensors, screen interaction, and more.")
        }

        LuiLogger.i("MCP", "→ Initialized (${ToolRegistry.tools.size} tools)")
        return rpcResult(id, result)
    }

    // ═══════════════════════════════════════
    //  TOOLS
    // ═══════════════════════════════════════

    private fun handleToolsList(id: Any?): String {
        val tools = JSONArray()
        for (tool in ToolRegistry.tools) {
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
