# LUI Developer Documentation

## BYOS Bridge — MCP Protocol Reference

LUI runs a WebSocket server that speaks the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/). Any MCP-compatible client — Claude Code, Cursor, custom agents, Python scripts — can connect and control the phone.

### Quick Start

1. Open LUI → say **"start bridge"** or toggle it on in Connection Hub
2. Note the URL and token from the notification (e.g., `ws://192.168.1.91:8765`)
3. Connect from any device on the same Wi-Fi

### Python Example

```python
import websocket
import json

# Connect
ws = websocket.create_connection("ws://192.168.1.91:8765", timeout=30)

# 1. Authenticate
ws.send(json.dumps({
    "method": "auth",
    "params": {"token": "YOUR_TOKEN"}
}))
print(ws.recv())  # {"jsonrpc":"2.0","result":{"authenticated":true,...}}

# 2. Initialize MCP session
ws.send(json.dumps({
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
        "protocolVersion": "2025-03-26",
        "clientInfo": {"name": "my-agent", "version": "1.0"}
    }
}))
init = json.loads(ws.recv())
print(f"Connected: {init['result']['serverInfo']['name']}")
print(f"Tools: {init['result']['instructions']}")

# 3. Send initialized notification
ws.send(json.dumps({
    "jsonrpc": "2.0",
    "method": "notifications/initialized"
}))

# 4. List available tools
ws.send(json.dumps({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}))
tools = json.loads(ws.recv())["result"]["tools"]
for t in tools:
    print(f"  {t['name']}: {t['description']}")

# 5. Call a tool
ws.send(json.dumps({
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
        "name": "get_location",
        "arguments": {}
    }
}))
result = json.loads(ws.recv())
print(result["result"]["content"][0]["text"])

ws.close()
```

### wscat Example

```bash
npm install -g wscat
wscat -c ws://192.168.1.91:8765

# Authenticate
> {"method":"auth","params":{"token":"YOUR_TOKEN"}}

# Initialize
> {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}

# List tools
> {"jsonrpc":"2.0","id":2,"method":"tools/list"}

# Check battery
> {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"battery","arguments":{}}}

# Turn on flashlight
> {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"toggle_flashlight","arguments":{"state":"on"}}}

# Get notifications
> {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"read_notifications","arguments":{}}}

# Read device state
> {"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"lui://device/state"}}
```

### Node.js Example

```javascript
const WebSocket = require('ws');
const ws = new WebSocket('ws://192.168.1.91:8765');

function send(msg) {
    ws.send(JSON.stringify(msg));
}

ws.on('open', () => {
    // Auth
    send({ method: 'auth', params: { token: 'YOUR_TOKEN' } });
});

ws.on('message', (data) => {
    const msg = JSON.parse(data);
    console.log(JSON.stringify(msg, null, 2));

    // After auth, initialize
    if (msg.result?.authenticated) {
        send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} });
    }
    // After init, call a tool
    if (msg.id === 1 && msg.result?.protocolVersion) {
        send({
            jsonrpc: '2.0', id: 2,
            method: 'tools/call',
            params: { name: 'wifi_info', arguments: {} }
        });
    }
});
```

---

## Protocol Reference

### Authentication

First message must be auth. Supports header-based or message-based auth.

**Message auth:**
```json
→ {"method":"auth","params":{"token":"your_32char_hex_token"}}
← {"jsonrpc":"2.0","id":"auth","result":{"authenticated":true,"message":"..."}}
```

**Header auth** (on connect):
```
Authorization: Bearer your_32char_hex_token
```

### MCP Lifecycle

```json
→ {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","clientInfo":{"name":"agent","version":"1.0"}}}
← {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{...},"serverInfo":{"name":"lui-android","version":"0.1.0"},"instructions":"..."}}

→ {"jsonrpc":"2.0","method":"notifications/initialized"}
```

### tools/list

```json
→ {"jsonrpc":"2.0","id":2,"method":"tools/list"}
← {"jsonrpc":"2.0","id":2,"result":{"tools":[
    {"name":"toggle_flashlight","description":"...","inputSchema":{"type":"object","properties":{"state":{"type":"string","enum":["on","off","toggle"]}}}}
    ...
  ]}}
```

### tools/call

```json
→ {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"TOOL_NAME","arguments":{...}}}
← {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"Result message"}],"isError":false}}
```

Error response:
```json
← {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"Error message"}],"isError":true}}
```

### resources/list

```json
→ {"jsonrpc":"2.0","id":4,"method":"resources/list"}
← {"jsonrpc":"2.0","id":4,"result":{"resources":[
    {"uri":"lui://device/state","name":"Device State","mimeType":"text/plain"},
    {"uri":"lui://device/tools","name":"Tool Summary","mimeType":"text/plain"}
  ]}}
```

### resources/read

