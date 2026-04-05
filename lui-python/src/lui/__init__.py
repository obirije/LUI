"""
LUI Bridge — Connect AI agents to Android devices.

Usage:
    from lui import LuiBridge

    bridge = LuiBridge("ws://phone:8765", "token")
    bridge.connect()
    print(bridge.call_tool("battery"))

CLI:
    lui bridge connect --url ws://phone:8765 --token XXX
    lui relay start --port 9000
"""

from lui.bridge import LuiBridge

__version__ = "0.2.0"
__all__ = ["LuiBridge"]
