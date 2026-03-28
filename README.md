# LUI

**An Android launcher where you talk to your phone instead of tapping through it.**

Your home screen is a dark canvas. No app grid, no widgets, no noise. You type or speak what you want, and it happens — set an alarm, open Spotify, turn on the flashlight, ask a question. A small on-device LLM runs locally on your phone. No cloud required.

LUI (pronounced "Louie") started as a simple question: what if your launcher was a conversation?

---

## What it does today

- **Replaces your home screen** with a monochrome chat interface
- **Runs a local LLM** (Qwen3.5 0.8B) entirely on-device via llama.cpp — no internet, no API keys
- **Executes device actions** instantly through a keyword interceptor: flashlight, alarms, timers, volume, brightness, DND, rotation, app launching, phone calls, Wi-Fi/BT settings
- **Voice conversation** — tap mic to speak, long-press for continuous conversation mode. On-device STT, natural TTS (Pocket TTS with voice cloning)
- **Remembers your conversations** across app restarts (Room/SQLite)
- **App drawer** — long-press for a searchable list of all your apps
- **Branded experience** — auto-sets a matching dark wallpaper, screen saver, and lock screen widget

## What it doesn't do yet

- Connect to remote agent frameworks (OpenClaw, Hermes) via WebSocket
- Triage notifications intelligently
- Browse the web autonomously
- Handle payments or credits

Those are Phase 2+. Right now it's a launcher that listens, thinks locally, and acts.

---

## Design

Black, white, gray. DM Sans for text, JetBrains Mono for the logo. No color, no gradients, no visual noise.

The idea is simple: the screen should feel like a calm surface. Everything complex — the LLM inference, the voice pipeline, the action routing — happens underneath where you don't see it.

---

## Architecture

```
You (voice/text)
  |
  v
┌──────────────────────────────┐
│  Keyword Interceptor (regex) │──> Device actions (instant)
│  12 actions, always checked  │
│  first before LLM            │
└──────────┬───────────────────┘
           | (no match)
           v
┌──────────────────────────────┐
│  Qwen3.5 0.8B (llama.cpp)   │──> Conversational response
│  On-device, Q4 quantized     │    (streamed token by token)
│  ~508MB model file           │
└──────────────────────────────┘
           |
           v
┌──────────────────────────────┐
│  Voice Pipeline              │
│  STT: Android SpeechRecognizer│
│  TTS: Pocket TTS (sherpa-onnx)│
│  Producer/player streaming   │
└──────────────────────────────┘
```

The interceptor always runs first. If someone says "turn on the flashlight," it fires instantly — no LLM round-trip. The LLM only handles things the interceptor can't match: open questions, conversation, anything that needs reasoning.

---

## Tech Stack

| | |
|:--|:--|
| Shell | Kotlin, Android XML, Navigation Component |
| LLM | llama.cpp (C++ via JNI), Qwen3.5 0.8B Q4_K_M |
| STT | Android SpeechRecognizer |
| TTS | Kyutai Pocket TTS INT8 via sherpa-onnx |
| Storage | Room (SQLite) for chat history |
| Fonts | JetBrains Mono (logo), DM Sans (body) |

## Building

Requires JDK 17, Android SDK 35, NDK 28, CMake 3.31+.

```bash
# Clone llama.cpp source (needed for native build)
git clone --depth 1 https://github.com/ggerganov/llama.cpp.git /tmp/llama-cpp
ln -sf /tmp/llama-cpp llama/src/main/cpp/llama.cpp

# Build
./gradlew assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

The LLM model, STT, and TTS models need to be pushed to the device separately. See [MVP.md](MVP.md) for details.

## Tested on

Motorola Edge 40 Neo (MediaTek Dimensity 7030, 8GB RAM, Android 15). The 0.8B model runs comfortably. The 1.7B was tested and rejected — too slow on this hardware.

---

## Roadmap

**Done:** Launcher, on-device LLM, voice (STT + TTS + conversation mode), 12 device actions, chat persistence, onboarding, wallpaper/widget/screensaver.

**Next:** WebSocket bridge to remote agent frameworks. Notification triage. Cloud API fallback. Geofencing.

**Later:** Managed cloud tier. Credit wallet. Generative UI. Web agent. Accessibility scraping for legacy apps.

---

## License

GPLv3