```json
→ {"jsonrpc":"2.0","id":5,"method":"resources/read","params":{"uri":"lui://device/state"}}
← {"jsonrpc":"2.0","id":5,"result":{"contents":[{"uri":"lui://device/state","mimeType":"text/plain","text":"Current time: 11:26 PM\nBattery: 100%\n..."}]}}
```

### ping

```json
→ {"jsonrpc":"2.0","id":6,"method":"ping"}
← {"jsonrpc":"2.0","id":6,"result":{}}
```

---

## Permission Tiers

The bridge has three configurable access levels. Change in Connection Hub or via `SecureKeyStore.bridgePermissionTier`.

### READ_ONLY (16 tools)

Device state queries only. No side effects.

| Tool | Description |
|:-----|:------------|
| `get_time` | Current time |
| `get_date` | Current date and day |
| `device_info` | Time, date, battery, network, device model |
| `battery` | Battery level and charging status |
| `wifi_info` | Wi-Fi SSID, signal strength, speed |
| `storage_info` | RAM and storage usage |
| `get_location` | GPS location with address |
| `get_distance` | Distance + drive time estimate to a destination |
| `get_steps` | Step counter |
| `get_proximity` | Is phone face-down or in pocket |
| `get_light` | Ambient light level in lux |
| `now_playing` | Current song/artist from media player |
| `read_clipboard` | Clipboard contents |
| `screen_time` | App usage stats for today |
| `bridge_status` | Bridge connection info |
| `read_screen` | Read current app screen content |

### STANDARD (44 tools) — Default

Everything in READ_ONLY plus device controls, navigation, apps, and reading personal data. Restricted tools (outside this tier) trigger an on-device approval prompt.

**Added over READ_ONLY:**

| Category | Tools |
|:---------|:------|
| Device controls | `toggle_flashlight`, `set_volume`, `set_brightness`, `toggle_dnd`, `toggle_rotation`, `set_ringer`, `set_screen_timeout`, `keep_screen_on` |
| Media | `play_pause`, `next_track`, `previous_track`, `route_audio` |
| Navigation | `navigate`, `search_map` |
| Apps | `open_app`, `open_app_search`, `open_settings`, `open_settings_wifi`, `open_settings_bluetooth`, `open_lui` |
| Read personal data | `read_notifications`, `read_calendar`, `read_sms`, `search_contact`, `get_digest`, `get_2fa_code`, `query_media` |
| Meta | `undo` |

### FULL (70 tools)

Everything. Use only with fully trusted agents.

**Added over STANDARD:**

| Category | Tools |
|:---------|:------|
| Communication | `send_sms`, `make_call`, `create_contact`, `create_event` |
| Notification management | `clear_notifications`, `clear_digest`, `config_triage` |
| Screen control | `find_and_tap`, `type_text`, `scroll_down`, `press_back`, `press_home`, `take_screenshot`, `lock_screen`, `split_screen` |
| System | `download_file`, `set_wallpaper`, `bedtime_mode`, `start_bridge`, `stop_bridge` |

### On-Device Approval

When an agent calls a tool outside its current tier, a dialog appears on the phone:

```
┌─────────────────────────────┐
│  Remote Agent Request       │
│                             │
│  A remote agent wants to:   │
│  Send SMS to Mom:           │
│  "Hey, just testing!"       │
│                             │
│  [Allow]        [Deny]      │
└─────────────────────────────┘
```

- **Allow** → tool executes, result sent to agent
- **Deny** → error sent to agent
- **30s timeout** → treated as denied

---

## Tool Call Examples

### Device Control

```json
// Flashlight
{"name":"toggle_flashlight","arguments":{"state":"on"}}

// Volume
{"name":"set_volume","arguments":{"direction":"up"}}    // up, down, mute, max

// Brightness
{"name":"set_brightness","arguments":{"level":"50"}}     // 0-100, up, down, max, low

// DND
{"name":"toggle_dnd","arguments":{}}

// Lock phone
{"name":"lock_screen","arguments":{}}

// Screenshot
{"name":"take_screenshot","arguments":{}}

// Bedtime mode (DND + dim + short timeout)
{"name":"bedtime_mode","arguments":{"enable":"true"}}

// Screen timeout
{"name":"set_screen_timeout","arguments":{"duration":"5m"}} // 15s, 30s, 1m, 5m, 30m, never
```

### Communication

```json
// Call (resolves contact names)
{"name":"make_call","arguments":{"target":"Mom"}}

// SMS (resolves contact names)
{"name":"send_sms","arguments":{"number":"Mom","message":"On my way"}}

// Search contacts
{"name":"search_contact","arguments":{"query":"John"}}

// Read SMS
{"name":"read_sms","arguments":{"from":"Mom"}}   // "from" is optional
```

### Navigation & Location

