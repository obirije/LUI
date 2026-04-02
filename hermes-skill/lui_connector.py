#!/usr/bin/env python3
"""
LUI Bridge Connector for Hermes Agent.

This module connects to a LUI Android device and registers as a Hermes agent.
It enables bidirectional communication:
  - Hermes can call LUI's 72 phone tools
  - LUI can send instructions to Hermes
  - Phone events (notifications, calls, 2FA) stream to Hermes

Usage as a standalone bridge:
    python lui_connector.py --url ws://192.168.1.91:8765 --token YOUR_TOKEN

Usage from Hermes skill system:
    Configured via hermes skills config lui-bridge
"""

import json
import sys
import threading
import time
import os

try:
    import websocket
except ImportError:
    print("Install websocket-client: pip install websocket-client")
    sys.exit(1)


class LuiBridge:
    """Manages the WebSocket connection to a LUI device."""

    def __init__(self, url, token, agent_name="hermes", on_instruction=None, on_event=None):
        self.url = url
        self.token = token
        self.agent_name = agent_name
        self.on_instruction = on_instruction  # callback(instruction) -> response string
        self.on_event = on_event              # callback(event_type, event_data)
        self.ws = None
        self.connected = False
        self.tools = []
        self._listen_thread = None
        self._request_id = 0
        self._pending = {}  # id -> threading.Event + result

    def connect(self):
        """Connect to the LUI bridge, authenticate, and register as agent."""
        self.ws = websocket.create_connection(self.url, timeout=30)

        # Auth
        self._send({"method": "auth", "params": {"token": self.token}})
        auth = json.loads(self.ws.recv())
        if not auth.get("result", {}).get("authenticated"):
            raise ConnectionError(f"Auth failed: {auth}")

        # Initialize MCP
        resp = self._call("initialize", {
            "protocolVersion": "2025-03-26",
            "clientInfo": {"name": self.agent_name, "version": "1.0"}
        })

        # Register as agent
        self._send({
            "jsonrpc": "2.0", "id": self._next_id(),
            "method": "lui/register",
            "params": {
                "name": self.agent_name,
                "description": "Hermes AI agent",
                "capabilities": ["chat", "code", "search", "deploy", "analyze"]
            }
        })

        # Drain registration responses
        time.sleep(0.5)
        try:
            while True:
                self.ws.settimeout(0.3)
                self.ws.recv()
        except:
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
            self.ws.close()
            self.ws = None

    def call_tool(self, name, arguments=None):
        """Call a LUI tool and return the result text."""
        resp = self._call("tools/call", {"name": name, "arguments": arguments or {}})
        content = resp.get("content", [])
        if content:
            return content[0].get("text", "No result")
        return "No result"

    def get_device_state(self):
        """Get current device state (time, battery, network, etc.)."""
        resp = self._call("resources/read", {"uri": "lui://device/state"})
        contents = resp.get("contents", [])
        if contents:
            return contents[0].get("text", "")
        return ""

    def list_tools(self):
        """Return list of available tool names."""
        return [t["name"] for t in self.tools]

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
            while True:
                resp = json.loads(self.ws.recv())
                if str(resp.get("id")) == req_id:
                    return resp.get("result", {})
                # Handle events inline during setup
        else:
            event.wait(timeout=30)
            result = self._pending.pop(req_id, {}).get("result", {})
            return result or {}

    def _listen(self):
        """Background listener for events and instruction callbacks."""
        while self.connected:
            try:
                self.ws.settimeout(2)
                raw = self.ws.recv()
                msg = json.loads(raw)
                method = msg.get("method", "")
                msg_id = str(msg.get("id", ""))

                # Response to a pending request
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
                            "result": str(response)[:500]
                        }
                    })

                # Event from phone
                elif method == "notifications/lui/event":
                    event = msg["params"]
                    if self.on_event:
                        try:
                            self.on_event(event["type"], event.get("data", {}))
                        except:
                            pass

            except websocket.WebSocketTimeoutException:
                pass
            except websocket.WebSocketConnectionClosedException:
                self.connected = False
                break
            except Exception:
                pass


def main():
    """Standalone bridge for testing."""
    import argparse

    parser = argparse.ArgumentParser(description="LUI Bridge Connector")
    parser.add_argument("--url", required=True, help="LUI bridge URL (ws://IP:8765)")
    parser.add_argument("--token", required=True, help="Bridge auth token")
    parser.add_argument("--name", default="hermes", help="Agent name")
    args = parser.parse_args()

    def handle_instruction(instruction):
        print(f"\n[Instruction] {instruction}")
        return f"Received: {instruction}"

    def handle_event(event_type, data):
        print(f"[Event] {event_type}: {json.dumps(data)[:80]}")

    bridge = LuiBridge(args.url, args.token, args.name,
                       on_instruction=handle_instruction,
                       on_event=handle_event)

    tool_count = bridge.connect()
    print(f"Connected to LUI — {tool_count} tools available")
    print(f"Device: {bridge.get_device_state()[:80]}")
    print("\nListening for instructions and events... (Ctrl+C to exit)")

    try:
        while bridge.connected:
            time.sleep(1)
    except KeyboardInterrupt:
        bridge.disconnect()
        print("\nDisconnected.")


if __name__ == "__main__":
    main()
