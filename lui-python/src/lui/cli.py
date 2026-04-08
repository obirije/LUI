"""
LUI CLI — Connect agents to LUI devices and manage relay servers.

Usage:
    lui bridge connect --url ws://PHONE_IP:8765 --token TOKEN [--agent NAME] [--mode MODE]
    lui bridge tools   --url ws://PHONE_IP:8765 --token TOKEN
    lui bridge call    --url ws://PHONE_IP:8765 --token TOKEN --tool TOOL [--args JSON]
    lui bridge status  --url ws://PHONE_IP:8765 --token TOKEN
    lui relay start    [--port PORT]
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time


def cmd_bridge_connect(args):
    """Connect to a LUI device as an agent."""
    from lui.bridge import LuiBridge

    executor = _get_executor(args.mode)

    def on_instruction(instruction):
        print(f"\n[→ {args.agent}] {instruction}", flush=True)
        result = executor(instruction)
        print(f"[← {args.agent}] {result[:120]}", flush=True)
        return result

    def on_event(event_type, data):
        if event_type == "notification_2fa":
            print(f"\n[2FA] Code: {data.get('code')} from {data.get('app')}", flush=True)
        elif event_type in ("call_incoming", "call_missed"):
            print(f"\n[{event_type.upper()}] {data.get('caller', '?')}", flush=True)
        elif event_type == "notification":
            print(f"\n[NOTIF] {data.get('title', '')}: {data.get('text', '')[:50]}", flush=True)

    bridge = LuiBridge(args.url, args.token, args.agent,
                       on_instruction=on_instruction, on_event=on_event)

    tool_count = bridge.connect()
    print(f"Connected to LUI as '{args.agent}' — {tool_count} tools, mode={args.mode}")
    print(f"Device: {bridge.get_device_state()[:80]}")
    print(f"\nOn LUI say: 'patch me to {args.agent}' or '@{args.agent} do something'")
    print("Listening... (Ctrl+C to exit)\n")

    try:
        while bridge.connected:
            time.sleep(1)
    except KeyboardInterrupt:
        bridge.disconnect()
        print("\nDisconnected.")


def cmd_bridge_tools(args):
    """List available tools on a LUI device."""
    from lui.bridge import LuiBridge

    bridge = LuiBridge(args.url, args.token)
    tool_count = bridge.connect()
    tools = bridge.list_tools_detailed()
    bridge.disconnect()

    print(f"{tool_count} tools available:\n")
    for t in tools:
        params = t.get("inputSchema", {}).get("properties", {})
        param_str = ", ".join(params.keys()) if params else ""
        print(f"  {t['name']}({param_str})")
        print(f"    {t['description']}")
        print()


def cmd_bridge_call(args):
    """Call a single tool on a LUI device."""
    from lui.bridge import LuiBridge

    arguments = json.loads(args.args) if args.args else {}

    bridge = LuiBridge(args.url, args.token)
    bridge.connect()
    result = bridge.call_tool(args.tool, arguments)
    bridge.disconnect()

    print(result)


def cmd_bridge_status(args):
    """Check bridge connection and device state."""
    from lui.bridge import LuiBridge

    bridge = LuiBridge(args.url, args.token)
    try:
        tool_count = bridge.connect()
        state = bridge.get_device_state()
        bridge.disconnect()
        print(f"Connected — {tool_count} tools")
        print(f"\n{state}")
    except Exception as e:
        print(f"Failed: {e}")
        sys.exit(1)


def cmd_relay_start(args):
    """Start the LUI relay server."""
    os.environ["PORT"] = str(args.port)
    from lui.relay import main
    import asyncio
    asyncio.run(main())


def _get_executor(mode):
    """Get the instruction executor for the given mode."""
    if mode == "hermes":
        return _execute_hermes
    elif mode == "claude-code":
        return _execute_claude_code
    elif mode == "shell":
        return _execute_shell
    else:
        return lambda i: f"Received: {i}"


def _execute_hermes(instruction):
    try:
        result = subprocess.run(
            ["hermes", "chat", "-q", instruction, "--yolo"],
            capture_output=True, text=True, timeout=120,
            env={**os.environ, "TERM": "dumb", "NO_COLOR": "1"}
        )
        output = result.stdout.strip() or result.stderr.strip()
        if not output:
            return "Hermes returned no output."
        output = re.sub(r'\x1b\[[0-9;]*[a-zA-Z]', '', output)
        output = re.sub(r'\x1b\].*?\x07', '', output)
        output = re.sub(r'[╭╮╰╯│─┃━┏┓┗┛┡┩┣┫┠┨┯┷┿╋╂╇╈╅╆╌╍⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]', '', output)
        lines = [l.strip() for l in output.split('\n')
                 if l.strip() and not any(p in l for p in ['Hermes Agent', '──────', 'Session:', 'Model:', '> ', '>>>', '/exit', 'Goodbye'])]
        return '\n'.join(lines[-15:])[:500] if lines else "Hermes returned no meaningful output."
    except subprocess.TimeoutExpired:
        return "Hermes timed out."
    except FileNotFoundError:
        return "Hermes CLI not found. Install: pip install hermes-agent"
    except Exception as e:
        return f"Error: {e}"


def _execute_claude_code(instruction):
    try:
        result = subprocess.run(
            ["claude", "--print", "--dangerously-skip-permissions", instruction],
            capture_output=True, text=True, timeout=120
        )
        output = result.stdout.strip() or result.stderr.strip()
        output = re.sub(r'\x1b\[[0-9;]*m', '', output)
        return output[:1000] if output else "Claude Code returned no output."
    except subprocess.TimeoutExpired:
        return "Claude Code timed out."
    except FileNotFoundError:
        return "Claude Code not found. Install: npm install -g @anthropic-ai/claude-code"
    except Exception as e:
        return f"Error: {e}"


def _execute_shell(instruction):
    try:
        result = subprocess.run(
            ["bash", "-c", instruction],
            capture_output=True, text=True, timeout=120,
            cwd=os.path.expanduser("~")
        )
        output = result.stdout.strip()
        stderr = result.stderr.strip()
        combined = f"{output}\n{stderr}".strip() if output and stderr else (output or stderr or "Done.")
        if result.returncode != 0:
            combined += f"\n(exit code {result.returncode})"
        return combined[:500]
    except subprocess.TimeoutExpired:
        return "Timed out."
    except Exception as e:
        return f"Error: {e}"


def main():
    parser = argparse.ArgumentParser(prog="lui", description="LUI — Android agent bridge and relay")
    parser.add_argument("--version", action="version", version="lui-bridge 0.1.0")
    subparsers = parser.add_subparsers(dest="command")

    # lui bridge
    bridge_parser = subparsers.add_parser("bridge", help="Connect to a LUI device")
    bridge_sub = bridge_parser.add_subparsers(dest="bridge_command")

    # lui bridge connect
    connect_p = bridge_sub.add_parser("connect", help="Connect as an agent")
    connect_p.add_argument("--url", required=True, help="Bridge URL (ws://PHONE_IP:8765)")
    connect_p.add_argument("--token", required=True, help="Auth token")
    connect_p.add_argument("--agent", default="python-agent", help="Agent name")
    connect_p.add_argument("--mode", default="echo", choices=["hermes", "claude-code", "shell", "echo"],
                          help="Execution mode")

    # lui bridge tools
    tools_p = bridge_sub.add_parser("tools", help="List available tools")
    tools_p.add_argument("--url", required=True, help="Bridge URL")
    tools_p.add_argument("--token", required=True, help="Auth token")

    # lui bridge call
    call_p = bridge_sub.add_parser("call", help="Call a single tool")
    call_p.add_argument("--url", required=True, help="Bridge URL")
    call_p.add_argument("--token", required=True, help="Auth token")
    call_p.add_argument("--tool", required=True, help="Tool name")
    call_p.add_argument("--args", default=None, help="Tool arguments as JSON")

    # lui bridge status
    status_p = bridge_sub.add_parser("status", help="Check device status")
    status_p.add_argument("--url", required=True, help="Bridge URL")
    status_p.add_argument("--token", required=True, help="Auth token")

    # lui relay
    relay_parser = subparsers.add_parser("relay", help="Run relay server")
    relay_sub = relay_parser.add_subparsers(dest="relay_command")

    start_p = relay_sub.add_parser("start", help="Start relay server")
    start_p.add_argument("--port", type=int, default=9000, help="Port (default: 9000)")

    args = parser.parse_args()

    if args.command == "bridge":
        if args.bridge_command == "connect":
            cmd_bridge_connect(args)
        elif args.bridge_command == "tools":
            cmd_bridge_tools(args)
        elif args.bridge_command == "call":
            cmd_bridge_call(args)
        elif args.bridge_command == "status":
            cmd_bridge_status(args)
        else:
            bridge_parser.print_help()
    elif args.command == "relay":
        if args.relay_command == "start":
            cmd_relay_start(args)
        else:
            relay_parser.print_help()
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
