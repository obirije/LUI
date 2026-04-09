#!/usr/bin/env python3
"""
LUI Relay Server — bridges LUI devices with remote agents.

LUI connects outbound:  ws://relay:9000/device?token=DEVICE_TOKEN
Agents connect inbound:  ws://relay:9000/agent?device_token=DEVICE_TOKEN

The relay forwards messages bidirectionally between the device and its agents.
No messages are stored — pure pass-through proxy.

Deploy:
    pip install websockets
    python relay_server.py

Or with Docker:
    docker build -t lui-relay .
    docker run -p 9000:9000 lui-relay

Environment variables:
    PORT=9000           Server port
    RELAY_SECRET=xxx    Optional master secret for admin
"""

import asyncio
import json
import os
import logging
from collections import defaultdict

try:
    import websockets
except ImportError:
    print("Install websockets: pip install websockets")
    exit(1)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("relay")

PORT = int(os.environ.get("PORT", 9000))

# device_token -> device WebSocket
devices = {}
# device_token -> set of agent WebSockets
agents = defaultdict(set)


async def handle_device(ws, path):
    """Handle a LUI device connection."""
    params = dict(p.split("=", 1) for p in path.split("?", 1)[-1].split("&") if "=" in p)
    token = params.get("device_token") or params.get("token")

    if not token:
        await ws.close(4001, "Missing device_token")
        return

    if token in devices:
        await ws.close(4002, "Device already connected with this token")
        return

    devices[token] = ws
    log.info(f"Device connected: {token[:8]}... ({ws.remote_address})")

    # Notify connected agents
    for agent_ws in agents[token]:
        try:
            await agent_ws.send(json.dumps({
                "jsonrpc": "2.0",
                "method": "notifications/relay/device_online",
                "params": {"status": "online"}
            }))
        except:
            pass

    try:
        async for message in ws:
            # Forward device messages to all connected agents
            disconnected = set()
            for agent_ws in list(agents[token]):
                try:
                    await agent_ws.send(message)
                except:
                    disconnected.add(agent_ws)
            agents[token] -= disconnected
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        devices.pop(token, None)
        log.info(f"Device disconnected: {token[:8]}...")
        # Notify agents
        for agent_ws in list(agents[token]):
            try:
                await agent_ws.send(json.dumps({
                    "jsonrpc": "2.0",
                    "method": "notifications/relay/device_offline",
                    "params": {"status": "offline"}
                }))
            except:
                pass


async def handle_agent(ws, path):
    """Handle a remote agent connection."""
    params = dict(p.split("=", 1) for p in path.split("?", 1)[-1].split("&") if "=" in p)
    device_token = params.get("device_token")

    if not device_token:
        await ws.close(4001, "Missing device_token")
        return

    agents[device_token].add(ws)
    device_ws = devices.get(device_token)

    if device_ws:
        log.info(f"Agent connected to device {device_token[:8]}... ({ws.remote_address})")
    else:
        log.info(f"Agent connected but device {device_token[:8]}... is offline ({ws.remote_address})")
        await ws.send(json.dumps({
            "jsonrpc": "2.0",
            "method": "notifications/relay/device_offline",
            "params": {"status": "offline", "message": "Device is not connected. It will receive your messages when it reconnects."}
        }))

    try:
        async for message in ws:
            # Forward agent messages to the device
            device_ws = devices.get(device_token)
            if device_ws:
                try:
                    await device_ws.send(message)
                except:
                    pass
            else:
                await ws.send(json.dumps({
                    "jsonrpc": "2.0",
                    "error": {"code": -32000, "message": "Device is offline"}
                }))
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        agents[device_token].discard(ws)
        log.info(f"Agent disconnected from device {device_token[:8]}...")


async def handle_status(ws, path):
    """Admin status endpoint."""
    status = {
        "devices": len(devices),
        "agents": sum(len(a) for a in agents.values()),
        "device_tokens": [t[:8] + "..." for t in devices.keys()]
    }
    await ws.send(json.dumps(status))
    await ws.close()


async def router(ws):
    """Route connections based on URL path."""
    path = ws.request.path if hasattr(ws, 'request') else (ws.path if hasattr(ws, 'path') else "")

    if path.startswith("/device"):
        await handle_device(ws, path)
    elif path.startswith("/agent"):
        await handle_agent(ws, path)
    elif path.startswith("/status"):
        await handle_status(ws, path)
    else:
        await ws.close(4000, f"Unknown path: {path}. Use /device or /agent")


async def main():
    log.info(f"LUI Relay Server starting on port {PORT}")
    log.info(f"Device URL:  ws://HOST:{PORT}/device?device_token=YOUR_TOKEN")
    log.info(f"Agent URL:   ws://HOST:{PORT}/agent?device_token=DEVICE_TOKEN")

    # process_request=None skips strict HTTP header validation
    # Required for Fly.io/Cloudflare proxies that send Connection: keep-alive
    async with websockets.serve(
        router, "0.0.0.0", PORT,
        process_request=None,
        ping_interval=30,
        ping_timeout=10,
    ):
        await asyncio.Future()  # run forever


if __name__ == "__main__":
    asyncio.run(main())
