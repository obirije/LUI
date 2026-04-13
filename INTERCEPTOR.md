# LUI Interceptor & Tool System — Reference

## Architecture

LUI uses a dual-path system depending on whether a cloud model is available:

```
CLOUD MODE (Gemini/Claude/OpenAI):
  User input
    │
    ├─ Live-state query? (notifications, battery, time, etc.)
    │   → Force execute tool → Feed result to LLM for interpretation
    │
    └─ Everything else → LLM orchestrates via native function calling
        → Model calls tools via structured API (not JSON in text)
        → Tool result sent back → Model chains or interprets
        → Up to 5 rounds per request

LOCAL/OFFLINE MODE:
  User input → Keyword interceptor (regex) → Local LLM fallback
```

### Native Tool Use

When cloud is active, tools are defined as structured schemas in `ToolRegistry` and sent via each provider's native tool-use API:
- **Gemini**: `tools` with `function_declarations`
- **Claude**: `tools` with `input_schema`
- **OpenAI**: `tools` with `function` type

The model returns structured `functionCall`/`tool_use` responses — no JSON parsing from text, no keyword matching on LLM output.

### Keyword Interceptor

Used for local/offline mode and for forcing live-state queries in cloud mode. Pattern-matched, first match wins, zero latency.

---

## All Tools (106)

### Hardware Controls

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `toggle_flashlight` | `state`: on/off/toggle | "turn on flashlight", "flashlight off" |
| `set_volume` | `direction`: up/down/mute/max | "volume up", "mute", "max volume" |
| `set_brightness` | `level`: up/down/max/low/0-100 | "brightness 50%", "dim the screen" |
| `toggle_dnd` | — | "do not disturb", "dnd" |
| `toggle_rotation` | — | "auto rotate" |
| `set_ringer` | `mode`: silent/vibrate/normal | "silent mode", "vibrate" |
| `lock_screen` | — | "lock my phone" |
| `take_screenshot` | — | "take a screenshot" |
| `split_screen` | — | "split screen" |
| `set_screen_timeout` | `duration`: 15s/30s/1m/5m/never | "set screen timeout to 5 minutes" |
| `keep_screen_on` | `enable`: true/false | "keep screen on" |
| `bedtime_mode` | `enable`: true/false | "bedtime mode" (chains DND + dim + timeout) |

### Alarms & Timers

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `set_alarm` | `time`, `label` | "set alarm for 7:30am" |
| `set_timer` | `amount`, `unit` | "set timer for 5 minutes" |
| `dismiss_alarm` | — | "cancel alarm", "stop alarm" |
| `cancel_timer` | — | "cancel timer" |

### Communication

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `make_call` | `target` (name or number) | "call Mom", "call 555-1234" |
| `send_sms` | `number`, `message` | "text Mom I'm running late" |
| `search_contact` | `query` | "find contact John" |
| `create_contact` | `name`, `number` | "create contact John 555-1234" |
| `read_sms` | `from` (optional) | "read my texts", "texts from Mom" |

### Calendar

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `create_event` | `title`, `date`, `time` | "schedule meeting tomorrow at 3pm" |
| `read_calendar` | `date` | "what's on my calendar today" |

### Media

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `play_pause` | — | "pause music", "play" |
| `next_track` | — | "next song" |
| `previous_track` | — | "previous track" |
| `now_playing` | — | "what song is this" |
| `query_media` | `type`, `date` | "photos I took today", "how many videos" |

### Apps & Deep Links (25 apps)

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `open_app` | `name` | "open Spotify" |
| `open_app_search` | `app`, `query` | "play Despacito on Spotify", "search on Netflix" |
| `open_lui` | — | "switch back to chat", "go home" |

Supported deep link apps: Spotify, YouTube, YouTube Music, Netflix, TikTok, Twitch, Amazon Music, SoundCloud, Deezer, X/Twitter, Reddit, Instagram, WhatsApp, Telegram, Amazon, eBay, Google Maps, Google Translate, Play Store, Shazam, Uber, Lyft, Chrome, default browser.

