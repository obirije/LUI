# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in LUI, **do not open a public issue.** Instead, email security@lui.app (or open a private security advisory on GitHub).

Include:
- Description of the vulnerability
- Steps to reproduce
- Impact assessment

We'll respond within 48 hours and work on a fix before public disclosure.

## Security Model

LUI handles sensitive device access. Here's how it's designed:

### API Keys & Credentials
- Stored in Android EncryptedSharedPreferences (AES256-GCM)
- Never logged (LuiLogger sanitizes keys, tokens, and bearer strings)
- Never sent to cloud LLMs in tool results

### Bridge (MCP WebSocket — Local)
- Auth token required for all connections
- Three permission tiers: READ_ONLY, STANDARD, FULL
- Restricted tools require on-device approval prompt
- Rate limiting: 10 requests/second, max 3 concurrent connections

### Relay Server (Remote Access)
- Auth token sent as first WebSocket message — never in URLs or server logs
- Rate limiting and connection limits enforced
- No messages stored — pure pass-through proxy
- Unauthenticated connections auto-closed
- TLS required in production (`wss://`)

### Sensitive Tools
- SMS, calls, screen control require runtime permission + confirmation
- Accessibility service only active when LUI is the launcher
- Camera only triggered by explicit user request

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest  | Yes       |
| Older   | Best-effort |
