<h1 align="center">LUI</h1>

<p align="center">
  <strong>Language User Interface</strong> &nbsp;(pronounced "Louie")
</p>

<p align="center">
  An AI assistant that actually assists.
</p>

<!-- TODO: Add screenshots / screen recording here -->

---

Voice assistants like Siri and Google Assistant can answer questions, but they can't actually *use* your phone. AI chatbots sit in an app waiting for you to copy and paste. LUI replaces your Android home screen with a single conversational interface backed by real agency — 78 native tools, a full voice pipeline, and an on-device LLM. You say what you want, and the phone does it.

**For developers:** Your AI agents deserve better than a Telegram bot. LUI is a native mobile terminal that gives your swarms a physical body. Connect Claude Code, Hermes, or any MCP-compatible agent via WebSocket — they get first-class access to Android hardware, sensors, notifications, 2FA codes, and screen control. `pip install lui-bridge` and go.

---

## What Can It Do?

**Talk to your phone like a person:**

> "Turn the flashlight on"
> "Text mum I'm on my way"
> "What's on my calendar tomorrow?"
> "Navigate to the nearest pharmacy"
> "Play Despacito on Spotify"
> "Read my notifications"
> "Take a photo and tell me what you see"
> "Set an alarm for 7am"

**All by voice.** Say "Hey LUI" even when the phone is locked — it wakes up, greets you, and listens. Say "goodbye" when you're done.

---

## Features

- **Replaces your home screen** — no icons, no widgets, no noise. Just a clean chat interface.
- **78 native device tools** — hardware controls, calls, SMS, calendar, navigation, media, camera, notifications, screen control, sensors, files, and more.
- **Voice-first** — full streaming voice pipeline. Wake word ("Hey LUI"), conversation mode, natural TTS. Works hands-free.
- **On-device LLM** — Qwen3.5 0.8B runs locally via llama.cpp. No cloud, no API keys, fully private. Auto-downloads from the Connection Hub.
- **Cloud LLM** — connect Gemini, Claude, OpenAI, or Ollama for stronger reasoning with native function calling.
- **Vision** — camera capture and gallery picker with AI image analysis.
- **The Bouncer** — notification triage. Urgent notifications pass through, noise gets batched into an Evening Digest, 2FA codes auto-extracted.
- **Screen control** — read any app's screen, tap buttons, type text, scroll. The LLM can pilot any app on your phone.
- **Agent bridge (BYOS)** — MCP-compatible WebSocket server. Connect any AI agent with `pip install lui-bridge` or `npm install -g lui-bridge`. Event streaming, bidirectional communication, permission tiers, relay for remote access.
- **Agent passthrough** — "Patch me to Hermes" enters direct chat with a remote agent. "@claude-code deploy" sends a one-off instruction.
- **28 app deep links** — search inside apps by voice (see list below).
- **Persistent conversations** across restarts (Room/SQLite).

### Compatible Agents

LUI has been tested end-to-end with these agent frameworks:

