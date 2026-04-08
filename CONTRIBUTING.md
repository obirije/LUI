# Contributing to LUI

Thanks for your interest in contributing to LUI.

## Getting Started

1. Fork the repo
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Build and test on a physical Android device (emulators won't test voice, camera, or sensors)
4. Submit a PR

## Requirements

- JDK 17, Android SDK 35, NDK 28, CMake 3.31+
- Physical Android device (tested on Moto Edge 40 Neo, Android 15)
- For bridge testing: `pip install lui-bridge` or `npm install -g lui-bridge`

## What to Work On

Check [open issues](https://github.com/obirije/LUI/issues) or pick from the roadmap in the README. Good first issues are tagged.

If you're adding a **new tool**, you need to update:
- `ToolRegistry.kt` — tool definition with name, description, params
- `ActionExecutor.kt` — wire tool name to action handler
- `Interceptor.kt` — add to KNOWN_TOOLS set + keyword pattern (for offline use)
- Test both keyword path (offline) and cloud LLM path (Gemini/Claude)

## Code Style

- Kotlin, no Java
- No unnecessary abstractions — three similar lines > a premature helper
- No comments unless the logic isn't obvious
- Keep tool actions self-contained in their `*Actions.kt` file

## Commit Messages

- Imperative mood: "Add web search tool" not "Added web search tool"
- First line under 72 chars
- Body explains *why*, not *what* (the diff shows what)

## What NOT to Commit

- API keys, tokens, passwords, or personal data
- Model files (*.gguf) — they're gitignored
- Build artifacts

## License

By contributing, you agree that your contributions will be licensed under GPLv3.
