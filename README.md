# Keyflow

A native **Kotlin Android** piano-tiles game that turns a YouTube Music playlist into a path of levels. Keyflow imports playlists, downloads audio and generates charts **directly on the phone**, using runtime and request handling adapted from [YTDLnis](https://github.com/deniscerri/ytdlnis). No computer, server or API key is needed.

## Play

- **Playlists.** Paste a public or unlisted playlist link, or share one to Keyflow from YouTube Music. Each import becomes a new local playlist with its songs shuffled once; the order then stays fixed. Downloads, scores and unlocks are shared per song across playlists. Imports of more than 100 songs ask for confirmation, because of storage.
- **Journey.** Each song is a level on a winding map. Clear a level to unlock the next; locked covers are blurred and their titles partly masked. Long songs play one of up to five continuous, bar-aligned highlights of up to 2½ minutes, a different one from last time, or the full song if chosen in Settings.
- **Gameplay.** Tap tiles, hold green ribbons, follow blue slides, tap linked chords together, catch gold tiles for points and a life, and avoid bombs. New tile kinds appear gradually. You have eight lives: a miss costs one and a 25-hit streak earns one back. Combos raise a multiplier up to ×4. Hit 75% of the notes to clear a level; stars come from accuracy. Each play uses a new two-thumb lane layout with the same rhythm.
- **Arcade.** *Time Attack* (1, 2, 3 or 5 minutes) and *Endless* (lives carry over and each song is faster) play random 45-second parts of songs from the levels you have reached.
- **Progression.** Runs earn XP toward your player level, and playing on consecutive days builds a streak.
- **Timing.** After each song, Keyflow measures whether your hits leaned early or late and adjusts the timing offset, which differs between phones and headphones. It can also be set by hand.
- **Settings** cover song speed, note speed, level length, timing, vibration and app-wide storage, including whether *Download ahead* and *Download all* wait for Wi-Fi (on by default) or a charger. Your next two levels and Arcade's songs download on any connection. *Download queue* shows what is still to be downloaded and prepared, in order, with an estimate for each song. Each playlist has its own settings, under the gear on the Playlists screen: how many levels to keep ready, keeping its songs, downloading or removing them, renaming and deleting. A playlist whose next level isn't on the phone yet is greyed out with an estimate of when it can be played.
- Songs are prepared in a foreground service, so it continues with the screen off, and it eases off while you play. Prepared songs play offline. Three original piano pieces ship with the app.

Charts come from the human-chart-trained [Mapperatorinator-v32-mini](https://huggingface.co/OliBomby/Mapperatorinator-v32-mini) mania model, adapted to run locally and conditioned on beats from [Beat This!](https://github.com/CPJKU/beat_this). See [the chart model](docs/chart-model.md) and [beat tracking](docs/beat-tracking.md).

Each song has a 20-minute / 100 MB limit. Private playlists that need an account are not supported. Use audio you have permission to download and play. If YouTube blocks requests or changes, retry or use **Update downloader** in Settings, which verifies the official yt-dlp nightly's SHA-256 before installing it.

## Install

Download the signed APK from this private repository's [releases](https://github.com/oskarsss/PianoTiles/releases): `keyflow-X.Y.Z.apk` for **ARM64 phones**, or `keyflow-X.Y.Z-x86_64.apk` for **x86-64 emulators**, on Android 8.0 (API 26) or newer. The bundled models, Python, FFmpeg and QuickJS make each about 160 MiB.

Development releases don't keep data between versions: playlists, downloads, scores and settings may need to be cleared after an update.

## Develop

Requires JDK 21, Python 3, Android SDK platform 35 and build tools 36.0.0. Open the root in Android Studio, or use the Gradle wrapper:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build runs `tools/prepare_android_runtimes.py`, which downloads the two official YTDLnis 1.9.0 APKs, checks their pinned SHA-256 hashes and extracts only the Python, FFmpeg and QuickJS libraries into the ignored `app/src/main/jniLibs/`. The yt-dlp nightly 2026.10.10.233141 ships as an asset. Neither needs to be installed on the build computer.

[Architecture](docs/architecture.md) describes the packages, how a playlist becomes a level, what is kept ready and where data is stored.

### Test

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
python3 -m unittest discover -s tools -p 'test_*.py'
./gradlew :app:connectedDebugAndroidTest
```

Unit tests run on the JVM, with Robolectric for screens. They cover gameplay, timing, charts, the model frontends, playlists, downloads, storage and Arcade. Device tests play the built-in levels with real touch events against the media clock, run the native models and downloader, and play both Arcade modes offline. Optional live tests import a real playlist:

```bash
adb shell am instrument -w -r \
  -e playlistUrl 'https://music.youtube.com/playlist?list=PLA2wtd_DK6uw' \
  com.keyflow.pianotiles.test/androidx.test.runner.AndroidJUnitRunner
```

### Release

Bump `versionCode` and `versionName` in `app/build.gradle.kts`, commit, then publish a GitHub release whose tag matches `versionName` (for example `v1.6.2`). `.github/workflows/release.yml` then runs the tests and lint, builds the signed APKs with `tools/build_release.py`, and attaches the phone and emulator APKs, a source archive and `SHA256SUMS`. It needs the Actions secrets `KEYFLOW_KEYSTORE_BASE64`, `KEYFLOW_STORE_PASSWORD` and `KEYFLOW_KEY_PASSWORD`.

To sign locally, set `KEYFLOW_KEYSTORE`, `KEYFLOW_STORE_PASSWORD` and `KEYFLOW_KEY_PASSWORD` and run `./gradlew :app:assembleRelease`, or `python3 tools/build_release.py` from a clean, committed tree. Without them, release builds are unsigned. Release builds are shrunk with R8 and come as one APK per processor, in `app/build/outputs/apk/release/`.

### Tools

- `tools/prepare_android_runtimes.py` prepares the checksum-pinned native runtimes.
- `tools/prepare_rhythm_model.py` and `tools/prepare_chart_model.py` verify the bundled models, or reproduce their pinned conversions.
- `tools/evaluate_rhythm.py` scores beat tracking against human annotations.
- `tools/generate_demo.py` regenerates the original built-in songs and their charts.

## Source and credits

The app is GPL-3.0; see `LICENSE` and `THIRD_PARTY_NOTICES.md` for reused YTDLnis code, native runtime provenance, model licenses and the MIT particle adaptation. License texts ship in the APK, and releases include this project's source archive.

The project is independent of commercial Piano Tiles games. No commercial game's source, branding or audio is included.
