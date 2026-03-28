<p align="center">
  <img src="app/src/main/res/mipmap-xxhdpi/ic_launcher.png" width="80" />
</p>

<h1 align="center">LUI: The Language User Interface</h1>

<p align="center">
  <strong>An open-source Android launcher that replaces the app grid with an AI-native conversational terminal.</strong>
</p>

---

Your home screen is a dark canvas. No app grid, no widgets, no noise. You type or speak what you want, and it happens. A small language model runs entirely on your phone — no cloud, no API keys, no data leaving your device.

LUI is what happens when you stop building around apps and start building around intent. Instead of navigating through screens to reach a function, you state what you need. The phone figures out the rest.

---

## Design Philosophy: Calm Power

Black, white, gray. DM Sans for text, JetBrains Mono for the logo. No color, no gradients, no visual competition.

**The surface is calm.** You pick up your phone and see nothing fighting for your attention.
**Underneath is a storm.** An on-device LLM, a keyword interceptor, a voice pipeline with streaming TTS, and a growing set of headless device actions — all invisible.

LUI handles the complexity so you never have to look at it.

---

## What LUI Does Today

- **Replaces your home screen** with a monochrome conversational canvas
- **Runs a local LLM** (Qwen3.5 0.8B) entirely on-device via llama.cpp
- **Executes 12 device actions** instantly through a keyword interceptor — flashlight, alarms, timers, volume, brightness, DND, screen rotation, app launch, phone calls, Wi-Fi/Bluetooth settings, wallpaper
- **Full voice pipeline** — tap mic for a single query, long-press for continuous conversation. On-device speech recognition, natural TTS with voice cloning (Pocket TTS). Streaming playback starts while the LLM is still generating text.
- **Persistent memory** — conversations survive app restarts
- **App drawer** — long-press for a searchable list of installed apps
- **Integrated experience** — auto-sets a matching dark wallpaper, screen saver (Dream Service), and lock screen widget

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

The interceptor fires first. "Turn on the flashlight" executes instantly — no LLM round-trip. The model only handles what the interceptor can't: open questions, conversation, anything that needs reasoning.

---

## Tech Stack

| Component | Technology |
|:----------|:-----------|
| Shell | Kotlin, Android XML layouts, Navigation Component |
| LLM | llama.cpp (native C++ via JNI), Qwen3.5 0.8B Q4_K_M |
| STT | Android SpeechRecognizer (on-device, streaming) |
| TTS | Kyutai Pocket TTS INT8 via sherpa-onnx |
| Storage | Room (SQLite) for chat history, SharedPreferences |
| Fonts | JetBrains Mono (logo), DM Sans (body) |

---

## Building

Requires JDK 17, Android SDK 35, NDK 28, CMake 3.31+.

```bash
# Clone llama.cpp source (required for native build)
git clone --depth 1 https://github.com/ggerganov/llama.cpp.git /tmp/llama-cpp
ln -sf /tmp/llama-cpp llama/src/main/cpp/llama.cpp

# Build
./gradlew assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

The LLM, STT, and TTS models are not bundled in the APK. They need to be pushed to the device separately — see [MVP.md](MVP.md) for model download instructions and staging paths.

**Tested on:** Motorola Edge 40 Neo (Dimensity 7030, 8GB RAM, Android 15). Qwen3.5 0.8B runs well. Qwen3 1.7B was benchmarked and rejected — too slow on mid-range hardware.

---

## Roadmap

### Shipped
Launcher shell. On-device LLM. Voice conversation (STT + TTS + streaming). 12 headless device actions. Chat persistence. First-launch onboarding. Wallpaper, widget, screen saver.

### Next (Phase 2)
BYOS WebSocket bridge to remote agent frameworks (OpenClaw, Hermes). Connection Hub UI. Android Keystore for secrets. Notification triage (The Bouncer). Geofencing and ambient context.

### Later (Phase 3+)
Cloud API fallback. Credit wallet. Generative UI. Web agent with WebView piloting. Accessibility scraping for walled-garden apps.

---

## License

GPLv3

---

**Fork it. Break it. Build the Agentic Web.**
