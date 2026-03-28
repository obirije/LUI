# LUI MVP Scope

## Status: COMPLETE

## Goal
A working Android launcher you can set as your default home screen that accepts text input, routes it through a local LLM, and can execute basic device actions. Proves the core loop: intent in → agent reasoning → headless action out.

---

## The Big Bet — RESOLVED

The original bet: can a quantized small model running on a phone reliably handle intents?

**Answer: Hybrid approach wins.** Qwen3 1.7B was too slow on a mid-range phone (Dimensity 7030, 8GB RAM). Qwen3.5 0.8B is fast and conversational. The keyword interceptor handles device actions instantly and reliably — the LLM handles freeform conversation. Each does what it's best at.

---

## Development Sequence

### Week 1: The Shell
**Goal:** A launcher on a phone with a working chat UI.

- [ ] Fork [Olauncher](https://github.com/tanujnotes/Olauncher)
- [ ] Strip the app grid / home screen UI
- [ ] Verify it registers as a valid launcher (`HOME` intent)
- [ ] Build the canvas: scrolling `RecyclerView` chat interface
- [ ] Text input bar at bottom (mic icon placeholder for future voice)
- [ ] Message bubbles: user (right), LUI (left)
- [ ] Dark, monochromatic theme
- [ ] Long-press gesture for app drawer escape hatch
- [ ] Echo user input back as a stub response — prove the shell works

### Week 2: The Brain (Go/No-Go Gate)
**Goal:** Text in → LLM response out, streaming in the canvas. Benchmark viability.

- [ ] Integrate MediaPipe LLM Inference API
- [ ] First-boot model download flow (Gemma 2B, ~1.5GB) with progress UI and resume support
- [ ] Wire: user text → model inference → streaming token output in canvas
- [ ] Handle model loading state (splash/progress indicator)
- [ ] Benchmark on target device:
  - Tokens/sec (need 10+ for usable UX)
  - Time-to-first-token
  - RAM pressure / thermal throttling
  - Tool-call JSON reliability (can it consistently output structured JSON from a system prompt?)

**This is the go/no-go gate.** If on-device inference is too slow or too unreliable for structured output, pivot strategy:
- **Option A:** Default to cloud API (Gemini, Claude, OpenAI) with local as optional
- **Option B:** Local model for chat, keyword-based fallback for tool routing

### Week 3: The Interceptor
**Goal:** "Turn on the flashlight" → flashlight turns on.

- [ ] Define minimal JSON tool-call schema:
  ```json
  {"tool": "set_alarm", "params": {"time": "07:30", "label": "Morning"}}
  ```
- [ ] Build the Kotlin Interceptor: dual parser approach
  - **Primary:** Extract JSON tool calls from LLM output
  - **Fallback:** Keyword/regex matcher for common intents (catches cases where the 2B model responds in prose instead of JSON)
- [ ] Validate all tool calls against a whitelist — unknown tools display as plain text
- [ ] Implement system actions that actually work headlessly:
  - Toggle flashlight (`CameraManager`)
  - Set alarm / timer (`AlarmClock` intents — note: briefly surfaces Clock app)
  - Open app by name (`PackageManager` query + launch intent, fuzzy matching)
  - Make a phone call (`ACTION_CALL`)
  - Open Settings panels (Wi-Fi, Bluetooth — Android 10+ blocks direct toggle)
- [ ] Deferred to Phase 2 (Android restrictions):
  - Send SMS (Play Store gates `SEND_SMS` permission)
  - Direct Wi-Fi/BT toggle (removed in Android 10+)

### Week 4: Polish & Daily Drive
**Goal:** Use LUI as your actual launcher for a week.

- [ ] First-launch permission flow:
  - Default launcher
  - Phone (for calls)
  - Notification access (stub — wired in Phase 2)
- [ ] Graceful degradation: if a permission is denied, disable that action and explain in chat
- [ ] Basic app search: query `PackageManager`, fuzzy match, fire launch intent
- [ ] Edge case handling:
  - Model not yet downloaded → explain and offer download
  - No internet for model download → clear error
  - Action fails → explain what happened in chat
- [ ] Sideload APK to test device, use as daily launcher
- [ ] Collect real-world friction points for Phase 2

---

## What's Explicitly OUT of MVP

| Feature | Why deferred |
|:--------|:-------------|
| BYOS / WebSocket bridge | Phase 2 — needs Connection Hub UI + Keystore auth |
| Managed Cloud tier | Phase 3 — needs backend infra + billing |
| Notification triage (The Bouncer) | Phase 2 — needs `NotificationListenerService` + triage logic |
| Generative UI | Phase 3 — complex dynamic view inflation engine |
| Web Agent / WebView pilot | Phase 3 — JS injection, CSP issues on modern sites |
| Accessibility scraper | Phase 4 — fragile, app updates break scrapers constantly |
| Credit wallet / payments | Phase 3 |
| Voice input | After text input is solid |
| Geofencing / ambient context | Phase 2+ |
| Biometric overwatch | Phase 2+ |
| Send SMS | Play Store permission restrictions — revisit for sideload builds |

---

## Technical Decisions

### Compose vs XML?
Olauncher uses XML. **Start with XML** to ship faster. Refactor to Compose in Phase 2 when building Generative UI (which actually needs it).

### Model bundling?
**Do not bundle in APK.** First-boot download to internal storage. ~1.5GB. Needs resume support and progress UI.

### Tool-call reliability?
**Dual parser.** LLM JSON extraction as primary, keyword/regex fallback as safety net. The fallback catches "turn on the flashlight" even when the model responds conversationally instead of producing JSON.

### Play Store vs sideload?
**Sideload for beta.** SMS, phone call, and notification access permissions will trigger Play Store review flags. Distribute APK directly to early testers. Revisit Play Store when the permission story is cleaner.

---

## Android Reality Check

Things the README promises that Android actually restricts:

| Action | Reality |
|:-------|:--------|
| Toggle Wi-Fi silently | Removed in Android 10+. Can only open Settings panel. |
| Toggle Bluetooth silently | Same — `Settings.Panel` only on modern Android. |
| Send SMS headlessly | Requires `SEND_SMS` + Play Store scrutiny. Works for sideloaded builds. |
| Alarm/Timer | Works via `AlarmClock` intents but briefly surfaces the Clock app UI. |
| Flashlight | Works fully headless via `CameraManager`. |
| App launch | Works fully headless. |
| Phone call | Works with `CALL_PHONE` permission. Play Store scrutiny. |

The "invisible complexity" promise holds for API/MCP actions and some system hooks, but many local device actions will briefly surface native Android UI. Be honest about this in the UX — a brief flash of the Clock app setting your alarm is acceptable; pretending it didn't happen isn't.

---

## Suggested File Structure

```
lui/
├── app/
│   ├── src/main/
│   │   ├── java/com/lui/
│   │   │   ├── MainActivity.kt              # Launcher entry point
│   │   │   ├── ui/
│   │   │   │   ├── canvas/
│   │   │   │   │   ├── CanvasActivity.kt    # Main chat screen
│   │   │   │   │   ├── MessageAdapter.kt    # RecyclerView adapter
│   │   │   │   │   └── InputBar.kt          # Text input component
│   │   │   │   └── setup/
│   │   │   │       └── SetupActivity.kt     # First-launch permissions
│   │   │   ├── llm/
│   │   │   │   ├── LocalModel.kt            # MediaPipe wrapper
│   │   │   │   └── ModelDownloader.kt       # First-boot model fetch
│   │   │   ├── interceptor/
│   │   │   │   ├── Interceptor.kt           # Dual parser (JSON + keyword)
│   │   │   │   └── actions/
│   │   │   │       ├── SystemActions.kt     # Flashlight, settings panels
│   │   │   │       ├── CommsActions.kt      # Phone call
│   │   │   │       ├── AlarmActions.kt      # Alarms, timers
│   │   │   │       └── AppLauncher.kt       # Open apps by name
│   │   │   └── util/
│   │   │       └── Permissions.kt           # Permission helpers
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── activity_canvas.xml
│   │   │   │   ├── item_message.xml
│   │   │   │   └── activity_setup.xml
│   │   │   └── values/
│   │   │       ├── strings.xml
│   │   │       ├── colors.xml               # Monochromatic palette
│   │   │       └── themes.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
├── MVP.md
└── idea.md
```

---

## Definition of Done (MVP)

1. Install LUI on an Android phone and set it as your default launcher
2. See a clean, dark, text-only canvas as your home screen
3. Type "turn on the flashlight" and the flashlight turns on
4. Type "set an alarm for 7:30am" and an alarm is created
5. Type "open Spotify" and Spotify launches
6. Have a freeform conversation with the local LLM about anything
7. Long-press to access an app drawer as an escape hatch
8. On-device inference benchmarked with clear go/no-go data
