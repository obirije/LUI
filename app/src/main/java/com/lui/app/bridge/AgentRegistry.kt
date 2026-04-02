package com.lui.app.bridge

import com.lui.app.helper.LuiLogger
import org.java_websocket.WebSocket
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages registered remote agents and enables bidirectional communication.
 *
 * Agents register with: {"method":"lui/register","params":{"name":"deploy-bot","capabilities":["deploy","test","status"],"description":"Handles deployment"}}
 * LUI can send instructions: {"method":"lui/instruct","params":{"agent":"deploy-bot","instruction":"run test suite"}}
 * Agent receives: {"jsonrpc":"2.0","method":"lui/instruction","params":{"instruction":"run test suite","from":"user"}}
 * Agent responds: {"jsonrpc":"2.0","id":"...","method":"lui/response","params":{"result":"Tests passed: 42/42"}}
 */
object AgentRegistry {

    data class Agent(
        val name: String,
        val description: String,
        val capabilities: List<String>,
        val conn: WebSocket
    )

    private val agents = mutableMapOf<String, Agent>() // name -> Agent

    val registeredAgents: List<Agent> get() = agents.values.toList()

    fun registerAgent(conn: WebSocket, params: JSONObject?): JSONObject {
        val name = params?.optString("name", "") ?: ""
        if (name.isBlank()) return JSONObject().put("error", "Agent name required")

        val description = params?.optString("description", "") ?: ""
        val caps = mutableListOf<String>()
        params?.optJSONArray("capabilities")?.let { arr ->
            for (i in 0 until arr.length()) caps.add(arr.getString(i))
        }

        agents[name] = Agent(name, description, caps, conn)
        LuiLogger.i("AGENTS", "Registered: $name (${caps.size} capabilities: ${caps.joinToString()})")

        return JSONObject().apply {
            put("registered", true)
            put("name", name)
            put("message", "Agent '$name' registered. LUI can now send you instructions.")
        }
    }

    fun unregisterAgent(conn: WebSocket) {
        val removed = agents.entries.filter { it.value.conn == conn }.map { it.key }
        removed.forEach { name ->
            agents.remove(name)
            LuiLogger.i("AGENTS", "Unregistered: $name (disconnected)")
        }
    }

    fun listAgents(): JSONObject {
        val arr = JSONArray()
        for (agent in agents.values) {
            arr.put(JSONObject().apply {
                put("name", agent.name)
                put("description", agent.description)
                put("capabilities", JSONArray(agent.capabilities))
                put("connected", agent.conn.isOpen)
            })
        }
        return JSONObject().put("agents", arr).put("count", arr.length())
    }

    /**
     * Send an instruction from LUI to a specific agent.
     * Returns the agent's response (blocks until response or timeout).
     */
    fun sendInstruction(agentName: String, instruction: String): String {
        val agent = agents[agentName]
            ?: return "Agent '$agentName' not found. Available: ${agents.keys.joinToString()}"

        if (!agent.conn.isOpen) {
            agents.remove(agentName)
            return "Agent '$agentName' is disconnected."
        }

        val instructionId = "instr_${System.currentTimeMillis()}"
        val message = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", instructionId)
            put("method", "lui/instruction")
            put("params", JSONObject().apply {
                put("instruction", instruction)
                put("from", "user")
                put("timestamp", System.currentTimeMillis())
            })
        }

        LuiLogger.i("AGENTS", "→ Instruction to $agentName: ${instruction.take(80)}")
        agent.conn.send(message.toString())

        // Wait for response (blocking — called from tool execution context)
        val latch = java.util.concurrent.CountDownLatch(1)
        var response = "Agent did not respond within 30 seconds."

        pendingResponses[instructionId] = { result ->
            response = result
            latch.countDown()
        }

        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        pendingResponses.remove(instructionId)

        return response
    }

    // Pending instruction responses (instruction_id -> callback)
    private val pendingResponses = mutableMapOf<String, (String) -> Unit>()

    /**
     * Handle a response from an agent to a LUI instruction.
     */
    fun handleAgentResponse(params: JSONObject?): JSONObject {
        val instructionId = params?.optString("instruction_id", "") ?: ""
        val result = params?.optString("result", "No result provided") ?: "No result provided"

        LuiLogger.i("AGENTS", "← Response for $instructionId: ${result.take(80)}")

        val callback = pendingResponses[instructionId]
        if (callback != null) {
            callback(result)
            return JSONObject().put("received", true)
        }
        return JSONObject().put("received", false).put("error", "No pending instruction for this ID")
    }
}
