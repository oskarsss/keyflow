# Keyflow

A native **Kotlin Android** piano-tiles game that turns a YouTube Music playlist into a path of levels. Keyflow imports playlists, downloads audio, and generates charts **directly on the phone**, using runtime and request handling adapted from [YTDLnis](https://github.com/deniscerri/ytdlnis). No computer, pairing code, or companion server is needed.

## Play

- Paste a public or unlisted playlist on first launch, or share a link to Keyflow from YouTube Music. Switch or add playlists from the playlist name at the top of the home screen. Each import copies up to 200 unique songs into a new local playlist and shuffles the level order once. That order stays fixed across restarts. Imports keep no link to the source playlist; importing the same link again creates a separate collection, numbered if the name is taken. Downloads, scores, and unlocks are reused per song.
- **Playlists:** the playlists screen shows how many songs of each playlist are downloaded. Download a whole playlist with one tap, or turn on *Download all songs* when adding one. The ⋮ menu renames or deletes a playlist; deleting removes downloads no other playlist uses and keeps scores. A song you open jumps ahead of the download queue, and its progress shows while you wait.
- **Journey:** each song is a level on a winding map. Clear a level to unlock the next. Locked covers are blurred and their titles partially masked (for example `Ru* Ra****`).
- **Gameplay:** tiles race down a perspective highway toward the strike line. Tap tiles, **hold** green ribbons, **follow** blue slides, tap linked **chords** of two to four tiles together, grab **gold** tiles for bonus points and a life back, and never touch **bombs**. New tile kinds appear gradually across levels and are introduced the first time you meet them.
- You have eight lives. Every miss costs one, and every 25-note streak earns one back. The combo raises a score multiplier up to ×4 (fever). Hit 75% of the notes to clear a level. Grades run from S to D, and stars come from accuracy.
- **Arcade:** *Time Attack* (1, 2, 3 or 5 minutes) and *Endless* (lives carry over and every song is faster) play random 45-second segments from all your playlists. Best results are kept per mode and duration. A few songs download before a run starts. Without imported playlists, the app warns you and offers the built-in songs.
- **Progression:** every run earns XP toward your player level, and playing on consecutive days builds a streak.
- **Settings:** song speed (0.8×, 1×, 1.15×), note speed, timing offset, vibration, how many upcoming levels to keep downloaded (0–20 per playlist), clearing downloaded songs, and updating the built-in downloader. Changes apply immediately.
- Downloads run in a visible foreground service while you leave the app or lock the phone. Cached audio, charts, and covers work offline. **Update downloader** checks the official yt-dlp nightly release and verifies its SHA-256 before updating the extractor independently of the APK.
- Three original synthesized piano sketches ship with the game for instant offline play.

Downloads use best audio, MP3 quality 0, embedded metadata, and embedded JPG artwork. Internal filenames use video IDs so duplicate titles cannot overwrite one another. Charts use streaming spectral analysis and a Kotlin implementation of the [SuperFlux onset-detection approach](https://librosa.org/doc/0.11.0/auto_examples/plot_superflux.html): tiles follow detected note and drum attacks, including syncopated notes and pitch changes that loudness alone misses. Tempo and tracked beats guide musical structure without snapping tile times to a beat grid. Every bar is ranked from calm to peak within the song, so quiet passages thin out to single notes, long holds or rests, loud ones fill in, accents become chords, and drops can hit with all four lanes. Changes in spectral pitch guide lane movement alongside repeating bar patterns, and each song gets its own mix of patterns, holds, and slides. Early levels cap chord size (two tiles on the first level, three on the next two). Arrhythmic audio falls back to an even grid. Songs that end in a long tile-free outro fade out a few seconds after the last tile. These are generated gameplay charts, not manually transcribed piano scores. Playback position drives rendering and judgement. Each tile starts with a fresh touch; continuing to drag after a hold or slide cannot trigger the next tile. Downloaded songs rebuild their charts with the new generator when needed. See [chart-generation notes](docs/chart-generation.md) for the research and limitations.

Public/unlisted playlists are supported up to 200 unique tracks, with a 20-minute / 100 MB source limit per song. Private playlists requiring account cookies are not supported. Use audio you have permission to download and play. YouTube can block requests or change extraction; retry or update the built-in downloader when necessary.

## Install and upgrade

Download the signed APK from this private repository's [releases](https://github.com/oskarsss/PianoTiles/releases). The APK supports **ARM64 phones and x86-64 emulators**, Android 8.0 (API 26) or newer. Bundled Python, FFmpeg, and QuickJS make it approximately 100 MB; the first import unpacks the runtime into app-private storage.

Version 1.4.1 is a development release with no backward data compatibility guarantee. Playlists, downloads, scores, progress, and settings may be wiped after any update during development. Existing downloads rebuild their charts when needed; uninstalling or clearing storage is optional. The release signing key/password remain in the ignored `artifacts/` directory.

## Build

Requires JDK 21, Python 3, Android SDK platform 35 and build tools 36.0.0. Open the root in Android Studio, or use the included Gradle wrapper:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The pre-build task runs `tools/prepare_android_runtimes.py`. It downloads the two official YTDLnis 1.9.0 APKs, verifies their pinned SHA-256 hashes, and extracts only Python/FFmpeg/QuickJS runtime libraries. Native bundles are ignored by Git and cached locally. The official yt-dlp nightly 2026.10.10.233141 executable is bundled as an asset. Neither yt-dlp nor FFmpeg needs to be installed on the build computer.

For release signing, set `KEYFLOW_KEYSTORE`, `KEYFLOW_STORE_PASSWORD`, and `KEYFLOW_KEY_PASSWORD`, then run `./gradlew :app:assembleRelease`. Without these variables, release builds are unsigned. `python3 tools/build_release.py` builds a signed APK, the committed source archive, and SHA-256 checksums. It uses the environment variables above in CI or the locally retained signing files on a developer machine, and requires a clean, committed working tree.

Publishing a GitHub release automatically runs `.github/workflows/release.yml`. The job checks out the release tag, runs JVM tests and lint, builds the signed APK, and attaches the APK, source archive, and `SHA256SUMS` to the release downloads. The tag must match `versionName` (for example `v1.3.0`). Configure repository Actions secrets `KEYFLOW_KEYSTORE_BASE64` (the base64-encoded keystore), `KEYFLOW_STORE_PASSWORD`, and `KEYFLOW_KEY_PASSWORD`. The workflow can also be run manually with an existing release tag to rebuild its downloads.

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

Install the debug APK and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` first. Live tests require internet access. JVM tests cover scoring, timing, lives and multipliers, tile variety, arcade segments, progression, URL validation, literal downloader arguments, deterministic audio-driven chart generation (attack alignment, syncopation, constant-volume pitch changes, accelerating tempo, rests in silence, denser loud sections, chords on drops, non-overlapping gestures), and level-based chord limits. Robolectric regression tests exercise independent shuffled imports, stable ordering after restarts, shared download reuse, recovery of interrupted downloads, progress-based preload windows, cache clearing, selection by playlist ID, playlist switching, renaming and deleting playlists, and locked-title/accessibility masking without an emulator.

## Source and credits

The app is GPL-3.0; see `LICENSE` and `THIRD_PARTY_NOTICES.md` for reused YTDLnis code, native runtime provenance, component source links, and the MIT tile/particle adaptations. License texts are included in the APK. Releases also provide this project's corresponding source archive and the upstream source/build references.

- `app/` — native interface, Canvas game, audio judgement, local playlists/cache, foreground download queue, and Android runtime integration.
- `tools/prepare_android_runtimes.py` — checksum-pinned official runtime preparation.
- `tools/generate_demo.py` — reproducible original demo audio/charts.
- `companion/` — retained desktop prototype and reference onset generator for development; **the Android app does not call it**. Its tests can be run with `python3 -m unittest discover -s companion -v` when system FFmpeg is installed.

The project is independent of commercial Piano Tiles games. No commercial game's source, branding, or audio is included.
