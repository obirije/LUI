<p align="center">
  <img src="app/src/main/res/mipmap-xxhdpi/ic_launcher.png" width="80" />
</p>

<h1 align="center">LUI: The Language User Interface</h1>

<p align="center">
  <strong>The open-source Android launcher that replaces your app grid with an AI-native conversational terminal.</strong>
</p>

---

The GUI is dead. For 15 years, your phone has been a grid of dopamine-driven icons — each one a walled garden designed to trap your attention. Meanwhile, the real revolution is happening headlessly. You're building agent swarms with OpenClaw and Hermes, orchestrating complex workflows through MCP, running local models on consumer hardware. But when you reach for your phone, you're back to tapping through legacy UIs like it's 2012.

LUI is the native mobile terminal for the agentic web. It replaces your Android home screen with a single dark canvas — no icons, no widgets, no noise. You speak or type your intent, and the phone executes it. A quantized LLM runs locally on your device. A keyword interceptor handles device actions at zero latency. A full voice pipeline lets you have real-time spoken conversations with your phone, completely offline.

Today it's a launcher that thinks locally and acts instantly. Tomorrow it's the display layer for your entire agent infrastructure — your self-hosted swarms get a body with first-class access to Android's hardware, sensors, notifications, and system APIs, all streaming through a secure WebSocket bridge.

**Your agents deserve better than a Telegram bot.**

---

## What It Does Now

- **Replaces your home screen** with a monochrome conversational canvas
- **70 headless device tools** — hardware controls, communication, calendar, media, navigation, sensors, screen control, notifications, file management, and more
- **Native tool use** with Gemini, Claude, and OpenAI — structured function calling, not JSON-in-text hacks. Multi-turn tool chaining (up to 5 rounds per request).
- **On-device LLM** (Qwen3.5 0.8B via llama.cpp) — no cloud, no API keys, fully private
- **Full voice pipeline** — real-time STT, natural TTS with voice cloning (Pocket TTS), streaming conversation mode where the voice starts speaking while the LLM is still generating
- **The Bouncer** — notification triage. Urgent notifications pass through, noise gets silently killed and batched into a persisted Evening Digest, 2FA codes are auto-extracted. Only active when LUI is your launcher.
- **AccessibilityService** — read any app's screen, tap buttons, type text, scroll. The LLM can pilot any app on your phone.
- **BYOS Bridge** — MCP-compatible WebSocket server. `pip install lui-bridge` or `npm install -g lui-bridge` to connect any agent. Event streaming, bidirectional communication, agent passthrough ("patch me to hermes"), three permission tiers with on-device approval. See [DOCS.md](DOCS.md).
- **25 app deep links** — "play Despacito on Spotify", "search on Netflix", "find on YouTube"
- **Sensor access** — proximity, ambient light, step counter
- **Persistent conversations** across restarts (Room/SQLite)
- **Searchable app drawer** (long-press), first-launch onboarding, matching wallpaper + widget + screen saver

## What's Coming

- **BYOS (Bring Your Own Swarm)** — WebSocket bridge to your self-hosted OpenClaw, Hermes, or any MCP-compatible agent framework. Your swarm gets access to Android hardware: geofencing, 2FA interception, biometric gates, ambient device context.
- **Generative UI** — When the canvas needs to show you something visual (shopping results, flight options, a chart), native Android components inflate directly in the chat stream, then collapse when you're done.
- **Geofencing** — "When I'm near the office, run X." Silent background monitoring.
- **Biometric Overwatch** — High-stakes agent actions pause for thumbprint confirmation.

---

## Architecture

```
You (voice / text)
  │
  ▼
┌──────────────────────────────────┐
│  Cloud available?                │
│  YES → LLM orchestrates          │──▶ Native tool use (Gemini/Claude/OpenAI)
│        (structured function       │    Multi-turn chaining, up to 5 rounds
│         calling, 67 tools)        │
│  NO  → Keyword Interceptor       │──▶ Device action (instant, regex-matched)
│        + Local LLM fallback       │    Conversational response (streamed)
└──────────┬───────────────────────┘
           │
           ▼
┌──────────────────────────────────┐
│  4-Tier Execution Hierarchy      │
│  1. Native API / headless hooks  │    Silent, invisible
│  2. Android Intents              │    May briefly surface app UI
│  3. Deep Links (25 apps)         │    Opens app to specific screen
│  4. Accessibility scraping       │    Read screen, tap buttons, type
└──────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────┐
│  Voice Pipeline                  │
│  STT: Android SpeechRecognizer   │
│  TTS: Kyutai Pocket TTS (sherpa) │
│  Conversation mode: auto-listen  │
└──────────────────────────────────┘
```

---

## Tool Count: 72

