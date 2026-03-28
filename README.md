# LUI: The Language User Interface

**The open-source Android launcher that replaces your app grid with an AI-native conversational terminal.**

The GUI is a 15-year-old paradigm — a grid of dopamine-driven icons designed to trap your attention. Meanwhile, the real computing revolution is happening headlessly. Developers are building powerful agent swarms with frameworks like OpenClaw and Hermes, but controlling them from a phone means clunky Telegram bots or terminal emulators.

**LUI** (pronounced "Louie") changes the paradigm. Built on the minimalist skeleton of [Olauncher](https://github.com/tanujnotes/Olauncher), LUI replaces your Android home screen with a pure, intent-centric conversational canvas. It's not a chatbot — it's a highly privileged Android launcher that acts as the native command center for your entire AI ecosystem.

---

## Design Philosophy: Calm Power

No neon terminals. No hacker aesthetics. Just a warm, minimal palette — black, white, gray. Clean typography. Nothing competing for your attention.

**The Front-End** is the detox. You pick up your phone and feel calm.
**The Back-End** is the storm. A high-speed agent swarm executing JSON payloads, routing APIs, intercepting notifications, and piloting legacy UIs — all invisible.

LUI handles the complexity so you never have to look at it.

---

## How It Works

### The 3-Tier Engine

| Tier | What | Cost |
|:-----|:-----|:-----|
| **Local Orchestrator** | Quantized on-device LLM (Gemma 2B / Llama 3 8B via MediaPipe). Handles intent routing, device control, notification triage, and UI generation. | Free |
| **BYOS (Bring Your Own Swarm)** | Connect to your self-hosted OpenClaw, Hermes, or direct API keys via Keystore-backed WebSocket. | Free (your infra) |
| **Managed Cloud** | Frontier models for heavy reasoning, deep web scraping, and autonomous browser piloting. | Credits |

### The Execution Hierarchy

When an agent outputs a JSON command, the **Kotlin Interceptor** executes it in priority order:

1. **API & MCP** — Machine-to-machine via structured JSON. Bypasses the phone entirely.
2. **Android Headless Hooks** — Content Providers, Background Services, Broadcast Receivers. Silent, native execution.
3. **Deep Links** — URI strings that force legacy apps to specific screens (e.g., `uber://?action=...`).
4. **UI Scraping (Last Resort)** — `AccessibilityNodeInfo` to pilot legacy UIs behind a transient overlay.

### First-Class Hardware Hooks

LUI gives your remote agents a body — exposing Android hardware and local state via secure WebSocket:

- **Contextual Geofencing** — "When I'm 2 miles from the office, spin up staging and run tests." LUI polls `LocationManager` and fires the payload when you cross the fence.
- **Autonomous 2FA Interception** — Agent hits an SMS verification wall? LUI intercepts the code via `NotificationListenerService` and streams it back through the WebSocket. Zero touch.
- **Biometric Overwatch** — High-stakes actions (moving funds, deploying to prod) trigger a native biometric prompt. Your thumbprint signs the payload to resume execution.
- **Ambient Context** — LUI knows your state. Connected to car Bluetooth? Non-urgent alerts get routed to your Evening Digest automatically.

### The Bouncer: Notification Triage

Replaces the Android notification shade with an intelligent intercept system:

- **Pass-Through** — Urgent signals (rides, banking, pinned contacts) surface as text bubbles in the canvas.
- **Batch & Summarize** — Low-priority noise is silently killed and logged for an AI-generated digest on demand.
- **Auto-Action** — 2FA codes are parsed and auto-filled; simple invites get draft confirmations for approval.

### UI Paradigms

- **The Canvas** — Persistent, monochromatic, scrolling text. No icons, no widgets.
- **Generative UI** — For visual tasks (shopping, flights), native Android components inflate directly in the chat stream, then collapse when done.
- **Browser Handoff** — WebView expands in the canvas for web tasks. The agent pilots via JS injection; touch events instantly return manual control.

---

## Architecture

```
┌─────────────────────────────────────────────┐
│              LUI Launcher Shell              │
│         (Kotlin · Olauncher Fork)            │
├──────────┬──────────────┬───────────────────┤
│  Voice / │  Canvas UI   │  Generative UI    │
│  Text In │  (RecyclerV) │  (Transient)      │
├──────────┴──────────────┴───────────────────┤
│           Local Orchestrator LLM             │
│      (MediaPipe / MLC · Gemma / Llama)       │
├─────────────────────────────────────────────┤
│            Kotlin Interceptor                │
│  ┌────────┬──────────┬────────┬───────────┐ │
│  │API/MCP │ Headless │ Deep   │ A11y      │ │
│  │        │ Hooks    │ Links  │ Scraper   │ │
│  └────────┴──────────┴────────┴───────────┘ │
├─────────────────────────────────────────────┤
│  Connection Hub                              │
│  ┌─────────────┬────────────┬─────────────┐ │
│  │ Swarm GW    │ Auth Vault │ Credit      │ │
│  │ (WSS/REST)  │ (Keystore) │ Wallet      │ │
│  └─────────────┴────────────┴─────────────┘ │
└─────────────────────────────────────────────┘
         ▼              ▼              ▼
   Local Device    BYOS Swarm    Managed Cloud
   (Intents/HW)   (OpenClaw/    (Frontier
                    Hermes)      Models)
```

---

## Tech Stack

| Component | Technology |
|:----------|:-----------|
| Frontend Shell | Kotlin, Android XML layouts, Navigation Component |
| Local LLM | llama.cpp (native C++ via JNI) |
| Models | Qwen3.5 0.8B Q4_K_M (GGUF, 508MB) |
| STT | Android SpeechRecognizer (on-device, streaming) |
| TTS | Kyutai Pocket TTS (100M params, INT8, via sherpa-onnx) |
| Fonts | JetBrains Mono (logo), DM Sans (body) |
| Agent Bridge | WebSocket (WSS), REST, MCP (Phase 2) |
| Security | Android Keystore (hardware-backed), mTLS (Phase 2) |
| Notifications | `NotificationListenerService` (Phase 2) |
| Accessibility | `AccessibilityService` (Tier 4 fallback, Phase 4) |
| Storage | SharedPreferences, internal file storage |

---

## Roadmap

### Phase 1: Core Terminal (MVP) -- COMPLETE
Native Android launcher with conversational canvas. On-device LLM (Qwen3.5 0.8B via llama.cpp). Keyword interceptor for device actions (flashlight, alarms, timers, app launch, calls, Wi-Fi/BT). App drawer with search. LUI-branded wallpaper, Dream Service, lock screen widget. Streaming response animation with thinking indicator.

### Phase 1.5: Voice -- COMPLETE
Full voice pipeline: Android SpeechRecognizer for STT (real-time, on-device), Pocket TTS for natural speech synthesis (voice cloning, sherpa-onnx). Conversation mode with producer/player streaming pipeline — TTS starts speaking during LLM text generation. Barge-in support (tap mic to interrupt). Hardware volume control for TTS output.

### Phase 2: The Agentic Bridge
Connection Hub UI. Android Keystore integration. Secure WebSocket streaming to external agent frameworks. Standardized JSON tool-calling schema.

### Phase 3: The Convenience Layer
Managed Cloud infrastructure. Credit Wallet and payment flow. Generative UI engine. Web Agent with WebView piloting.

### Phase 4: Community Launch
Closed beta with the first 100 Nodes. Friday Update cadence. Developer hackathons to crowd-source MCP server integrations.

---

## License

GPLv3

---

**Fork it. Break it. Build the Agentic Web.**
