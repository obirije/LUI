# lui-bridge

Connect AI agents to LUI Android devices. 78 phone tools, MCP protocol, bidirectional communication.

## Install

```bash
pip install lui-bridge
```

## CLI

```bash
# Connect as an agent (interactive — receives instructions from phone)
lui bridge connect --url ws://PHONE_IP:8765 --token TOKEN --agent my-bot --mode echo

# Connect with Hermes as the backend
lui bridge connect --url ws://PHONE_IP:8765 --token TOKEN --agent hermes --mode hermes

# Connect with Claude Code as the backend
lui bridge connect --url ws://PHONE_IP:8765 --token TOKEN --agent claude-code --mode claude-code

# List all available tools on the phone
lui bridge tools --url ws://PHONE_IP:8765 --token TOKEN

# Call a single tool
lui bridge call --url ws://PHONE_IP:8765 --token TOKEN --tool battery
lui bridge call --url ws://PHONE_IP:8765 --token TOKEN --tool get_location
lui bridge call --url ws://PHONE_IP:8765 --token TOKEN --tool toggle_flashlight --args '{"state":"on"}'

# Check device status
lui bridge status --url ws://PHONE_IP:8765 --token TOKEN

# Start a relay server (for remote access beyond LAN)
lui relay start --port 9000
```

## Python API

```python
from lui import LuiBridge

# Connect to phone
bridge = LuiBridge("ws://PHONE_IP:8765", "YOUR_TOKEN")
bridge.connect()  # returns tool count

# Call tools
print(bridge.call_tool("battery"))           # "Battery is at 73%, not charging."
print(bridge.call_tool("get_location"))       # "You're at 123 Main St..."
print(bridge.call_tool("get_time"))           # "It's 3:42 PM."
print(bridge.call_tool("read_notifications")) # "Slack: new message..."
print(bridge.call_tool("wifi_info"))          # "Connected to MyWifi (5GHz, 702 Mbps)"

# Control the phone
bridge.call_tool("toggle_flashlight", {"state": "on"})
bridge.call_tool("set_volume", {"direction": "up"})
bridge.call_tool("send_sms", {"number": "Mom", "message": "On my way"})
bridge.call_tool("navigate", {"destination": "the airport"})
bridge.call_tool("open_app_search", {"app": "Spotify", "query": "Despacito"})

# Vision — trigger camera or gallery remotely
bridge.call_tool("take_photo")                # captures photo via Camera2 API
bridge.call_tool("pick_image")                # opens gallery picker on phone
bridge.call_tool("analyze_image")             # describe what's in the last captured/selected image

# Get device state
print(bridge.get_device_state())
# Time: 3:42 PM
# Battery: 73%
# Network: Wi-Fi
# ...

# List tools
for name in bridge.list_tools():
    print(name)

bridge.disconnect()
```

## Bidirectional Agent

Register as a named agent so the phone user can send you instructions:

```python
from lui import LuiBridge

def handle_instruction(instruction):
    """Called when user says 'tell my-bot to ...' on the phone."""
    print(f"Got: {instruction}")
    # Process the instruction with your agent logic
    return "Done!"

def handle_event(event_type, data):
    """Called for phone events (notifications, calls, 2FA codes)."""
    if event_type == "notification_2fa":
        print(f"2FA code: {data['code']}")
    elif event_type == "notification":
        print(f"Notification: {data['title']}")

bridge = LuiBridge(
    url="ws://PHONE_IP:8765",
    token="YOUR_TOKEN",
    agent_name="my-bot",
    on_instruction=handle_instruction,
    on_event=handle_event
)

bridge.connect()
print("Listening...")

# On the phone, user can say:
#   "patch me to my-bot"  → enters direct chat mode
#   "@my-bot deploy"      → sends one-off instruction
#   "tell my-bot to run tests" → LLM forwards instruction

while bridge.connected:
    time.sleep(1)
```

## Relay Server

For access beyond the local network:

```bash
# Start relay
lui relay start --port 9000

# Phone connects to relay (configure in LUI Connection Hub)
# Agent connects via relay
lui bridge connect --url ws://RELAY_HOST:9000/agent?device_token=TOKEN --token TOKEN --agent my-bot
```

## Requirements

- Python 3.9+
- A phone running LUI with the BYOS bridge enabled
- Phone and agent on the same Wi-Fi (or use relay for remote)
