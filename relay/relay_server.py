#!/usr/bin/env python3
"""
LUI Relay Server — bridges LUI devices with remote agents.

Protocol:
1. Connect to /device or /agent (no token in URL)
2. Send auth as first message: {"type":"auth","device_token":"xxx"}
3. Relay forwards messages bidirectionally after auth

Deploy:
    pip install websockets
    python relay_server.py

Environment variables:
    PORT=9000           Server port
    RELAY_SECRET=xxx    Admin secret for /status endpoint
"""

import asyncio
import json
import os
import logging
import time
from collections import defaultdict

try:
    import websockets
except ImportError:
    print("Install websockets: pip install websockets")
    exit(1)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("relay")

PORT = int(os.environ.get("PORT", 9000))
RELAY_SECRET = os.environ.get("RELAY_SECRET", "")
MAX_AGENTS_PER_DEVICE = 5
MAX_MESSAGE_SIZE = 1 * 1024 * 1024  # 1MB
AUTH_TIMEOUT = 10  # seconds to send auth after connecting

# Rate limiting
rate_limits = {}  # ip -> (count, window_start)
RATE_WINDOW = 60
RATE_MAX = 20

# State
devices = {}               # token -> device WebSocket
agents = defaultdict(set)   # token -> set of agent WebSockets


def check_rate_limit(ip):
    now = time.time()
    if ip in rate_limits:
        count, start = rate_limits[ip]
        if now - start > RATE_WINDOW:
            rate_limits[ip] = (1, now)
            return False
        if count >= RATE_MAX:
            return True
        rate_limits[ip] = (count + 1, start)
    else:
        rate_limits[ip] = (1, now)
    return False


async def wait_for_auth(ws, timeout=AUTH_TIMEOUT):
    """Wait for auth message. Returns device_token or None."""
    try:
        msg = await asyncio.wait_for(ws.recv(), timeout=timeout)
        data = json.loads(msg)
        # Support both formats:
        # {"type":"auth","device_token":"xxx"}
        # {"method":"auth","params":{"token":"xxx"}}
        token = (
            data.get("device_token") or
            data.get("token") or
            (data.get("params", {}) or {}).get("token") or
            (data.get("params", {}) or {}).get("device_token")
        )
        if token and len(token) >= 16:
            return token
        return None
    except (asyncio.TimeoutError, json.JSONDecodeError, Exception):
        return None


async def handle_device(ws):
    ip = ws.remote_address[0] if ws.remote_address else "unknown"
    if check_rate_limit(ip):
        await ws.close(4003, "Rate limited")
        return

    # Auth: first message must contain device_token
    token = await wait_for_auth(ws)
    if not token:
        await ws.close(4001, "Auth failed: send {\"device_token\":\"xxx\"} within 10 seconds")
        return

    if token in devices:
        await ws.close(4002, "Device already connected")
        return

    devices[token] = ws
    # Send auth success back to device
    await ws.send(json.dumps({"type": "auth", "status": "ok"}))
    log.info(f"Device authenticated: {token[:8]}... ({ip})")

    # Notify waiting agents
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
        for agent_ws in list(agents[token]):
            try:
                await agent_ws.send(json.dumps({
                    "jsonrpc": "2.0",
                    "method": "notifications/relay/device_offline",
                    "params": {"status": "offline"}
                }))
            except:
                pass


async def handle_agent(ws):
    ip = ws.remote_address[0] if ws.remote_address else "unknown"
    if check_rate_limit(ip):
        await ws.close(4003, "Rate limited")
        return

    # Auth: first message must contain device_token
    token = await wait_for_auth(ws)
    if not token:
        await ws.close(4001, "Auth failed: send {\"device_token\":\"xxx\"} within 10 seconds")
        return

    if len(agents[token]) >= MAX_AGENTS_PER_DEVICE:
        await ws.close(4004, "Max agents per device reached")
        return

    agents[token].add(ws)
    device_ws = devices.get(token)

    if device_ws:
        await ws.send(json.dumps({"type": "auth", "status": "ok", "device": "online"}))
        log.info(f"Agent authenticated for device {token[:8]}... ({ip})")
    else:
        await ws.send(json.dumps({"type": "auth", "status": "ok", "device": "offline"}))
        log.info(f"Agent authenticated but device {token[:8]}... offline ({ip})")

    try:
        async for message in ws:
            device_ws = devices.get(token)
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
        agents[token].discard(ws)
        log.info(f"Agent disconnected from device {token[:8]}...")


async def handle_status(ws):
    path = ws.request.path if hasattr(ws, 'request') else ""
    params = dict(p.split("=", 1) for p in path.split("?", 1)[-1].split("&") if "=" in p)
    secret = params.get("secret", "")

    if not RELAY_SECRET or secret != RELAY_SECRET:
        await ws.send(json.dumps({"error": "Unauthorized"}))
        await ws.close(4001, "Unauthorized")
        return

    await ws.send(json.dumps({
        "devices": len(devices),
        "agents": sum(len(a) for a in agents.values()),
    }))
    await ws.close()


async def router(ws):
    path = ws.request.path if hasattr(ws, 'request') else (ws.path if hasattr(ws, 'path') else "")

    if path.startswith("/device"):
        await handle_device(ws)
    elif path.startswith("/agent"):
        await handle_agent(ws)
    elif path.startswith("/status"):
        await handle_status(ws)
    else:
        await ws.close(4000, "Unknown path. Use /device or /agent")


async def main():
    log.info(f"LUI Relay Server starting on port {PORT}")

    async with websockets.serve(
        router, "0.0.0.0", PORT,
        process_request=None,
        ping_interval=30,
        ping_timeout=10,
        max_size=MAX_MESSAGE_SIZE,
        close_timeout=10,
    ):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
