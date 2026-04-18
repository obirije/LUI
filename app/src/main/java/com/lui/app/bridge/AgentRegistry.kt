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

    /** Fuzzy agent lookup: case-insensitive, ignores hyphens/spaces, supports partial match */
    fun findAgent(query: String): Agent? {
        val normalized = query.lowercase().replace(Regex("[\\s-_]"), "")
        // Exact match first
        agents[query]?.let { return it }
        // Case-insensitive
        agents.entries.find { it.key.equals(query, ignoreCase = true) }?.let { return it.value }
        // Normalized (no hyphens/spaces): "claude code" matches "claude-code"
        agents.entries.find { it.key.lowercase().replace(Regex("[\\s-_]"), "") == normalized }?.let { return it.value }
        // Partial/contains: "claude" matches "claude-code"
        agents.entries.find { it.key.lowercase().contains(normalized) || normalized.contains(it.key.lowercase().replace(Regex("[\\s-_]"), "")) }?.let { return it.value }
        return null
    }

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
        // Fuzzy lookup: case-insensitive, ignore hyphens/spaces, partial match
        val agent = findAgent(agentName)
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

        // Wait for response. We don't impose an upper time bound — long-running
        // agents (claude-code with deep tool use) can take 30+ min. We poll the
        // agent's WS every 2s and bail the moment it disconnects, so the user
        // is never blocked on a dead agent. Generation can also be cancelled
        // by the user from the app.
        val latch = java.util.concurrent.CountDownLatch(1)
        var response = "Agent disconnected without responding."

        pendingResponses[instructionId] = { result ->
            response = result
            latch.countDown()
        }

        while (true) {
            if (latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) break
            if (!agent.conn.isOpen) {
                response = "Agent '$agentName' disconnected while processing."
                break
            }
        }
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