| Agent | How it connects | Mode |
|:------|:---------------|:-----|
| **[Claude Code](https://claude.ai/claude-code)** | `lui bridge connect --mode claude-code` | Full conversation + command execution |
| **[Hermes](https://github.com/hermes-ai/hermes)** | `lui bridge connect --mode hermes` | Single-query via `-q` flag |
| **Any shell command** | `lui bridge connect --mode shell` | Raw bash execution |
| **Custom agents** | Python/Node.js API | Build your own with `pip install lui-bridge` |

Any MCP-compatible agent framework can connect. The bridge uses standard JSON-RPC 2.0 over WebSocket.

### App Deep Links

Say "play Despacito on Spotify" or "search cats on YouTube" — LUI deep-links directly into the app:

| Category | Apps |
|:---------|:-----|
| **Music** | Spotify, YouTube Music, Amazon Music, SoundCloud, Deezer, Shazam |
| **Video** | YouTube, Netflix, TikTok, Twitch |
| **Social** | X (Twitter), Reddit, Instagram, WhatsApp, Telegram |
| **Shopping** | Amazon, eBay |
| **Travel** | Google Maps, Uber, Lyft, Google Translate |
| **Browse** | Chrome, Play Store |

---

## Getting Started

### Install

1. Clone and build:

```bash
git clone --recurse-submodules https://github.com/obirije/LUI.git
cd LUI
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17, Android SDK 35, NDK 28, CMake 3.31+.

2. Open LUI on your phone. Complete the onboarding.

3. **Get an LLM running** (pick one):
   - **Easiest:** Tap the status dot → Connection Hub → select **Gemini** → paste a free API key from [aistudio.google.com](https://aistudio.google.com/apikey) → toggle Cloud-first on. Done — you get 15 free requests/minute with all 78 tools.
   - **Self-hosted:** Select **Ollama** → enter your machine's IP and model name. Any model with tool support works (Qwen 3.5, Llama 3.1, Mistral).
   - **Fully offline:** The on-device model auto-downloads (~533MB) from the Connection Hub. No API key needed.

4. Start talking. Type or tap the mic.

### Optional: Cloud Speech

For natural voice output, add a speech API key in Connection Hub:

| Provider | Free Tier |
|:---------|:----------|
| [Deepgram](https://console.deepgram.com/) | $200 credit |
| [ElevenLabs](https://elevenlabs.io/) | 10K chars/month |

Without cloud speech, LUI uses on-device TTS (Pocket TTS).

---

## Connect Your AI Agents

LUI exposes all 78 phone tools over an MCP-compatible WebSocket bridge. Any agent framework can connect.

```bash
pip install lui-bridge

# Connect as an agent
lui bridge connect --url ws://PHONE_IP:8765 --token TOKEN --agent my-bot --mode echo

# Call tools remotely
lui bridge call --url ws://PHONE_IP:8765 --token TOKEN --tool battery
lui bridge call --url ws://PHONE_IP:8765 --token TOKEN --tool get_location
lui bridge call --url ws://PHONE_IP:8765 --token TOKEN --tool send_sms --args '{"number":"Mum","message":"On my way"}'
```

```python
from lui import LuiBridge

bridge = LuiBridge("ws://PHONE_IP:8765", "YOUR_TOKEN")
bridge.connect()

bridge.call_tool("battery")
bridge.call_tool("toggle_flashlight", {"state": "on"})
bridge.call_tool("take_photo")
bridge.call_tool("navigate", {"destination": "the airport"})
```

Enable the bridge in Connection Hub. Three permission tiers (Read-Only / Standard / Full) with on-device approval for restricted tools. Relay server for remote access beyond LAN. See [DOCS.md](DOCS.md) for the full MCP protocol reference.

Also available: `npm install -g lui-bridge` for Node.js.

---

## Tools (78)

| Category | Count | Examples |
|:---------|:------|:--------|
| Hardware | 10 | Flashlight, volume, brightness, DND, rotation, ringer, lock, screenshot, split screen |
| Alarms & Timers | 4 | Set/dismiss alarm, set/cancel timer |
| Communication | 5 | Call, SMS, search/create contacts, read SMS |
| Calendar | 2 | Create/read events |
| Media | 5 | Play/pause, next/prev track, now playing |
| Apps & Navigation | 7 | Open app, deep link search (25 apps), navigate, search map, get location, distance |
| Device & Sensors | 8 | Time, date, battery, device info, Wi-Fi, step counter, proximity, light |
| Notifications | 5 | Read, clear, digest, 2FA code, triage config |
| Screen Control | 6 | Read screen, find & tap, type text, scroll, back, home |
| Storage & Files | 4 | Storage info, download file, query media, audio routing |
| Clipboard & System | 8 | Copy, read, share, settings, Wi-Fi/BT settings, wallpaper, bedtime mode |
| Vision | 3 | Take photo, pick image from gallery, analyze image |
| Bridge | 7 | Start/stop bridge, status, list agents, instruct agent, passthrough start/end |
| Meta | 4 | Undo, open LUI, keep screen on, screen timeout |

Full tool reference: [INTERCEPTOR.md](INTERCEPTOR.md)

---

## Architecture

```
You (voice / text)
  │
  ▼
┌──────────────────────────────────┐
│  Cloud LLM available?            │
│  YES → Native function calling   │──▶ Gemini / Claude / OpenAI / Ollama
│        (78 tools, multi-turn)    │    Up to 5 rounds per request
│  NO  → On-device LLM + keywords │──▶ Qwen3.5 0.8B (llama.cpp)
└──────────┬───────────────────────┘
           │
           ▼
┌──────────────────────────────────┐
│  4-Tier Execution                │
│  1. Headless API (silent)        │
│  2. Android Intents              │
│  3. Deep Links (25 apps)         │
│  4. Accessibility (tap/type/read)│
└──────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────┐
│  Voice Pipeline                  │
│  STT: Android SpeechRecognizer   │
│  TTS: Pocket TTS / Deepgram     │
│  Wake word: sherpa-onnx (5MB)    │
└──────────────────────────────────┘
```

---

## Tech Stack

| Component | Technology |
|:----------|:-----------|
| App | Kotlin, Android XML, Navigation Component |
| Local LLM | llama.cpp (C++ via JNI), Qwen3.5 0.8B Q4_K_M |
| Cloud LLM | Gemini, Claude, OpenAI, Ollama — native function calling |
| Voice | Android STT, Pocket TTS (local), Deepgram/ElevenLabs (cloud) |
| Wake word | sherpa-onnx keyword spotter, "Hey LUI" |
| Vision | Camera2 API, Gemini multimodal analysis |
| Storage | Room (SQLite) — chat history, notification digest |
| Bridge | MCP over WebSocket, Python + Node.js SDKs |

---

## Roadmap

**Shipped:** On-device launcher + LLM, voice conversation, 78 device tools with native function calling, BYOS WebSocket bridge (MCP, event streaming, bidirectional, permission tiers, relay), agent passthrough, notification triage, accessibility screen control, 25 app deep links, cloud TTS, camera/gallery vision, wake word, model auto-download, Connection Hub.

**Next:** Health Connect integration. Geofencing. Biometric overwatch. Ambient context.

**Later:** Generative UI. Web agent. MCP server registry.

---

## Contributing

1. Fork the repo
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Build and test on a physical device
4. Submit a PR

See [DOCS.md](DOCS.md) for the bridge protocol reference and [INTERCEPTOR.md](INTERCEPTOR.md) for the full tool reference.

**Tested on:** Motorola Edge 40 Neo (Dimensity 7030, 8GB RAM).

---

## License

GPLv3

---

**Fork it. Break it. Build the Agentic Web.**
