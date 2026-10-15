# Keyflow

A native **Kotlin Android** piano-tiles game that turns a YouTube Music playlist into a path of levels. Version 1.1 imports playlists, downloads audio, and generates charts **directly on the phone**, using runtime and request handling adapted from [YTDLnis](https://github.com/deniscerri/ytdlnis). No computer, pairing code, or companion server is needed.

## Play

- Paste a public or unlisted playlist on first launch, or share a link to Keyflow from YouTube Music.
- Use **Playlists** at the top of your journey to return to any saved playlist, see each collection’s progress, or add another. Importing another playlist keeps earlier scores and unlocks available.
- Every level previews its source song artwork. Locked covers are blurred and dimmed, and locked titles are partially masked (for example, `Ru* Ra****`). Titles and covers become clear when unlocked. Album artwork comes from the source metadata, with a video thumbnail as the fallback.
- Tap each of the four lanes when a tile reaches the glowing line. **Hold** sustained rails until their tails finish, and **slide** your finger along diagonal rails between lanes. Drag across ordinary tiles for flowing phrases. Multiple fingers, touch trails, hit particles, and haptics are supported.
- Hit at least 75% of notes and finish with fewer than eight misses to pass. Complete songs in playlist order to unlock the next level.
- Earn up to three stars from timing accuracy and keep your best scores. Progress survives restarts and playlist changes.
- The first three songs download automatically by default. **Play & download settings** controls a **per-playlist preload count of 0–20**. Later songs download when selected. Changing the count controls future automatic downloads; existing downloaded tracks stay until cleared.
- Downloads run in a visible foreground service while you leave the app or lock the phone. Cached audio, charts, and covers work offline.
- Settings also include 0.8× / 1× / 1.15× speed, a headphone timing offset, and cache cleanup that preserves scores and unlocks.
- **Built-in downloader → Update downloader** checks the official yt-dlp nightly release and verifies its SHA-256 before updating the extractor independently of the APK.
- Three original synthesized piano sketches ship with the game for instant offline play.

Downloads use best audio, MP3 quality 0, embedded metadata, and embedded JPG artwork. Internal filenames use video IDs so duplicate titles cannot overwrite one another. Charts use streaming mono energy-onset detection and generate taps, holds, and slides; sparse audio falls back to an estimated rhythm grid. These are generated gameplay charts, not manually transcribed piano scores. Playback position drives rendering and judgement.

Public/unlisted playlists are supported up to 200 unique tracks, with a 20-minute / 100 MB source limit per song. Private playlists requiring account cookies are not supported. Use audio you have permission to download and play. YouTube can block requests or change extraction; retry or update the built-in downloader when necessary.

## Install and upgrade

Download the signed APK from this private repository's [releases](https://github.com/oskarsss/PianoTiles/releases). The APK supports **ARM64 phones and x86-64 emulators**, Android 8.0 (API 26) or newer. Bundled Python, FFmpeg, and QuickJS make it approximately 100 MB; the first import unpacks the runtime into app-private storage.

Version 1.1.1 uses the same signing key as earlier releases, so it can be installed over the existing app while retaining progress. The playlist picker also exposes collections imported before this update. Existing downloaded audio and charts are reused, and older playlist indexes migrate locally. The release signing key/password remain in the ignored `artifacts/` directory; retain them for compatible future updates.

## Build

Requires JDK 21, Python 3, Android SDK platform 35 and build tools 36.0.0. Open the root in Android Studio, or use the included Gradle wrapper:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The pre-build task runs `tools/prepare_android_runtimes.py`. It downloads the two official YTDLnis 1.9.0 APKs, verifies their pinned SHA-256 hashes, and extracts only Python/FFmpeg/QuickJS runtime libraries. Native bundles are ignored by Git and cached locally. The official yt-dlp nightly 2026.10.10.233141 executable is bundled as an asset. Neither yt-dlp nor FFmpeg needs to be installed on the build computer.

For release signing, set `KEYFLOW_KEYSTORE`, `KEYFLOW_STORE_PASSWORD`, and `KEYFLOW_KEY_PASSWORD`, then run `./gradlew :app:assembleRelease`. Without these variables, release builds are unsigned. `tools/build_release.py` uses the locally retained signing files and writes a versioned APK and checksum.

## Verify

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

The default device test completes the first two original levels with touchscreen MotionEvents through the real handlers against the MediaPlayer clock. It checks taps, holds, slides, pause/resume, three stars, sequential unlocks, and saved progress after activity recreation. Screenshots are saved under the app's external files `evidence/` directory. The default test selects the demo collection in the test device.

Optional live tests import a playlist with the bundled Android engine, download MP3/artwork/charts, verify background foreground-service downloads and the preload limit, then play the downloaded song and exercise failure:

```bash
adb shell am instrument -w -r \
  -e playlistUrl 'https://music.youtube.com/playlist?list=PLA2wtd_DK6uw' \
  com.keyflow.pianotiles.test/androidx.test.runner.AndroidJUnitRunner
```

Install the debug APK and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` first. Live tests require internet access. JVM tests cover scoring, timing, progression, URL validation, literal downloader arguments, deterministic streaming audio analysis, and non-overlapping gestures. Robolectric regression tests exercise legacy progress, adding a second collection, selecting earlier playlists, activity restarts, and locked-title/accessibility masking without an emulator.

## Source and credits

The app is GPL-3.0; see `LICENSE` and `THIRD_PARTY_NOTICES.md` for reused YTDLnis code, native runtime provenance, component source links, and the MIT tile/particle adaptations. License texts are included in the APK. Releases also provide this project's corresponding source archive and the upstream source/build references.

- `app/` — native interface, Canvas game, audio judgement, local playlists/cache, foreground download queue, and Android runtime integration.
- `tools/prepare_android_runtimes.py` — checksum-pinned official runtime preparation.
- `tools/generate_demo.py` — reproducible original demo audio/charts.
- `companion/` — retained desktop prototype and reference onset generator for development; **the Android app does not call it**. Its eight tests can be run with `python3 -m unittest discover -s companion -v` when system FFmpeg is installed.

The project is independent of commercial Piano Tiles games. No commercial game's source, branding, or audio is included.
