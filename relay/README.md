# LUI Relay Server

A lightweight WebSocket proxy that lets remote agents reach your LUI device from anywhere.

## How It Works

```
[Your Phone]                    [Relay Server]                  [Remote Agent]
     |                               |                              |
     |── wss://relay.lui.app/device ─>|                              |
     |                               |<── wss://relay.lui.app/agent ─|
     |                               |                              |
     |<──── messages forwarded ──────>|<──── messages forwarded ────>|
```

LUI connects **outbound** to the relay (no inbound port needed on the phone). Agents connect to the relay and specify which device to reach. Messages forwarded bidirectionally. No storage, pure pass-through.

## Default Relay

LUI ships with a default relay at `wss://relay.lui.app`. Just enable it in Connection Hub — no setup needed.

Users can override with their own relay URL for privacy or performance.

## Self-Hosting

### Quick Start

```bash
pip install websockets
python relay_server.py
```

Server starts on port 9000 (configurable via `PORT` env var).

### Deploy to Fly.io (recommended)

```bash
cd relay
fly launch --name my-lui-relay --region lhr
fly deploy
```

Your relay is now at `wss://my-lui-relay.fly.dev`. Enter this URL in LUI Connection Hub.

### Deploy with Docker

```bash
docker build -t lui-relay .
docker run -p 9000:9000 lui-relay
```

### Deploy to Railway

1. Fork the repo
2. Create a new Railway project
3. Point it at the `relay/` directory
4. Set `PORT=9000`
5. Deploy

### Deploy to any VPS

```bash
# On your server
pip install websockets
nohup python relay_server.py &

# Put behind nginx for TLS:
# proxy_pass http://127.0.0.1:9000;
# with SSL cert from Let's Encrypt
```

## URLs

| Who | URL | Purpose |
|:----|:----|:--------|
| LUI device | `wss://HOST/device?device_token=TOKEN` | Device connects out |
| Remote agent | `wss://HOST/agent?device_token=TOKEN` | Agent connects to device |
| Admin | `wss://HOST/status` | Check connected devices/agents |

The `device_token` is the bridge auth token from LUI's notification. It identifies the device and authenticates the agent.

## Setup on LUI

1. Open LUI → Connection Hub → BYOS Bridge
2. Enable the bridge
3. Under "Remote Relay" — the default `wss://relay.lui.app` is pre-filled
4. Toggle "Enable relay"
5. Restart bridge (toggle off then on)

LUI connects outbound and stays connected (auto-reconnects on disconnect).

## Agent Connection (Remote)

From anywhere in the world:

```python
import websocket, json

# Connect via relay
ws = websocket.create_connection(
    "wss://relay.lui.app/agent?device_token=YOUR_LUI_TOKEN",
    timeout=30
)

# Auth (same as local bridge)
ws.send(json.dumps({"method":"auth","params":{"token":"YOUR_LUI_TOKEN"}}))
print(ws.recv())

# Initialize MCP
ws.send(json.dumps({"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}))
print(ws.recv())

# Call any tool — this executes on the phone
ws.send(json.dumps({
    "jsonrpc":"2.0","id":2,
    "method":"tools/call",
    "params":{"name":"battery","arguments":{}}
}))
print(ws.recv())  # Battery is at 85%, not charging.

# Listen for events (notifications, calls, 2FA)
while True:
    msg = json.loads(ws.recv())
    if msg.get("method") == "notifications/lui/event":
        event = msg["params"]
        print(f"[{event['type']}] {event['data']}")
```

## Architecture

The relay is stateless:
- No messages stored
- No database
- No authentication logic (auth happens between agent and LUI directly)
- Pure WebSocket forwarding
- Agents notified when device comes online/offline

Resource usage is minimal — a $5 VPS or free Fly.io instance handles thousands of concurrent devices.

## Security

- The relay never sees decrypted content — it's a transport layer
- Authentication is end-to-end between agent and LUI
- Use `wss://` (TLS) in production
- The `device_token` acts as a routing key — keep it secret
- For additional security, self-host your own relay