### Navigation & Location

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `navigate` | `destination` | "navigate to the airport" |
| `search_map` | `query` | "find coffee shops nearby" |
| `get_location` | — | "where am I" |
| `get_distance` | `destination` | "how far is the airport" (includes drive time estimate) |

### Device Info & Storage

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `get_time` | — | "what time is it" |
| `get_date` | — | "what's the date" |
| `device_info` | — | "device info" |
| `battery` | — | "battery level" |
| `wifi_info` | — | "what wifi am I on" |
| `storage_info` | — | "how much storage do I have" |
| `download_file` | `url`, `filename` | "download https://..." |

### Sensors

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `get_steps` | — | "how many steps today" |
| `get_proximity` | — | "is my phone face down" |
| `get_light` | — | "how bright is it" |

### Notifications (The Bouncer)

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `read_notifications` | — | "check my notifications" |
| `clear_notifications` | — | "clear all notifications" |
| `get_digest` | — | "show my digest", "what did I miss" |
| `clear_digest` | — | "clear digest" |
| `get_notification_history` | `hours`, `app` | "notifications from WhatsApp yesterday", "what did I get in the last 6 hours" |
| `get_2fa_code` | — | "what's my verification code" |
| `config_triage` | `app`, `bucket` | LLM-driven: mark app as urgent or noise |

### Screen Control (Accessibility — Tier 4)

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `read_screen` | — | "what's on screen" |
| `find_and_tap` | `query` | "tap Play", "click Sign In" |
| `type_text` | `text` | LLM-driven: type into focused field |
| `scroll_down` | — | "scroll down" |
| `press_back` | — | "go back" |
| `press_home` | — | "press home" |

### Audio

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `route_audio` | `target`: speaker/bluetooth/earpiece | "switch to speaker" |

### Clipboard & Sharing

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `copy_clipboard` | — | "copy that" |
| `read_clipboard` | — | "what did I copy" |
| `share_text` | `text` | "share 'hello world'" |

### Settings & System

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `open_settings` | — | "open settings" |
| `open_settings_wifi` | — | "wifi settings" |
| `open_settings_bluetooth` | — | "bluetooth settings" |
| `set_wallpaper` | — | "set wallpaper" |
| `undo` | — | "undo that" |
| `screen_time` | `app` (optional) | "screen time", "time on Instagram" |
| `bedtime_mode` | `enable` | "bedtime mode on" |
| `lock_screen` | — | "lock my phone" |
| `take_screenshot` | — | "screenshot" |
| `split_screen` | — | "split screen" |
| `set_screen_timeout` | `duration` | "screen timeout 1 minute" |
| `keep_screen_on` | `enable` | "keep screen on" |

### Health Ring (Colmi R09)

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `get_heart_rate` | — | "what's my heart rate", "HR" |
| `get_spo2` | — | "blood oxygen", "SpO2" |
| `get_stress` | — | "stress level", "am I stressed" |
| `get_hrv` | — | "HRV", "heart rate variability" |
| `get_sleep` | — | "how did I sleep", "sleep data" |
| `get_activity` | — | "steps from ring" (also reachable via `get_steps` when ring is connected) |
| `get_temperature` | — | "body temperature" |
| `ring_battery` | — | "ring battery" |
| `ring_status` | — | "ring status", "is my ring connected" |
| `ring_capabilities` | — | "what can the ring do" |
| `find_ring` | — | "find my ring" |
| `get_health_summary` | — | "check my vitals", "how's my health" |
| `get_health_trend` | `metric`, `hours` | "stress trend last 24 hours", "deep sleep this week" |

All readings persist to a local Room `health_readings` table with timestamps. Background sync refreshes every 15 minutes when the ring is connected. Sleep metrics are split into `sleep_total`, `sleep_deep`, `sleep_light`, `sleep_rem`, `sleep_awake` so trend queries can target specific phases.

