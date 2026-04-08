# LUI Privacy Policy

*Last updated: April 2026*

LUI ("Language User Interface") is an open-source Android launcher. This policy explains what data LUI accesses, how it's used, and what's sent where.

## Summary

- LUI stores API keys locally on your device in encrypted storage
- LUI does NOT collect, transmit, or sell personal data
- Voice/text queries may be sent to cloud LLM providers you configure (Gemini, Claude, OpenAI, Ollama)
- LUI does NOT have its own servers that receive your data (except the optional relay server you self-host)
- All features work locally unless you explicitly enable cloud services

## Data Stored on Device

| Data | Where | Purpose |
|------|-------|---------|
| Chat history | Room/SQLite database, app internal storage | Persistent conversations across restarts |
| API keys | Android EncryptedSharedPreferences (AES256-GCM) | Cloud LLM, TTS provider authentication |
| Notification digest | Room/SQLite database | The Bouncer — batched non-urgent notifications |
| Trigger rules | Room/SQLite database | Geofence and scheduled trigger definitions |
| Logs | Internal file storage (lui_log.txt) | Debug logging, auto-rotated at 5MB |
| Downloaded model | Internal file storage (models/) | On-device LLM for offline use |

**None of this data is transmitted to LUI developers or any third party.**

## Data Sent to Cloud Services (Only When You Enable Them)

### Cloud LLM (Gemini, Claude, OpenAI, Ollama)

When you configure a cloud LLM in Connection Hub:
- Your text/voice queries are sent to the selected provider's API
- Tool call results (battery level, notifications, etc.) are sent as conversation context
- Each provider has its own privacy policy:
  - [Google Gemini](https://policies.google.com/privacy)
  - [Anthropic Claude](https://www.anthropic.com/privacy)
  - [OpenAI](https://openai.com/privacy)
  - Ollama: self-hosted, data stays on your network

**LUI sanitizes API keys from all data sent to cloud providers.**

### Cloud Speech (Deepgram, ElevenLabs)

When you enable cloud speech:
- Audio/text is sent to the speech provider for TTS/STT processing
- [Deepgram Privacy Policy](https://deepgram.com/privacy)
- [ElevenLabs Privacy Policy](https://elevenlabs.io/privacy)

### Web Search & Browse

When you use web search or browse tools:
- Search queries are sent to DuckDuckGo (HTML endpoint, no tracking)
- Browse requests are sent through Jina Reader API (r.jina.ai)
- [DuckDuckGo Privacy Policy](https://duckduckgo.com/privacy)
- [Jina AI Privacy Policy](https://jina.ai/legal/)

### BYOS Bridge (Optional)

When you enable the bridge:
- A WebSocket server runs locally on your phone (port 8765)
- Only agents with your auth token can connect
- If relay is enabled, your phone connects outbound to the relay server you specify
- The default relay is self-hosted — we do not operate a relay that receives your data

## Permissions and Why LUI Needs Them

| Permission | Why LUI needs it |
|-----------|-----------------|
| **INTERNET** | Cloud LLM, TTS, web search, bridge relay |
| **RECORD_AUDIO** | Voice input (STT), wake word detection |
| **CAMERA** | Take photos for AI image analysis when you explicitly ask |
| **CALL_PHONE** | Make calls by voice command ("call Mum") |
| **SEND_SMS** | Send texts by voice command ("text John I'm on my way") |
| **READ_SMS** | Read recent messages when you ask ("read my texts") |
| **READ_CONTACTS** | Look up contacts by name for calls/texts |
| **WRITE_CONTACTS** | Create new contacts by voice |
| **READ_CALENDAR** | Read your schedule when you ask |
| **WRITE_CALENDAR** | Create events by voice |
| **ACCESS_FINE_LOCATION** | Get your location, calculate distances, navigation |
| **ACCESS_BACKGROUND_LOCATION** | Geofence triggers (fire actions when you enter/leave a location) |
| **ACTIVITY_RECOGNITION** | Step counter |
| **QUERY_ALL_PACKAGES** | App launcher — display and open installed apps |
| **FOREGROUND_SERVICE** | Wake word detection, bridge server |
| **BLUETOOTH_CONNECT** | List paired Bluetooth devices |
| **ACCESS_NETWORK_STATE** | Network status for ambient context |
| **SCHEDULE_EXACT_ALARM** | Scheduled trigger actions |
| **RECEIVE_BOOT_COMPLETED** | Re-register triggers after device restart |
| **PACKAGE_USAGE_STATS** | Screen time statistics |
| **ACCESSIBILITY_SERVICE** | Read screen content, tap buttons, type text in other apps. Only active when LUI is set as launcher. Used for the "screen pilot" feature — LUI can navigate other apps on your behalf when you ask. |

## Accessibility Service Disclosure

LUI uses Android's AccessibilityService API to:
- Read visible text on any app's screen (`read_screen` tool)
- Find and tap UI elements (`find_and_tap` tool)
- Type text into input fields (`type_text` tool)
- Scroll, press back/home buttons

This functionality is **only active when LUI is set as your default launcher.** It is used exclusively to enable voice-controlled navigation of apps on the user's behalf. No data from the accessibility service is transmitted off-device or stored beyond the current session.

LUI's accessibility service is NOT an assistive technology for users with disabilities. It is a general-purpose automation feature that allows the AI assistant to interact with apps.

## Children's Privacy

LUI is not directed at children under 13. If you use LUI in an educational context with children, you (the parent/teacher) are responsible for supervising the content and configuring appropriate cloud LLM providers with content filtering.

## Data Deletion

All LUI data is stored locally on your device. To delete:
- Uninstall the app (removes all data), or
- Clear app data in Android Settings → Apps → LUI → Storage → Clear Data

## Changes to This Policy

We may update this policy. Changes will be posted at this URL. The "last updated" date reflects the most recent revision.

## Contact

For privacy questions: https://github.com/obirije/LUI/issues
