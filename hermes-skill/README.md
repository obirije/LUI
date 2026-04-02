# LUI Bridge — Hermes Skill

Connect Hermes to your LUI-powered Android phone. 72 device tools, real-time events, bidirectional communication.

## Install

```bash
hermes skills install /path/to/lui-bridge/
```

Or copy the `hermes-skill/` directory to `~/.hermes/skills/lui-bridge/`.

## Configure

Set your phone's bridge URL and token:

```bash
hermes skills config lui-bridge
```

Or edit `~/.hermes/skills/lui-bridge/config.yaml`:

```yaml
url: ws://192.168.1.91:8765
token: your_bridge_token_here
```

## Usage

### From Hermes Chat

```
You: check my phone battery
Hermes: [calls LUI battery tool] Your battery is at 73%, not charging.

You: read my notifications
Hermes: [calls LUI read_notifications tool] You have 3 notifications...

You: text Mom I'm on my way
Hermes: [calls LUI send_sms tool] SMS sent to Mom.
```

### From LUI (phone side)

```
You: patch me to hermes
LUI: Connected to hermes. Everything you say goes to hermes now.

You: check git status
hermes: [runs git status] On branch main, 3 files changed...

You: LUI come back
LUI: Back with you. hermes is still connected.

You: @hermes deploy to staging
hermes: Deploying... done. All tests passed.
```

### Python API

```python
from lui_connector import LuiBridge

bridge = LuiBridge(
    url="ws://192.168.1.91:8765",
    token="your_token",
    agent_name="my-bot",
    on_instruction=lambda instr: f"Executed: {instr}",
    on_event=lambda t, d: print(f"Event: {t}")
)

tool_count = bridge.connect()
print(f"{tool_count} tools available")

# Call phone tools
print(bridge.call_tool("battery"))
print(bridge.call_tool("get_location"))
print(bridge.call_tool("toggle_flashlight", {"state": "on"}))

# Get device state
print(bridge.get_device_state())

# List available tools
print(bridge.list_tools())
```

## Events

The bridge streams phone events in real-time:

| Event | When |
|:------|:-----|
| `notification` | New notification received |
| `notification_2fa` | 2FA code captured |
| `call_incoming` | Incoming phone call |
| `call_missed` | Missed call |
| `battery_change` | Charging started/stopped |
| `bridge_connect` | Another agent connected |
| `bridge_disconnect` | Agent disconnected |
