# LUI Developer Documentation

LUI is an Android agent runtime with 72 tools, exposed over an MCP-compatible WebSocket bridge. Any agent — Claude Code, Hermes, custom bots — can connect and control the phone.

---

## Getting Started

### 1. Install the Python package

```bash
pip install lui-bridge
```

### 2. Enable the bridge on your phone

Open LUI → Connection Hub → toggle **BYOS Bridge** on. Note the URL and token from the notification.

### 3. Connect

```bash
# Check device status
lui bridge status --url ws://PHONE_IP:8765 --token YOUR_TOKEN

# List all 72 tools
lui bridge tools --url ws://PHONE_IP:8765 --token YOUR_TOKEN

# Call a tool
lui bridge call --url ws://PHONE_IP:8765 --token YOUR_TOKEN --tool battery
```

---

## How It Works

```
┌──────────┐         ┌───────────────┐         ┌──────────────────┐
│ Your     │  voice  │  LUI on       │ WebSocket│  Agent machine   │
│ voice/   │ ──────> │  your phone   │ ────────>│  (lui-bridge)    │
│ text     │         │  (72 tools)   │          │                  │
│          │ <────── │               │ <────────│  Claude Code /   │
│          │  result │               │  response│  Hermes / custom │
└──────────┘         └───────────────┘          └──────────────────┘
```

1. LUI runs a WebSocket server on the phone (port 8765)
2. Agents connect from any machine on the same Wi-Fi
3. Agents authenticate with a token and speak MCP protocol (JSON-RPC 2.0)
4. The LLM on the phone can also route instructions to agents bidirectionally