| Category | Tools | Examples |
|:---------|:------|:--------|
| Hardware | 10 | Flashlight, volume, brightness, DND, rotation, ringer, lock, screenshot, split screen, screen timeout |
| Alarms & Timers | 4 | Set/dismiss alarm, set/cancel timer |
| Communication | 5 | Call, SMS, search/create contacts, read SMS |
| Calendar | 2 | Create/read events |
| Media | 5 | Play/pause, next/prev track, now playing |
| Apps & Deep Links | 3 | Open app, deep link search (25 apps), open LUI |
| Navigation | 4 | Navigate, search map, get location, get distance + drive time |
| Device Info | 5 | Time, date, battery, device info, Wi-Fi info |
| Storage | 3 | Storage/RAM info, download file, query media |
| Sensors | 3 | Step counter, proximity, ambient light |
| Notifications | 4 | Read, clear, get digest, get 2FA code |
| Screen Control | 6 | Read screen, find & tap, type text, scroll, back, home |
| Clipboard | 3 | Copy, read, share |
| Audio | 1 | Route to speaker/bluetooth/earpiece |
| System | 5 | Open settings, Wi-Fi/BT settings, wallpaper, bedtime mode |
| Bridge | 5 | Start/stop bridge, status, list agents, instruct agent |
| Meta | 2 | Undo, triage config |

---

## Tech Stack

| Component | Technology |
|:----------|:-----------|
| Shell | Kotlin, Android XML layouts, Navigation Component |
| LLM | llama.cpp (native C++ via JNI), Qwen3.5 0.8B Q4_K_M |
| Cloud LLM | Gemini, Claude, OpenAI, Ollama (native tool use, 4 providers) |
| STT | Android SpeechRecognizer (on-device, streaming) |
| TTS | Kyutai Pocket TTS INT8 via sherpa-onnx (local), Deepgram/ElevenLabs (cloud) |
| Storage | Room (SQLite) for chat history + notification digest |
| Tool System | ToolRegistry (structured schemas) → ActionExecutor → 72 tools |
| Bridge SDK | Python (`pip install lui-bridge`) + Node.js (`npm install -g lui-bridge`) |
| Fonts | JetBrains Mono (logo), DM Sans (body) |

---

## Building

Requires JDK 17, Android SDK 35, NDK 28, CMake 3.31+.

```bash
git clone --depth 1 https://github.com/ggerganov/llama.cpp.git /tmp/llama-cpp
ln -sf /tmp/llama-cpp llama/src/main/cpp/llama.cpp

./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Models (LLM, TTS) are not bundled — push to device separately. See [MVP.md](MVP.md) for model downloads and staging paths.

For the BYOS bridge and MCP protocol reference, see [DOCS.md](DOCS.md). For the full tool reference, see [INTERCEPTOR.md](INTERCEPTOR.md).

**Tested on:** Motorola Edge 40 Neo (Dimensity 7030, 8GB RAM).

---

## Cloud Configuration

LUI works fully offline by default. For stronger reasoning and native tool use, connect cloud services via the **Connection Hub** (tap the status dot or draw an "S" on the canvas).

### LLM (Reasoning + Tool Use)

| Provider | Free Tier | How to get a key |
|:---------|:----------|:-----------------|
| **Gemini** | 15 RPM free | [aistudio.google.com](https://aistudio.google.com/apikey) |
| **Claude** | None (paid) | [console.anthropic.com](https://console.anthropic.com/) |
| **OpenAI** | None (paid) | [platform.openai.com](https://platform.openai.com/api-keys) |
| **Ollama** | Free (self-hosted) | [ollama.com](https://ollama.com/) — run any model locally |

All four providers use **native structured tool use**. Ollama uses the OpenAI-compatible API — any model with tool support (Qwen 3.5, Llama 3.1, Mistral) works with all 72 tools.

### Ollama Setup

Run any model on your machine and connect LUI to it:

1. Install Ollama: `curl -fsSL https://ollama.com/install.sh | sh`
2. Pull a model: `ollama pull qwen3.5:9b`
3. Set `OLLAMA_HOST=0.0.0.0` and `OLLAMA_ORIGINS=*` in your Ollama service config so LUI can reach it from the phone
4. In LUI Connection Hub → select **Ollama** → enter your machine's IP (e.g., `http://YOUR_IP:11434`) and model name
5. Toggle **Cloud-first** on

For remote access, use a Cloudflare tunnel pointing to `localhost:11434` and enter the tunnel URL as the endpoint.

### Speech (TTS + STT)

| Provider | Free Tier | How to get a key |
|:---------|:----------|:-----------------|
| **Deepgram** | $200 credit | [console.deepgram.com](https://console.deepgram.com/) |
| **ElevenLabs** | 10K chars/month | [elevenlabs.io](https://elevenlabs.io/) |

**Gemini free tier is the easiest way to get started** — 15 requests per minute, no credit card, and it gives you full native tool use with all 67 tools.

---

## Roadmap

**Shipped:** On-device launcher + LLM, voice conversation, 72 device tools with native function calling, BYOS WebSocket bridge (MCP protocol, event streaming, bidirectional agent communication, permission tiers, on-device approval, relay, `pip install lui-bridge`), agent passthrough mode ("patch me to hermes"), notification triage (The Bouncer), accessibility screen control, 25 app deep links, sensor access, chat persistence, cloud API (Gemini/Claude/OpenAI), cloud TTS (Deepgram/ElevenLabs), Connection Hub, file-based debug logging.

**Next:** Geofencing. Biometric overwatch. Ambient context. MCP server registry.

**Later:** Credit wallet. Generative UI. Web agent. MCP server.

---

## License

GPLv3

---

**Fork it. Break it. Build the Agentic Web.**