### Wellness

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `play_relaxing_sound` | `type` | "play rain", "play Clair de Lune", "I need some brown noise" |
| `stop_relaxing_sound` | — | "stop sound", "stop the rain" |
| `list_relaxing_sounds` | — | "what relaxing sounds are there" |
| `start_wellness_mode` | `sound` (optional) | "help me calm down", "start wellness mode" |
| `stop_wellness_mode` | — | "stop wellness mode", "exit wellness mode" |

11 bundled tracks (9 ambient + 2 music). If `start_wellness_mode` is called without a sound, LUI auto-picks based on time of day and current ring stress level. See [docs/ambient-sounds.md](docs/ambient-sounds.md) for the full lineup and licensing.

### Web

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `search_web` | `query` | "search the web for gemma 4", "google cats" |
| `browse_url` | `url` | "browse https://example.com", "read this page" |
| `ambient_context` | — | "what's the device status", "quick check" |
| `bluetooth_devices` | — | "bluetooth devices" |
| `network_state` | — | "network state", "am I online" |

### Vision

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `take_photo` | — | "take a photo and describe it" |
| `pick_image` | — | "analyze this photo", "pick from gallery" |
| `analyze_image` | — | LLM-driven: analyze the last captured image |

### Triggers

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `create_geofence` | `name`, `latitude`, `longitude`, `radius`, `action` | "when I arrive at work, turn on DND" |
| `schedule_action` | `when`, `action` | "in 5 minutes, lock my phone" |
| `list_triggers` | — | "what triggers do I have" |
| `delete_trigger` | `id` | "delete trigger 2" |

### Bridge

| Tool | Params | Example phrases |
|:-----|:-------|:----------------|
| `start_bridge` | — | "start the bridge" |
| `stop_bridge` | — | "stop bridge" |
| `bridge_status` | — | "bridge status" |
| `list_agents` | — | "list agents", "who's connected" |
| `instruct_agent` | `agent`, `message` | "@claude-code deploy staging" |
| `start_passthrough` | `agent` | "patch me to hermes" |
| `end_passthrough` | — | "LUI come back" |

---

## Permissions

### Runtime (prompted on first use)

| Permission | Tools |
|:-----------|:------|
| `SEND_SMS` | send_sms |
| `READ_CONTACTS` | search_contact |
| `WRITE_CONTACTS` | create_contact |
| `CALL_PHONE` | make_call |
| `ACCESS_FINE_LOCATION` | get_location, get_distance |
| `READ_SMS` | read_sms |
| `READ_CALENDAR` | read_calendar |
| `ACTIVITY_RECOGNITION` | get_steps (Android 10+) |

### Special access (enabled in Settings)

| Access | Tools | How to enable |
|:-------|:------|:--------------|
| Modify System Settings | brightness, rotation, screen timeout | Settings > Apps > LUI > Modify System Settings |
| Notification Access | read_notifications, now_playing, get_digest, get_2fa_code | Settings > Notification access > LUI |
| Accessibility | read_screen, find_and_tap, type_text, lock_screen, screenshot, split_screen | Settings > Accessibility > LUI |
| DND Access | toggle_dnd | Settings > DND access > LUI |

---

## The Bouncer (Notification Triage)

Active only when LUI is the default launcher.

| Bucket | Behavior | Default apps |
|:-------|:---------|:-------------|
| **Urgent** | Pass through | Banking, rides (Uber/Lyft), delivery, phone, SMS, WhatsApp |
| **Noise** | Silently killed, batched to Evening Digest (persisted in Room) | Gmail, LinkedIn, Google News, X/Twitter |
| **Auto-Action** | 2FA codes extracted and stored for retrieval | Any notification with 4-8 digit code + keywords (code, otp, verify) |

Configure with `config_triage` tool or let the LLM manage it.

---

## Debug Logging

File-based logging at `/data/data/com.lui.app.debug/files/lui_log.txt`.

```bash
adb exec-out run-as com.lui.app.debug cat files/lui_log.txt
```

Categories: `INPUT`, `VOICE`, `INTERCEPT`, `LLM`, `GEMINI`, `TOOL`, `CHAIN`, `FORCE`, `CONFIRM`, `PERM`, `A11Y`, `BOUNCER`, `SYSTEM`.