```json
// Navigate (opens Google Maps)
{"name":"navigate","arguments":{"destination":"Liverpool Airport"}}

// Distance + drive time from current location
{"name":"get_distance","arguments":{"destination":"Liverpool Airport"}}

// Current location
{"name":"get_location","arguments":{}}

// Search on map
{"name":"search_map","arguments":{"query":"coffee shops nearby"}}
```

### Apps & Deep Links

```json
// Open app
{"name":"open_app","arguments":{"name":"Spotify"}}

// Search inside app (25 supported apps)
{"name":"open_app_search","arguments":{"app":"Spotify","query":"Despacito"}}
{"name":"open_app_search","arguments":{"app":"Netflix","query":"Injustice"}}
{"name":"open_app_search","arguments":{"app":"YouTube","query":"Kotlin tutorials"}}
{"name":"open_app_search","arguments":{"app":"Chrome","query":"MCP protocol"}}
{"name":"open_app_search","arguments":{"app":"Amazon","query":"USB-C cable"}}
```

### Calendar

```json
// Read today's events
{"name":"read_calendar","arguments":{"date":"today"}}

// Read tomorrow
{"name":"read_calendar","arguments":{"date":"tomorrow"}}

// Create event
{"name":"create_event","arguments":{"title":"Team standup","date":"tomorrow","time":"9am"}}
```

### Screen Control (Accessibility)

```json
// Read what's on screen
{"name":"read_screen","arguments":{}}

// Find and tap a button
{"name":"find_and_tap","arguments":{"query":"Play"}}

// Type into focused field
{"name":"type_text","arguments":{"text":"Hello world"}}

// Scroll, back, home
{"name":"scroll_down","arguments":{}}
{"name":"press_back","arguments":{}}
{"name":"press_home","arguments":{}}
```

### Notifications (The Bouncer)

```json
// Read active notifications
{"name":"read_notifications","arguments":{}}

// Get Evening Digest (batched noise)
{"name":"get_digest","arguments":{}}

// Get latest 2FA code
{"name":"get_2fa_code","arguments":{}}

// Clear all notifications
{"name":"clear_notifications","arguments":{}}
```

### Sensors

```json
// Step counter
{"name":"get_steps","arguments":{}}

// Is phone face-down?
{"name":"get_proximity","arguments":{}}

// Ambient light
{"name":"get_light","arguments":{}}
```

### Storage & Downloads

```json
// Storage and RAM info
{"name":"storage_info","arguments":{}}

// Download a file
{"name":"download_file","arguments":{"url":"https://example.com/file.pdf","filename":"report.pdf"}}

// Query photos from today
{"name":"query_media","arguments":{"type":"photos","date":"today"}}

// Audio routing
{"name":"route_audio","arguments":{"target":"speaker"}}  // speaker, bluetooth, earpiece
```

---

## Error Codes

| Code | Meaning |
|:-----|:--------|
| `-32700` | Parse error — invalid JSON |
| `-32601` | Method not found |
| `-32602` | Invalid params |
| `-32000` | Not authenticated / rate limited |
| `-32001` | Tool not allowed at current permission tier |

---

## Security

- **Auth token**: 32-character hex, generated on first start, stored in Android Keystore (encrypted)
- **Rate limiting**: Max 3 connections, 5 auth attempts per IP, 10 requests/second per client
- **Permission tiers**: READ_ONLY / STANDARD / FULL — configurable in Connection Hub
- **On-device approval**: Restricted tools prompt user on phone with 30s timeout
- **Network**: Bridge runs on local Wi-Fi only. Not exposed to internet.
- **Log sanitization**: API keys, tokens, and sensitive data redacted from all log output
- **No cleartext**: Network security config blocks HTTP except localhost

---

## Debug Logging

LUI writes comprehensive logs to `/data/data/com.lui.app.debug/files/lui_log.txt`.

```bash
# Pull logs
adb exec-out run-as com.lui.app.debug cat files/lui_log.txt

# Clear logs
adb exec-out run-as com.lui.app.debug sh -c "echo '' > files/lui_log.txt"
```

Log categories:

| Tag | What it captures |
|:----|:-----------------|
| `INPUT` | Every user message (text/voice) |
| `VOICE` | Voice transcription partials and finals |
| `INTERCEPT` | Keyword matches and misses |
| `LLM` | Routing decisions, full responses |
| `GEMINI` / `CLAUDE` / `OPENAI` | Provider-specific raw responses |
| `TOOL` | Tool execution and results |
| `CHAIN` | Multi-turn tool chaining steps |
| `FORCE` | Forced tool calls (live-state queries) |
| `MCP` | Bridge protocol messages |
| `BRIDGE` | Bridge approval requests |
| `BOUNCER` | Notification triage decisions |
| `A11Y` | Accessibility screen reading |
| `TTS` | Text-to-speech provider, failures, fallback |
| `SYSTEM` | Logger init, restarts |
