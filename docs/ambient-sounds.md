# Ambient Sounds for Wellness Mode

Drop CC0/royalty-free ambient loops here. File format: `.ogg` or `.mp3`.
Android `raw` resources can't contain hyphens/uppercase — names must match exactly.

## Expected files

| Filename                 | Sound           |
|--------------------------|-----------------|
| `amb_rain.ogg`           | Rain            |
| `amb_ocean.ogg`          | Ocean waves     |
| `amb_fire.ogg`           | Fire crackling  |
| `amb_wind.ogg`           | Wind            |
| `amb_forest.ogg`         | Forest / birds  |
| `amb_white_noise.ogg`    | White noise     |
| `amb_crickets.ogg`       | Night crickets  |

`.mp3` extensions are also accepted (e.g. `amb_rain.mp3`).

## Good properties for each file

- Length: 30 seconds to 2 minutes (MediaPlayer loops seamlessly)
- Size: 300 KB – 1.5 MB per file (keep APK small)
- Channels: mono is fine for ambient loops
- Bitrate: ~128 kbps is plenty

## Sourcing

Use CC0 / public-domain sources so the submission stays compatible with the hackathon's
CC-BY 4.0 winner license:

- [freesound.org](https://freesound.org) — filter by CC0
- [pixabay.com/sound-effects](https://pixabay.com/sound-effects/) — royalty-free
- [bbc sound effects archive](https://sound-effects.bbcrewind.co.uk/) — check licensing

Missing files are handled gracefully — the `list_relaxing_sounds` tool reports which
sounds are actually available, and others return a clear "not bundled yet" error.
