"""Tests for LuiBridge connection logic."""
import pytest
from lui.bridge import LuiBridge


class TestRelayDetection:
    """Test that the bridge correctly detects relay vs direct URLs."""

    def test_direct_url_not_relay(self):
        bridge = LuiBridge("ws://192.168.1.91:8765", "token123456789012345")
        # Should not have /agent or /device in URL
        assert "/agent" not in bridge.url
        assert "/device" not in bridge.url

    def test_relay_agent_url(self):
        bridge = LuiBridge("wss://relay.luios.xyz/agent", "token123456789012345")
        assert "/agent" in bridge.url

    def test_relay_device_url(self):
        bridge = LuiBridge("wss://relay.luios.xyz/device", "token123456789012345")
        assert "/device" in bridge.url


class TestBridgeInit:
    """Test bridge initialization."""

    def test_stores_url(self):
        bridge = LuiBridge("ws://localhost:8765", "mytoken1234567890")
        assert bridge.url == "ws://localhost:8765"

    def test_stores_token(self):
        bridge = LuiBridge("ws://localhost:8765", "mytoken1234567890")
        assert bridge.token == "mytoken1234567890"

    def test_stores_agent_name(self):
        bridge = LuiBridge("ws://localhost:8765", "mytoken1234567890", agent_name="test-bot")
        assert bridge.agent_name == "test-bot"

    def test_defaults_not_connected(self):
        bridge = LuiBridge("ws://localhost:8765", "mytoken1234567890")
        assert bridge.connected is False

    def test_defaults_empty_tools(self):
        bridge = LuiBridge("ws://localhost:8765", "mytoken1234567890")
        assert bridge.tools == []
