# LUI Relay Server

A lightweight WebSocket proxy that lets remote agents reach your LUI device from anywhere — not just the local network.

## How It Works

```
[Your Phone]                    [Relay Server]                  [Remote Agent]
     |                               |                              |
     |── ws://relay/device?token=X ──>|                              |
     |                               |<── ws://relay/agent?token=X ──|
     |                               |                              |
     |<──── messages forwarded ──────>|<──── messages forwarded ────>|
```

LUI connects **outbound** to the relay (no inbound port needed on the phone). Agents connect to the relay and specify which device they want to reach. The relay forwards messages bidirectionally.

## Quick Start

```bash
pip install websockets
python relay_server.py
```

Server starts on port 9000 (configurable via `PORT` env var).

## URLs

| Who | URL | Purpose |
|:----|:----|:--------|
| LUI device | `ws://HOST:9000/device?device_token=TOKEN` | Device connects out |
| Remote agent | `ws://HOST:9000/agent?device_token=TOKEN` | Agent connects to device |
| Admin | `ws://HOST:9000/status` | Check connected devices/agents |

The `device_token` is the same token shown in LUI's bridge notification. It's how the relay knows which device an agent wants to reach.

## Setup on LUI

1. Deploy relay to a VPS, Fly.io, Railway, or any server with a public IP
2. Open LUI → Connection Hub → BYOS Bridge section
3. Enable the bridge
4. Under "Remote Relay", enter your relay URL: `ws://your-server:9000/device`
5. Toggle "Enable relay"
6. Restart the bridge

LUI will connect outbound to the relay and stay connected (auto-reconnects on disconnect).

## Agent Connection

From anywhere in the world:

```python
import websocket, json

# Connect to the relay, targeting your device
ws = websocket.create_connection("ws://your-server:9000/agent?device_token=YOUR_LUI_TOKEN")

# Auth with LUI (same as local bridge)
ws.send(json.dumps({"method":"auth","params":{"token":"YOUR_LUI_TOKEN"}}))
print(ws.recv())

# Initialize MCP
ws.send(json.dumps({"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}))
print(ws.recv())

# Call any tool
ws.send(json.dumps({"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"battery","arguments":{}}}))
print(ws.recv())
```

## Deploy with Docker

```dockerfile
FROM python:3.12-slim
WORKDIR /app
RUN pip install websockets
COPY relay_server.py .
CMD ["python", "relay_server.py"]
EXPOSE 9000
```

```bash
docker build -t lui-relay .
docker run -p 9000:9000 lui-relay
```

## Security Notes

- The relay is a pure pass-through — it doesn't store or inspect messages
- Authentication happens between the agent and LUI directly (the relay just forwards)
- Use `wss://` (TLS) in production — put the relay behind nginx or Caddy with SSL
- The `device_token` acts as a session key — anyone with it can reach your device via the relay
