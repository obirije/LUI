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

## All Tools (67)

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
