# LUI Interceptor — Capabilities & Reference

The interceptor is the fast path. Every user input (voice or text) hits `Interceptor.parse()` **before** the LLM. If a keyword or JSON tool call matches, the action executes in milliseconds — no inference, no latency, no cloud round-trip.

---

## How It Works

```
User input
  │
  ▼
┌─────────────────────────────┐
│  1. JSON Parser             │  Extracts {"tool":"...","params":{}} from input
│     (catches LLM output)    │  Validates against KNOWN_TOOLS whitelist
└────────┬────────────────────┘
         │ no JSON found
         ▼
┌─────────────────────────────┐
│  2. Keyword/Regex Parser    │  Pattern-matches natural language
│     (catches human input)   │  Priority order matters — first match wins
└────────┬────────────────────┘
         │ no match
         ▼
      LLM handles it
```

**Dual parser design:** The JSON parser handles structured output from cloud LLMs (which are instructed to output tool-call JSON). The keyword parser handles natural speech from humans. Both produce the same `ToolCall(tool, params)` object.

**Cloud LLM tool loop:** When a cloud model is active, it can also output tool JSON in its response. After streaming completes, the full response is parsed again. If a tool is found, it executes, and the result is fed back to the model for natural-language interpretation.

---

## Tool Reference

### Device Queries (instant, no side effects)

| Tool | What it does | Example phrases |
|:-----|:-------------|:----------------|
| `get_time` | Returns current time | "what time is it", "current time", "what's the time" |
| `get_date` | Returns current date and day | "what's the date", "what day is it", "what's today" |
| `device_info` | Returns time, date, battery, network, device model | "device info", "phone status", "system details" |
| `battery` | Returns battery level and charging status | "battery level", "how much charge", "what's my battery" |

### Hardware Controls

| Tool | Params | What it does | Example phrases |
|:-----|:-------|:-------------|:----------------|
| `toggle_flashlight` | `state`: on/off/toggle | Controls flashlight with on/off awareness | "turn on flashlight", "flashlight off", "toggle torch" |
| `set_volume` | `direction`: up/down/mute/max | Adjusts media volume | "volume up", "mute", "max volume", "sound down" |
| `set_brightness` | `level`: up/down/max/low/0-100 | Adjusts screen brightness (needs WRITE_SETTINGS) | "brightness 50%", "dim the screen", "max brightness" |
| `toggle_dnd` | — | Toggles Do Not Disturb (needs DND policy access) | "do not disturb", "dnd", "quiet mode" |
| `toggle_rotation` | — | Toggles auto-rotate (needs WRITE_SETTINGS) | "auto rotate", "screen rotation" |
| `set_ringer` | `mode`: silent/vibrate/normal | Changes ringer mode | "silent mode", "vibrate mode", "ring mode" |

### Alarms & Timers

| Tool | Params | What it does | Example phrases |
|:-----|:-------|:-------------|:----------------|
| `set_alarm` | `time`, `label` | Creates alarm via Clock app | "set alarm for 7:30am", "wake me up at 6" |
| `set_timer` | `amount`, `unit` | Creates countdown timer via Clock app | "set timer for 5 minutes", "start timer 30 seconds" |
| `dismiss_alarm` | — | Dismisses active alarm | "cancel alarm", "stop alarm", "dismiss alarm" |
| `cancel_timer` | — | Cancels active timer | "cancel timer", "stop timer" |

**Note:** Alarm/timer intents briefly surface the Clock app UI. This is an Android limitation, not a bug.

### Communication

| Tool | Params | What it does | Example phrases |
|:-----|:-------|:-------------|:----------------|
| `make_call` | `target` (name or number) | Calls a number or resolves contact name first | "call Mom", "call 555-1234", "dial John" |
| `send_sms` | `number` (name or number), `message` | Sends SMS (needs SEND_SMS permission) | "text Mom I'm running late", "send sms to 555-1234 saying hello" |
| `search_contact` | `query` | Searches contacts by name (needs READ_CONTACTS) | "find contact John", "search contacts for Sarah" |
| `create_contact` | `name`, `number` | Creates new contact (needs WRITE_CONTACTS) | "create contact John 555-1234", "add contact Sarah number 555-5678" |

**Call by name flow:** When `make_call` receives a name (not digits), it searches contacts via ContentProvider. If found, dials the number directly. If not found, opens the dialer with an explanation.

### Calendar

| Tool | Params | What it does | Example phrases |
|:-----|:-------|:-------------|:----------------|
| `create_event` | `title`, `date`, `time` | Opens calendar to create event | "schedule meeting tomorrow at 3pm", "create event dentist next Monday at 10am", "schedule lunch tomorrow" |

