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
- **On-device LLM** (Qwen3.5 0.8B via llama.cpp) — no cloud, no API keys, fully private
- **12 headless device actions** via keyword interceptor — flashlight, alarms, timers, volume, brightness, DND, rotation, app launch, calls, Wi-Fi/BT, wallpaper — all instant, no LLM round-trip
- **Full voice pipeline** — real-time STT, natural TTS with voice cloning (Pocket TTS), streaming conversation mode where the voice starts speaking while the LLM is still generating
- **Persistent conversations** across restarts (Room/SQLite)
- **Searchable app drawer** (long-press), first-launch onboarding, matching wallpaper + widget + screen saver

## What's Coming

- **BYOS (Bring Your Own Swarm)** — WebSocket bridge to your self-hosted OpenClaw, Hermes, or any MCP-compatible agent framework. Your swarm gets access to Android hardware: geofencing, 2FA interception, biometric gates, ambient device context.
- **The Bouncer** — Notification triage. LUI intercepts your notification stream, passes through the urgent ones, batches the noise into an AI digest, and auto-handles 2FA codes.
- **Generative UI** — When the canvas needs to show you something visual (shopping results, flight options, a chart), native Android components inflate directly in the chat stream, then collapse when you're done.
- **Cloud tier** — Optional. For when you need frontier-model reasoning without self-hosting. Burns credits, not your privacy by default.

---

## Architecture

```
You (voice / text)
  │
  ▼
┌──────────────────────────────────┐
│  Keyword Interceptor             │──▶ Device action (instant)
│  12 tools, regex-matched         │
│  Always checked before the LLM   │
└──────────┬───────────────────────┘
           │ no match
           ▼
┌──────────────────────────────────┐
│  Qwen3.5 0.8B (llama.cpp)       │──▶ Conversational response
│  On-device, Q4 quantized         │    (streamed token by token)
│  ~508MB GGUF model               │
└──────────┬───────────────────────┘
           │
           ▼
┌──────────────────────────────────┐
│  Voice Pipeline                  │
│  STT: Android SpeechRecognizer   │
│  TTS: Kyutai Pocket TTS (sherpa) │
│  Producer/player streaming       │
└──────────────────────────────────┘
```

The interceptor fires first. "Turn on the flashlight" executes in milliseconds — no inference, no latency. The LLM only handles what the interceptor can't: open questions, conversation, reasoning.

---

## Tech Stack

| Component | Technology |
|:----------|:-----------|
| Shell | Kotlin, Android XML layouts, Navigation Component |
| LLM | llama.cpp (native C++ via JNI), Qwen3.5 0.8B Q4_K_M |
| STT | Android SpeechRecognizer (on-device, streaming) |
| TTS | Kyutai Pocket TTS INT8 via sherpa-onnx |
| Storage | Room (SQLite) for chat history |
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

**Tested on:** Motorola Edge 40 Neo (Dimensity 7030, 8GB RAM). Qwen3.5 0.8B runs well. Qwen3 1.7B was benchmarked and rejected — too slow on mid-range silicon.

---

## Cloud Configuration

LUI works fully offline by default. For stronger reasoning and natural voice, you can optionally connect cloud services via the **Connection Hub** (tap the status dot or draw an "S" on the canvas).

### LLM (Reasoning)

| Provider | Free Tier | How to get a key |
|:---------|:----------|:-----------------|
| **Gemini** | 15 RPM free | [aistudio.google.com](https://aistudio.google.com/apikey) |
| **Claude** | None (paid) | [console.anthropic.com](https://console.anthropic.com/) |
| **OpenAI** | None (paid) | [platform.openai.com](https://platform.openai.com/api-keys) |

Toggle **Cloud-first** to use the cloud model by default. When off, LUI uses the local Qwen3.5 0.8B and only falls back to cloud if the local model isn't loaded.

### Speech (TTS + STT)

| Provider | Free Tier | How to get a key |
|:---------|:----------|:-----------------|
| **Deepgram** | $200 credit | [console.deepgram.com](https://console.deepgram.com/) |
| **ElevenLabs** | 10K chars/month | [elevenlabs.io](https://elevenlabs.io/) |

Enable **Cloud Speech**, select a provider, paste your key, and pick a voice from the dropdown. Deepgram voices work immediately. ElevenLabs requires adding voices to "My Voices" in their dashboard first — library voices need a paid plan.

When cloud speech is off, LUI uses on-device Android SpeechRecognizer (STT) and Kyutai Pocket TTS (TTS).

**Gemini free tier is the easiest way to get started** — 15 requests per minute, no credit card, and it dramatically improves reasoning over the local 0.8B model.

---

## Roadmap

**Shipped:** On-device launcher + LLM, voice conversation, 12 device actions, chat persistence, onboarding, cloud API fallback (Gemini/Claude/OpenAI), cloud TTS (Deepgram/ElevenLabs), Connection Hub, splash screen.

**Next:** BYOS WebSocket bridge. Notification triage. Geofencing. Ambient context.

**Later:** Credit wallet. Generative UI. Web agent. Accessibility scraping.

---

## License

GPLv3

---

**Fork it. Break it. Build the Agentic Web.**
