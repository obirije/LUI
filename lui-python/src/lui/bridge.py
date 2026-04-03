"""
LUI Bridge Client — Connect to a LUI Android device over MCP WebSocket.

Usage:
    from lui import LuiBridge

    bridge = LuiBridge("ws://PHONE_IP:8765", "YOUR_TOKEN")
    bridge.connect()

    # Call tools
    print(bridge.call_tool("battery"))
    print(bridge.call_tool("get_location"))
    print(bridge.call_tool("toggle_flashlight", {"state": "on"}))

    # Register as agent for bidirectional communication
    bridge = LuiBridge(url, token, agent_name="my-bot",
        on_instruction=lambda instr: f"Executed: {instr}",
        on_event=lambda t, d: print(f"Event: {t}"))
    bridge.connect()
"""

import json
import os
import threading
import time

import websocket


class LuiBridge:
    """WebSocket client for LUI's MCP bridge."""

    def __init__(self, url, token, agent_name=None, on_instruction=None, on_event=None):
        """
        Args:
            url: WebSocket URL (e.g., ws://PHONE_IP:8765)
            token: Bridge auth token
            agent_name: If set, registers as a named agent for bidirectional comms
            on_instruction: Callback(instruction: str) -> str. Called when user sends
                           instruction via LUI (e.g., "tell my-bot to deploy")
            on_event: Callback(event_type: str, data: dict). Called for phone events
                     (notifications, calls, 2FA codes, etc.)
        """
        self.url = url
        self.token = token
        self.agent_name = agent_name
        self.on_instruction = on_instruction
        self.on_event = on_event
        self.ws = None
        self.connected = False
        self.tools = []
        self._listen_thread = None
        self._request_id = 0
        self._pending = {}

    def connect(self):
        """Connect, authenticate, initialize MCP, optionally register as agent.

        Returns:
            int: Number of available tools
        """
        self.ws = websocket.create_connection(self.url, timeout=30)

        # Auth
        self._send({"method": "auth", "params": {"token": self.token}})
        auth = json.loads(self.ws.recv())
        if not auth.get("result", {}).get("authenticated"):
            raise ConnectionError(f"Authentication failed: {auth}")

        # Initialize MCP
        client_name = self.agent_name or "lui-python"
        self._call("initialize", {
            "protocolVersion": "2025-03-26",
            "clientInfo": {"name": client_name, "version": "0.1.0"}
        })

        # Register as agent if name provided
        if self.agent_name:
            self._send({
                "jsonrpc": "2.0", "id": self._next_id(),
                "method": "lui/register",
                "params": {
                    "name": self.agent_name,
                    "description": f"{self.agent_name} agent",
                    "capabilities": []
                }
            })

        # Drain setup responses
        time.sleep(0.5)
        try:
            while True:
                self.ws.settimeout(0.3)
                self.ws.recv()
        except Exception:
            pass
        self.ws.settimeout(30)

        # Get tools
        tools_resp = self._call("tools/list")
        self.tools = tools_resp.get("tools", [])

        self.connected = True

        # Start listener
        self._listen_thread = threading.Thread(target=self._listen, daemon=True)
        self._listen_thread.start()

        return len(self.tools)

    def disconnect(self):
        """Disconnect from the bridge."""
        self.connected = False
        if self.ws:
            try:
                self.ws.close()
            except Exception:
                pass
            self.ws = None

    def call_tool(self, name, arguments=None):
        """Call a LUI tool and return the result text.

        Args:
            name: Tool name (e.g., "battery", "get_location", "toggle_flashlight")
            arguments: Dict of tool arguments (e.g., {"state": "on"})

        Returns:
            str: Tool result text
        """
        resp = self._call("tools/call", {"name": name, "arguments": arguments or {}})
        content = resp.get("content", [])
        if content:
            text = content[0].get("text", "")
            is_error = resp.get("isError", False)
            if is_error:
                raise ToolError(text)
            return text
        return ""

    def get_device_state(self):
        """Get current device state (time, battery, network, etc.)."""
        resp = self._call("resources/read", {"uri": "lui://device/state"})
        contents = resp.get("contents", [])
        return contents[0].get("text", "") if contents else ""

    def list_tools(self):
        """Return list of available tool names."""
        return [t["name"] for t in self.tools]

    def list_tools_detailed(self):
        """Return full tool definitions with descriptions and schemas."""
        return self.tools

    def ping(self):
        """Ping the bridge. Returns True if alive."""
        try:
            self._call("ping")
            return True
        except Exception:
            return False

    # ── Internal ──

    def _next_id(self):
        self._request_id += 1
        return str(self._request_id)

    def _send(self, msg):
        self.ws.send(json.dumps(msg))

    def _call(self, method, params=None):
        """Send a request and wait for response."""
        req_id = self._next_id()
        msg = {"jsonrpc": "2.0", "id": req_id, "method": method}
        if params:
            msg["params"] = params

        event = threading.Event()
        self._pending[req_id] = {"event": event, "result": None}

        self._send(msg)

        # If listener isn't running yet, read directly
        if not self._listen_thread or not self._listen_thread.is_alive():
            self.ws.settimeout(10)
            for _ in range(20):
                try:
                    raw = self.ws.recv()
                    resp = json.loads(raw)
                    if str(resp.get("id")) == req_id:
                        self.ws.settimeout(30)
                        return resp.get("result", {})
                except Exception:
                    break
            self.ws.settimeout(30)
            return {}
        else:
            event.wait(timeout=30)
            result = self._pending.pop(req_id, {}).get("result", {})
            return result or {}

    def _listen(self):
        """Background listener for events and instructions."""
        while self.connected:
            try:
                self.ws.settimeout(2)
                raw = self.ws.recv()
                msg = json.loads(raw)
                method = msg.get("method", "")
                msg_id = str(msg.get("id", ""))

                # Response to pending request
                if msg_id in self._pending:
                    self._pending[msg_id]["result"] = msg.get("result", {})
                    self._pending[msg_id]["event"].set()
                    continue

                # Instruction from user via LUI
                if method == "lui/instruction":
                    instruction = msg["params"]["instruction"]
                    instr_id = msg.get("id", "")
                    response = "No handler configured."
                    if self.on_instruction:
                        try:
                            response = self.on_instruction(instruction)
                        except Exception as e:
                            response = f"Error: {e}"

                    self._send({
                        "jsonrpc": "2.0", "id": "resp",
                        "method": "lui/response",
                        "params": {
                            "instruction_id": str(instr_id),
                            "result": str(response)[:1000]
                        }
                    })

                # Event from phone
                elif method == "notifications/lui/event":
                    event = msg["params"]
                    if self.on_event:
                        try:
                            self.on_event(event["type"], event.get("data", {}))
                        except Exception:
                            pass

            except websocket.WebSocketTimeoutException:
                pass
            except websocket.WebSocketConnectionClosedException:
                self.connected = False
                break
            except Exception:
                pass


class ToolError(Exception):
    """Raised when a tool call returns an error."""
    pass