**Date parsing:** Supports `today`, `tomorrow`, `next Monday` (and other day names), `MM/DD`. Defaults to today if no date given, 9:00am if no time given.

### Media Controls

| Tool | What it does | Example phrases |
|:-----|:-------------|:----------------|
| `play_pause` | Toggles media playback via MediaSession | "pause music", "play song", "resume track", "pause" |
| `next_track` | Skips to next track | "next song", "skip track" |
| `previous_track` | Goes to previous track | "previous song", "back track" |

**Note:** Media controls require an active media session (Spotify, YouTube Music, etc). "play" alone works; "play a game" does NOT trigger this — the pattern requires music/song/track/audio keywords or bare play/pause.

### Apps & Navigation

| Tool | Params | What it does | Example phrases |
|:-----|:-------|:-------------|:----------------|
| `open_app` | `name` | Launches app by fuzzy name match | "open Spotify", "launch Chrome", "start Camera" |
| `open_settings` | — | Opens Android Settings | "open settings", "go to settings" |
| `open_settings_wifi` | — | Opens Wi-Fi panel/settings | "wifi", "wi-fi settings" |
| `open_settings_bluetooth` | — | Opens Bluetooth settings | "bluetooth settings", "bluetooth" |

**App matching:** Exact match → starts-with → contains → multi-word match. "open yt music" matches "YouTube Music".

### Other

| Tool | Params | What it does | Example phrases |
|:-----|:-------|:-------------|:----------------|
| `share_text` | `text` | Opens Android share sheet | "share text 'hello world'" |
| `set_wallpaper` | — | Applies the LUI wallpaper | "set wallpaper", "change wallpaper" |

---

## Keyword Priority Order

The keyword parser checks patterns **top to bottom — first match wins**. This ordering is intentional:

1. **Time/date queries** — Answered instantly, prevents LLM hallucinating wrong time
2. **Flashlight** — Common, unambiguous
3. **Alarms → Timers** — Set before cancel (set has more specific regex)
4. **Cancel alarm → Cancel timer** — After set patterns
5. **Volume → Brightness** — Hardware controls with direction params
6. **DND** — After volume (both mention "silence" — DND requires "dnd"/"do not disturb"/"quiet mode" specifically)
7. **Rotation** — Unambiguous
8. **SMS (pattern 1)** — With separator word ("saying", "message", "that")
9. **SMS (pattern 2)** — Without separator ("text mom I'm late") — simpler, more aggressive
10. **Contact search → Contact create** — Before open_app (prevents "find contact" → launching an app called "Contact")
11. **Calendar** — With time → without time
12. **Media controls** — Tightened: requires music/song/track keyword (prevents "play a game" false positive)
13. **Ringer mode** — After DND (prevents "silence" collision)
14. **Battery** — Tightened: requires question form or explicit "battery/charge" + "level/status/%" (prevents "charge ahead" false positive)
15. **Share text** — Requires quoted text
16. **Open settings** — Before open_app
17. **Open app** — Catch-all for "open/launch/start X" (excludes wifi/bluetooth/settings/flashlight)
18. **Make call** — Catch-all for "call/phone/dial X"
19. **Wi-Fi → Bluetooth** — Very broad ("wifi" anywhere triggers)
20. **Wallpaper** — Uncommon, at the bottom

---

## Permissions

Actions that need runtime permissions use just-in-time prompting:

| Tool | Permission | When prompted |
|:-----|:-----------|:--------------|
| `send_sms` | `SEND_SMS` | First SMS attempt |
| `search_contact` | `READ_CONTACTS` | First contact search |
| `create_contact` | `WRITE_CONTACTS` | First contact creation |
| `make_call` | `CALL_PHONE` | First call attempt |

**Flow:** User says "text Mom hello" → PermissionHelper checks SEND_SMS → if missing, shows explanation in chat ("I need SMS permission...") → fires Android permission dialog → on grant, re-executes the stashed tool call → on deny, opens app settings.

**Special permissions** (not runtime, handled by each action internally):
- `set_brightness` / `toggle_rotation`: Needs WRITE_SETTINGS — opens the system settings page to enable
- `toggle_dnd`: Needs notification policy access — opens DND access settings

---

## JSON Tool Schema

Cloud LLMs output tools in this format:
```json
{"tool": "tool_name", "params": {"key": "value"}}
```

The JSON parser finds the first `{` to last `}` in the response, extracts `tool` and `params`, and validates against `KNOWN_TOOLS`. Unknown tools are ignored (treated as plain text).