For remote access beyond LAN, use the relay server (see [Relay](#relay) below).

---

## CLI Reference

### lui bridge connect

Connect as a named agent. Receives instructions from the phone user, executes them through the specified backend.

```bash
# Connect Claude Code — full AI conversations + code execution
lui bridge connect --url ws://PHONE_IP:8765 --token TOKEN --agent claude-code --mode claude-code

# Connect Hermes — multi-tool AI agent
lui bridge connect --url ws://PHONE_IP:8765 --token TOKEN --agent hermes --mode hermes

# Connect with raw shell execution
lui bridge connect --url ws://PHONE_IP:8765 --token TOKEN --agent shell-bot --mode shell

# Connect in echo mode (for testing)
lui bridge connect --url ws://PHONE_IP:8765 --token TOKEN --agent test-bot --mode echo
```

Once connected, the phone user can say:
- **"patch me to claude-code"** → enters direct chat mode
- **"@hermes what model are you using"** → one-off instruction
- **"tell claude-code to check git status"** → LLM forwards instruction
- **"LUI come back"** → exits agent chat, returns to LUI

### lui bridge tools

```bash
lui bridge tools --url ws://PHONE_IP:8765 --token TOKEN
```

Lists all available tools with descriptions and parameters.

### lui bridge call

```bash
# Simple tool call
lui bridge call --url ws://PHONE_IP:8765 --token TOKEN --tool battery

# Tool call with arguments
lui bridge call --url ws://PHONE_IP:8765 --token TOKEN --tool toggle_flashlight --args '{"state":"on"}'

# Send SMS
lui bridge call --url ws://PHONE_IP:8765 --token TOKEN --tool send_sms --args '{"number":"Mom","message":"On my way"}'
```

### lui bridge status

```bash
lui bridge status --url ws://PHONE_IP:8765 --token TOKEN
```

Shows connection info, tool count, device state (time, battery, network, etc.).

### lui relay start

```bash
lui relay start --port 9000
```

Starts a relay server for remote access. See [Relay](#relay).

---

## Python API

```python
from lui import LuiBridge

# Connect
bridge = LuiBridge("ws://PHONE_IP:8765", "YOUR_TOKEN")
bridge.connect()  # returns tool count

# Call tools
bridge.call_tool("battery")                    # "Battery is at 73%."
bridge.call_tool("get_location")               # "You're at 123 Main St..."
bridge.call_tool("toggle_flashlight", {"state": "on"})
bridge.call_tool("send_sms", {"number": "Mom", "message": "On my way"})
bridge.call_tool("navigate", {"destination": "the airport"})
bridge.call_tool("open_app_search", {"app": "Spotify", "query": "Despacito"})

# Device state
bridge.get_device_state()    # Time, battery, network, device model
bridge.list_tools()          # List of tool names
bridge.ping()                # True if alive

bridge.disconnect()
```

### Bidirectional Agent

```python
from lui import LuiBridge

def handle_instruction(instruction):
    """Called when phone user says 'tell my-bot to ...'"""
    return f"Done: {instruction}"

def handle_event(event_type, data):
    """Called for phone events"""
    if event_type == "notification_2fa":
        print(f"2FA code: {data['code']}")

bridge = LuiBridge(
    url="ws://PHONE_IP:8765",
    token="YOUR_TOKEN",
    agent_name="my-bot",
    on_instruction=handle_instruction,
    on_event=handle_event
)
bridge.connect()

# Now on the phone:
#   "patch me to my-bot"        → direct chat mode
#   "@my-bot deploy staging"    → one-off instruction
#   "tell my-bot to run tests"  → LLM forwards
#   "LUI come back"             → exit agent chat

import time
while bridge.connected:
    time.sleep(1)
```

---

## Agent Passthrough Mode

When the phone user says "patch me to claude-code", LUI enters **passthrough mode**:

```
User: "patch me to claude-code"
LUI:  "Connected to claude-code. Everything you say goes to claude-code now.
       Say 'LUI come back' to return."

User: "what's the working directory?"
claude-code: "/home/user/Projects"

User: "check git status"
claude-code: "On branch main, 3 files changed..."

User: "LUI come back"
LUI:  "Back with you. claude-code is still connected in the background."

User: "@claude-code deploy to staging"     ← one-off, no mode switch
claude-code: "Deployed. All tests passed."

User: "what's my battery?"                 ← goes to LUI, not the agent
LUI:  "Battery is at 73%."
```

### How passthrough works internally

1. User says "patch me to X" → LUI's LLM calls `start_passthrough(agent="X")` tool
2. LUI enters passthrough mode — all subsequent messages forwarded to agent via WebSocket
3. Agent's `on_instruction` callback receives the message, processes it, returns response
4. Response displayed in LUI chat (with thinking dots animation while waiting)
5. User says "LUI come back" → passthrough ends, back to normal LUI

### @ Mentions

Type `@` in the input field → popup shows connected agents. Tap to autocomplete.

`@hermes deploy staging` sends a one-off instruction without entering passthrough mode.

---

## Event Streaming

LUI pushes real-time events to all connected agents:

```json
← {"jsonrpc":"2.0","method":"notifications/lui/event","params":{
    "type":"notification",
    "timestamp":1775088246884,
    "data":{"app":"com.whatsapp","title":"Mom","text":"Are you coming?","bucket":"URGENT"}
  }}
```

| Event | When | Data |
|:------|:-----|:-----|
| `notification` | New notification | `app`, `title`, `text`, `bucket` |
| `notification_2fa` | 2FA code captured | `code`, `app` |
| `call_incoming` | Incoming call | `caller` |
| `call_missed` | Missed call | `caller` |
| `battery_change` | Charging started/stopped | `level`, `charging` |
| `bridge_connect` | Agent connected | `address` |
| `bridge_disconnect` | Agent disconnected | `address` |
| `media_change` | Track changed | `title`, `artist`, `playing` |

---

## Agent Registration

Agents register themselves so LUI can send instructions to them:

```json
→ {"jsonrpc":"2.0","id":1,"method":"lui/register","params":{
    "name":"deploy-bot",
    "description":"Handles deployments",
    "capabilities":["deploy","test","rollback"]
  }}

→ {"jsonrpc":"2.0","id":2,"method":"lui/agents"}

← (instruction from user via LUI)
← {"jsonrpc":"2.0","id":"instr_123","method":"lui/instruction","params":{
    "instruction":"run test suite","from":"user"
  }}

→ {"jsonrpc":"2.0","id":"resp","method":"lui/response","params":{
    "instruction_id":"instr_123",
    "result":"All 42 tests passed."
  }}
```

---

## MCP Protocol Reference

### Authentication

```json
→ {"method":"auth","params":{"token":"YOUR_TOKEN"}}
← {"jsonrpc":"2.0","id":"auth","result":{"authenticated":true}}
```

### Initialize

```json
→ {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","clientInfo":{"name":"agent","version":"1.0"}}}
← {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{...},"serverInfo":{"name":"lui-android","version":"0.1.0"}}}

→ {"jsonrpc":"2.0","method":"notifications/initialized"}
```

### tools/list, tools/call, resources/read, ping

```json
→ {"jsonrpc":"2.0","id":2,"method":"tools/list"}
→ {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"battery","arguments":{}}}
→ {"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"lui://device/state"}}
→ {"jsonrpc":"2.0","id":5,"method":"ping"}
```

---

## Permission Tiers

| Tier | Tools | What's included |
|:-----|:------|:----------------|
| **READ_ONLY** | 17 | Device state, sensors, battery, time, location, screen reading |
| **STANDARD** | 44 | + controls, navigation, apps, read personal data (default) |
| **FULL** | 72 | + SMS, calls, screen control, downloads — trusted agents only |

Tools outside the current tier trigger an **on-device approval prompt** (30s timeout).

Change tier in Connection Hub → BYOS Bridge section.

---

## Relay

For access beyond the local network:

```bash
# Start relay on any server
lui relay start --port 9000

# Or deploy to Fly.io
cd relay && fly launch && fly deploy
```

LUI connects outbound to the relay. Agents connect to the relay targeting the device:

```
[Phone] ──outbound──> [Relay Server] <──inbound── [Remote Agent]
```

```bash
# Agent connects via relay
lui bridge connect --url ws://RELAY_HOST:9000/agent?device_token=TOKEN --token TOKEN --agent my-bot
```

Configure relay URL in LUI Connection Hub → Remote Relay section.

---

## Hermes Integration

Install the LUI skill for Hermes:

```bash
hermes skills install /path/to/hermes-skill/
```

Or connect via CLI:

```bash
lui bridge connect --url ws://PHONE_IP:8765 --token TOKEN --agent hermes --mode hermes
```

Then on the phone: "patch me to hermes" or "@hermes what can you do"

---

## Security

- **Auth**: 32-char hex token stored in Android Keystore
- **Rate limiting**: Max 3 connections, 5 auth attempts/IP, 10 req/sec
- **Permission tiers**: READ_ONLY / STANDARD / FULL
- **On-device approval**: Restricted tools prompt user with 30s timeout
- **Network**: Local Wi-Fi only (or relay for remote)
- **Log sanitization**: API keys, tokens, personal data redacted
- **No cleartext**: Network security config enforced

---

## Debug Logging

```bash
adb exec-out run-as com.lui.app.debug cat files/lui_log.txt
```

| Tag | What |
|:----|:-----|
| `INPUT` | User messages |
| `VOICE` | Voice transcription |
| `LLM` | LLM routing, responses |
| `TOOL` | Tool execution, results |
| `MCP` | Bridge protocol |
| `AGENT` | Passthrough, instructions |
| `EVENTS` | Event streaming |
| `BOUNCER` | Notification triage |
| `A11Y` | Screen reading |
| `TTS` | Text-to-speech |

---

## Tool Categories (72 total)

| Category | Count | Examples |
|:---------|:------|:--------|
| Hardware | 12 | flashlight, volume, brightness, DND, lock, screenshot |
| Alarms | 4 | set/dismiss alarm, set/cancel timer |
| Communication | 5 | call, SMS, contacts, read SMS |
| Calendar | 2 | create/read events |
| Media | 5 | play/pause, now playing, query media |
| Apps | 3 | open app, deep link search (25 apps), open LUI |
| Navigation | 4 | navigate, map search, location, distance |
| Device Info | 7 | time, date, battery, Wi-Fi, storage, device info |
| Sensors | 3 | steps, proximity, ambient light |
| Notifications | 6 | read, clear, digest, 2FA, triage config |
| Screen Control | 6 | read screen, tap, type, scroll, back, home |
| Clipboard | 3 | copy, read, share |
| Audio | 1 | route to speaker/bluetooth/earpiece |
| Settings | 5 | settings, Wi-Fi/BT, wallpaper, bedtime mode |
| Bridge | 5 | start/stop, status, list agents, instruct agent |
| Agent | 2 | start/end passthrough |
| Meta | 2 | undo, triage config |

See [INTERCEPTOR.md](INTERCEPTOR.md) for the full tool reference with parameters and example phrases.
