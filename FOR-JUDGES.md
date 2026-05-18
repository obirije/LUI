# LUI for Postpartum — Gemma 4 For Good Submission

> An AI companion for the first year. On-device Gemma 4, a smart ring, and a phone that quiets itself at 3am.

**Track:** Health · **Demo device:** Moto Edge 40 Neo (£250 mid-range, Dimensity 7030) · **Hardware add-on:** Colmi R09 smart ring (~£13)

---

## What it is

LUI is an Android app that turns a £200 phone and a £13 smart ring into a postpartum companion that runs entirely on the device. Voice-first, hands-free, offline-capable. The ring streams heart rate, HRV, blood oxygen, stress score, sleep stages and temperature. Gemma 4 reads natural language ("I'm wrecked", "help me breathe"), picks the right action, and the app actually executes it — dim the screen, play calming sound, queue a reschedule message, open a breathing card.

The goal is to put usable tools in a new mother's hand for the moments between appointments, when "everything looks fine" at the clinic but doesn't feel fine at home.

---

## What it does

| She says | LUI does (5–10 seconds, on-device) |
|---|---|
| *"Hey LUI."* (wake word) | Phone enters conversation mode. On-device wake-word model, no cloud trigger detection |
| *"How am I doing?"* / *"Show my vitals."* | Opens an in-chat vitals card: heart rate, HRV, SpO₂, stress, temperature, steps, ring battery — pulled live from the ring, summarised at a glance |
| *"I'm wrecked. Baby's been up five times."* | Reads HRV from the ring, offers to push the 9am pediatric call, primes a breath reset |
| *"I'm anxious, help me breathe."* | Opens a 4-7-8 breathing card with a paced animation, in silence, hands-free |
| *"I need to wind down."* | Plays calming sound, Do Not Disturb on, screen dimmed, all from one phrase |
| *"What does this say?"* (formula label) | Camera reads the label aloud: dosage, warnings, allergen list |
| *"How was last night?"* | Sleep recap, HRV trend, gentle observation if patterns are worsening |

Every flow runs entirely on-device when the phone is offline. Gemma 4 E2B for reasoning, Sherpa-ONNX for wake-word and STT/TTS fallback, llama.cpp for inference. Verified end-to-end on the £250 phone in airplane mode.

**Hands-free is the point.** One hand is holding the baby. The other is doing something else. The full loop (wake word → speech-to-text → Gemma 4 → tool execution → text-to-speech reply) works without touching the screen and without the internet.

---

## How Gemma 4 fits

**Cloud (Gemma 4 26B-A4B via Google AI Studio)** handles rich conversation, the full 112-tool toolset, and multi-turn reasoning. This is the "full assistant" mode for when the user is online and wants depth.

**On-device (Gemma 4 E2B IQ3_XXS, 2.4 GB)** handles the postpartum-mode demo with 8 tools curated for the first year (`get_health_summary`, `get_sleep`, `start_wellness_mode`, `stop_wellness_mode`, `start_breathing_exercise`, `generate_relaxing_music`, `read_calendar`, `detect_stress_patterns`). Tight system prompt, JSON tool-call output, no thinking traces shown to the user. **This is the privacy mode** — when the device is offline the cloud path is unreachable by design and the on-device path handles everything.

The router picks based on network state and user preference, satisfying the Cactus Prize criterion of *"intelligently routes tasks between models"*. The model is loaded lazily on first chat send, so app startup is instant and mid-range hardware doesn't OOM during launch.

---

## What we proved this week

- **Gemma 4 E2B loads and chats on a £250 Android phone.** 2.4 GB GGUF plus a 2K-context KV cache fits under the device's low-memory-killer ceiling.
- **On-device tool-call routing works end-to-end.** Example: *"I need to wind down"* → Gemma 4 emits `{"tool":"start_wellness_mode"}` → parser routes the call → brown noise + DND + dimmed screen in 8 seconds, fully offline.
- **Real load-progress UI.** Plumbed `llama_model_params.progress_callback` through JNI so the user sees a real percentage bar during the one-time ~70-second cold load.
- **End-to-end offline voice loop.** Wake word ("Hey LUI", Sherpa-ONNX, ~5 MB) → local STT (Android `SpeechRecognizer`) → Gemma 4 E2B → local TTS (Sherpa-ONNX Pocket TTS, ~20 ms/chunk). Airplane mode, no cloud, works.
- **Rich in-chat cards for health.** `get_health_summary` renders a custom card with the full vitals snapshot (HR, HRV, SpO₂, stress, temperature, steps, ring battery); `get_health_trend` renders a 7-day trend chart inline. Same chat surface, no app-switching.
- **Vision on demand.** Camera capture rescaled to 640×480 base64 → Gemini Flash for medication and formula label reading. Image never persisted unless the user explicitly saves.

---

## Honest constraints

Gemma 4 E2B on a Dimensity 7030 with 7.6 GB RAM and other apps loaded is tight. First chat takes ~70 seconds to cold-load the model; subsequent chats are 5–10 seconds per turn. Under heavy RAM pressure the OS sometimes kills the app mid-load and the user retries.

On a flagship 12 GB+ phone (Pixel 8 Pro, Galaxy S24, OnePlus 12), the same model loads in ~10 seconds and runs smoothly. The architecture works; mid-range hardware is the live constraint. This is documented in the codebase rather than gated around.

---

## Demo

- 📹 **Demo video:** [link to be added at submission]
- 🌐 **Landing page:** https://luios.xyz/health.html
- 📦 **APK (universal arm64):** GitHub Releases on this repo
- 💍 **Hardware:** Colmi R09 smart ring (any Colmi-protocol ring will work)

---

## What you're looking at in the code

- `app/src/main/java/com/lui/app/llm/SystemPrompt.kt` — `POSTPARTUM_LOCAL_PROMPT` and `parseLocalToolCall()`. The on-device tool-call format.
- `app/src/main/java/com/lui/app/llm/ToolRegistry.kt` — `postpartumTools` filtered list (8 tools).
- `app/src/main/java/com/lui/app/LuiViewModel.kt` — `generateWithLocalLlm()`. Lazy load with real progress bar, JSON tool-call interception during streaming, route to `executeToolCall()`.
- `llama/src/main/cpp/ai_chat.cpp` — JNI shim: `progress_callback` wiring, 2K context size.
- `app/src/main/java/com/lui/app/scenarios/ProactiveScenarios.kt` — Four proactive flows including `morning_briefing` and `detect_stress_patterns`.

---

## License + attribution

GPLv3 for the app code. CC-BY 4.0 for the submission materials (per Kaggle requirement).

Gemma is a trademark of Google LLC.