**Current tool count:** 28

---

## Cloud LLM Reasoning

When a cloud model (Gemini, Claude, OpenAI) is connected, the LLM gets the full tool list, real-time device state, and instructions for reasoning through complex requests. This gives it three capabilities the keyword interceptor doesn't have:

### Follow-Up Questions

The LLM asks for missing info instead of guessing or failing:

| User says | LLM asks |
|:----------|:---------|
| "Send a text" | "Who should I text, and what should I say?" |
| "Set an alarm" | "What time?" |
| "Call someone" | "Who should I call?" |
| "Remind me" | "When should I remind you, and about what?" |

The LLM does NOT ask follow-ups when the intent is clear enough to act on. "Set alarm 7am" just sets the alarm.

### Tool Chaining

Some requests require multiple steps. The tool loop allows up to 3 chained tool calls per user message:

```
User: "What's John's number and text him I'll be late"
  │
  ▼ LLM outputs:
  {"tool":"search_contact","params":{"query":"John"}}
  │
  ▼ Result: "John Smith: 555-1234"
  │
  ▼ LLM sees result, outputs:
  {"tool":"send_sms","params":{"number":"555-1234","message":"I'll be late"}}
  │
  ▼ Result: "SMS sent to 555-1234."
  │
  ▼ LLM interprets: "Done — texted John Smith that you'll be late."
```

**Common chain patterns:**

| Request | Chain |
|:--------|:------|
| "Text John I'm running late" | search_contact → send_sms |
| "Call my dentist" | search_contact → make_call |
| "Set brightness to 40 and turn on DND" | set_brightness → toggle_dnd |
| "What's my battery and the time?" | battery → get_time (or answers from device state) |

### Graceful Limitations

When the LLM can't do something, it says so honestly and offers what it can:

| Request | Response |
|:--------|:---------|
| "What's the weather?" | "I don't have a weather tool yet, but it's 3:42 PM and your battery is at 73%." |
| "Play Despacito" | Opens Spotify (if it can infer that), or says "I can't pick a specific song, but I can open Spotify for you." |
| "Send an email" | "I can't send emails yet, but I can open Gmail for you." |

### How It Works (Architecture)

```
User input
  │
  ▼
Keyword Interceptor → if match → execute instantly (no LLM)
  │ no match
  ▼
Cloud LLM generates response (streaming)
  │
  ▼
Parse full response for JSON tool call
  │
  ├─ No tool found → display response as conversation
  │
  └─ Tool found → execute action
                    │
                    ▼
              Feed result back to LLM
                    │
                    ├─ LLM outputs another tool → execute (chain, up to 3x)
                    │
                    └─ LLM outputs text → display as natural interpretation
```

**Chain limit:** 3 tool calls per user message. This prevents runaway loops while allowing multi-step requests like contact lookup → call.

**Local model:** The on-device Qwen3.5 0.8B does NOT get the tool list and cannot chain. If the keyword interceptor misses, the local model responds conversationally only. Tool chaining is a cloud-only capability.

---

## Known Limitations

1. **Local model can't call tools.** The on-device Qwen3.5 0.8B gets a minimal system prompt with no tool list. If the keyword parser misses, the local model responds conversationally — it can't fall back to JSON tool output. Only cloud models (Gemini/Claude/OpenAI) get the full tool list in their system prompt.

2. **Flashlight state tracking is in-memory.** If the user toggles the flashlight from the system tray, LUI's state gets out of sync. "Turn on" might turn it off. Android doesn't provide a reliable callback for torch state across all devices.

3. **Alarm/timer intents surface the Clock app.** Android doesn't allow fully headless alarm creation. The Clock app UI flashes briefly. `EXTRA_SKIP_UI` is set to false because some devices ignore or mishandle the skip flag.

4. **SMS needs the number, not just a name.** When the interceptor captures "text Mom hello", it passes "mom" as the `number` param. SmsManager needs an actual phone number. Currently this will fail unless "Mom" is literally a phone number. The cloud model handles this better by resolving names first.

5. **Calendar "remind me" doesn't set a reminder.** "Remind me to call dentist at 2pm" creates a calendar event, not a system reminder. This is a UX expectation mismatch.

6. **Media controls need an active session.** "Play music" sends a media key event, but if no app has an active MediaSession, nothing happens. There's no way to start playback from scratch without opening a specific app.

7. **Contact resolution for calls requires READ_CONTACTS.** "Call Mom" will try to resolve via contacts, but if permission hasn't been granted yet, it falls back to opening the dialer. The permission prompt only fires for explicit `make_call` tool — not automatically for contact resolution.
