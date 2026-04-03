---
name: lui-bridge
description: Connect to a LUI Android device over the local network. Control the phone, read notifications, send messages, navigate, and more — 72 tools available.
version: 1.0.0
metadata:
  hermes:
    tags: [android, phone, mobile, device, mcp, bridge, lui]
    related_skills: []
---

# LUI Bridge: Android Device Control

## Overview

This skill connects Hermes to a LUI-powered Android phone over the local network. Once connected, you gain access to 72 device control tools — hardware controls, communication, navigation, sensors, screen interaction, notifications, and more.

LUI is an Android agent runtime that exposes the phone's capabilities over an MCP-compatible WebSocket bridge.

## Prerequisites

- A phone running LUI with the BYOS bridge enabled
- Phone and this machine on the same Wi-Fi network
- The bridge URL and auth token (shown in LUI's notification when bridge is active)
- Python `websocket-client` package: `pip install websocket-client`

## Setup

1. On the phone: Open LUI → Connection Hub → Enable BYOS Bridge
2. Note the URL (e.g., `ws://PHONE_IP:8765`) and token from the notification
3. Install this skill: `hermes skills install /path/to/lui-bridge/`
4. Configure: `hermes skills config lui-bridge` → enter URL and token

## What You Can Do

Once connected, tell Hermes things like:

**Device Control:**
- "Turn on the flashlight"
- "Set brightness to 50%"
- "Lock my phone"
- "Take a screenshot"
- "Enable bedtime mode"

**Communication:**
- "Send a text to Mom saying I'm on my way"
- "Read my recent text messages"
- "Call John"
- "Search contacts for Sarah"

**Information:**
- "What's my battery level?"
- "Check my notifications"
- "What's on my calendar today?"
- "How far is the airport?"
- "What wifi am I connected to?"

**Navigation:**
- "Navigate to the office"
- "Find coffee shops nearby"

**Apps:**
- "Open Spotify"
- "Play Despacito on Spotify"
- "Search Netflix for Injustice"

**Screen Control:**
- "What's on the phone screen?"
- "Tap the Play button"
- "Scroll down"

**Sensors:**
- "How many steps today?"
- "Is the phone face down?"
- "How bright is it in the room?"

## Connection

The skill connects via WebSocket to the LUI bridge and authenticates with MCP protocol. All tool calls are forwarded to the phone and results returned to Hermes.

The phone pushes events in real-time:
- New notifications
- Incoming/missed calls
- 2FA verification codes
- Battery changes

## Security

- Auth token required for every connection
- Permission tiers control which tools are accessible (Read Only / Standard / Full)
- Destructive actions (SMS, calls) may require on-device approval
- All communication is over the local network (or via relay for remote access)
