# Ambient Sounds & Music — Wellness Mode

LUI bundles 11 looping tracks for wellness mode: 9 ambient/nature sounds and 2 calming music pieces. All tracks are public domain or algorithmically generated — compatible with CC-BY 4.0 dual-licensing for the Gemma 4 hackathon submission.

## What's bundled

All files live in `app/src/main/res/raw/`. Android `raw` resource names must be lowercase, no hyphens, so the `amb_` prefix is used.

### Ambient / nature

| File                      | Sound           | Source / license |
|---------------------------|-----------------|------------------|
| `amb_rain.ogg`            | Rain            | Wikimedia Commons `Rain.ogg` — PD-self |
| `amb_thunder.ogg`         | Thunderstorm    | Wikimedia Commons `Rain_and_thunder_(1).ogg` — public domain (PDSounds, author ezwa) |
| `amb_ocean.ogg`           | Ocean waves     | Wikimedia Commons `Bayfront.wav` — public domain |
| `amb_forest.ogg`          | Forest & birds  | Wikimedia Commons `Forest_lawn_creek.ogg` — public domain |
| `amb_fire.ogg`            | Fire crackling  | Wikimedia Commons `Campfire_sound_ambience.ogg` — public domain |
| `amb_wind.ogg`            | Wind            | Wikimedia Commons `Gentle_wind_after_shower_accompanied_by_thunders.ogg` — public domain |
| `amb_crickets.ogg`        | Night crickets  | Wikimedia Commons `Country_night_noise.ogg` — public domain |
| `amb_white_noise.ogg`     | White / pink noise | Algorithmically generated with ffmpeg `anoisesrc=c=pink` — no copyright |
| `amb_brown_noise.ogg`     | Brown noise     | Algorithmically generated with ffmpeg `anoisesrc=c=brown` — no copyright |

### Music

| File                      | Piece                    | Source / license |
|---------------------------|--------------------------|------------------|
| `amb_piano.ogg`           | Debussy — *Clair de Lune* | Wikimedia Commons 1905 piano solo recording — Creative Commons PD Mark 1.0 (Debussy died 1918) |
| `amb_meditation.ogg`      | Meditation bell          | Wikimedia Commons `Gong_or_bell_vibrant.ogg` — public domain (PDSounds, author stephan) |

## Encoding

All tracks are normalized to:

- **Format:** OGG Vorbis
- **Channels:** Mono
- **Sample rate:** 44.1 kHz
- **Bitrate:** ~96 kbps
- **Loop length:** 60 seconds (except `amb_piano.ogg` — full 4:36 Debussy piece; loops naturally)

Total bundle size: **~6.2 MB**.

## How sounds are chosen at runtime

Two paths:

1. **Explicit request** — user says "play rain" → Interceptor keyword regex routes directly to `play_relaxing_sound(type="rain")`. Model is bypassed.

2. **Model picks** — Gemma 4 reads the tool description (rain/crickets at night, forest in morning, brown noise for sleep/focus, piano for emotional calm…) plus live device context (time of day, current ring stress) and picks.

3. **Auto-pick in wellness mode** — if `start_wellness_mode` is called with no sound argument, LUI picks deterministically from:

| Context | Pick |
|---------|------|
| Stress 86+ (any time) | Piano (Clair de Lune) |
| Stress 75–85 (any time) | Rain |
| 22:00–05:59 | Brown noise |
| 06:00–08:59 | Forest |
| 09:00–11:59 | Piano |
| 12:00–15:59 | White noise |
| 16:00–18:59 | Ocean |
| 19:00–21:59 | Fire |

## Volume behaviour

Playback volume is time-aware on the MediaPlayer track itself (doesn't globally change system volume):

| Hour | Base volume |
|------|-------------|
| 22:00–05:59 (sleep) | 30% |
| 06:00–08:59 (morning) | 50% |
| 09:00–18:59 (daytime) | 60% |
| 19:00–21:59 (evening) | 45% |

Modifiers:

- **Wellness mode**: +8% (user explicitly wants to hear it)
- **White/brown noise**: −10% (pure noise carries further)
- Clamped to 15%–100%

If the system MUSIC stream is below 35% of max when playback starts, LUI bumps it to 35% so the track is audible, captures the prior value, and restores it when playback stops.

## Adding your own sounds

If you want to swap or extend the bundle:

1. Drop a new file into `app/src/main/res/raw/` with name pattern `amb_<key>.ogg` (lowercase, no hyphens — Android resource naming rules)
2. Re-encode to 60 s mono OGG Vorbis @ 96 kbps for consistency:
   ```bash
   ffmpeg -y -ss 0 -i input.flac -t 60 -ac 1 -c:a libvorbis -b:a 96k amb_<key>.ogg
   ```
3. Add an entry to the `Sound` enum in `app/src/main/java/com/lui/app/audio/AmbientSoundPlayer.kt`
4. Update the tool description in `ToolRegistry.kt` so the LLM knows about it
5. Add a matcher keyword in `Interceptor.kt` for local model routing

Missing files are handled gracefully — `list_relaxing_sounds` reports which entries have a bundled file vs which don't.

## Sourcing CC0 audio

Use sources whose output is PD or CC-BY compatible (**not CC-BY-SA** — the ShareAlike clause conflicts with the hackathon's CC-BY 4.0 winner license):

- [Wikimedia Commons](https://commons.wikimedia.org) — filter for public domain (PD-self, PD Mark 1.0, PDSounds origin)
- [freesound.org](https://freesound.org) — filter by CC0
- [archive.org](https://archive.org) — many PD recordings

Don't trust CDN-hosted "royalty-free" content without reading the actual license text — many platforms use their own custom licenses that are more restrictive than CC0.
