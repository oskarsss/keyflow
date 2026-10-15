# Keyflow

A native **Kotlin Android** piano-tiles game that turns a YouTube Music playlist into a path of levels. Keyflow imports playlists, downloads audio, and generates charts **directly on the phone**, using runtime and request handling adapted from [YTDLnis](https://github.com/deniscerri/ytdlnis). No computer, pairing code, or companion server is needed.

## Play

- Paste a public or unlisted playlist on first launch, or share a link to Keyflow from YouTube Music. Switch or add playlists from the playlist name at the top of the home screen. Each import copies all unique songs into a new local playlist and shuffles the level order once. That order stays fixed across restarts. Imports keep no link to the source playlist; importing the same link again creates a separate collection, numbered if the name is taken. Downloads, scores, and unlocks are reused per song.
- **Playlists:** select, rename or delete collections from the playlists screen. Storage counts and download controls live only in Settings. Music is prepared automatically in the background; a song you open takes priority over background work.
- **Journey:** each song is a level on a winding map. Long songs default to a continuous, bar-aligned highlight of up to 2½ minutes, favoring active musical sections. Choose **Full song** in Settings to keep the complete track. Clear a level to unlock the next. Locked covers are blurred and their titles partially masked (for example `Ru* Ra****`).
- **Gameplay:** tiles race down a perspective highway toward the strike line. Tap tiles, **hold** green ribbons, **follow** blue slides, tap linked two-tile **chords** together, grab **gold** tiles for bonus points and a life back, and never touch **bombs**. New tile kinds appear gradually across levels and are introduced the first time you meet them. The fixed green strike band marks the actual perfect window; moving road stripes are gone. Slides stay within one side of the screen, show a white guide ring, and tolerate brief slips. Each play uses a new lane layout while keeping musical timings and repeated phrase patterns intact. Clear linked tiles within 65 ms of one another to earn a synchronization bonus (100 points for a double, scaled by chord size and multiplier) with a gold burst animation.
- You have eight lives. Every miss costs one, and every 25-note streak earns one back. The combo raises a score multiplier up to ×4 (fever). Hit 75% of the notes to clear a level. Grades run from S to D, and stars come from accuracy.
- **Arcade:** *Time Attack* (1, 2, 3 or 5 minutes) and *Endless* (lives carry over and every song is faster) play random 45-second segments from all your playlists. Best results are kept per mode and duration. Runs start from available music immediately. A background buffer targets two upcoming random songs and replenishes while playing; without internet, runs continue using music already on the phone, repeating when necessary. Without imported playlists, the app warns you and offers the built-in songs.
- **Progression:** every run earns XP toward your player level, and playing on consecutive days builds a streak.
- **Settings:** song speed (0.8×, 1×, 1.15×), note speed, journey level length, timing offset, vibration, how many upcoming levels to keep downloaded (0–20 per playlist), clearing downloaded songs, smart storage management, and updating the built-in downloader. Smart storage defaults to 30 days and can be disabled or set to 1–365 days. Cleanup runs in the background on launch/resume, preserves scores and playlists, and protects the playing song and upcoming arcade music. Changes apply immediately.
- Downloads run in a visible foreground service while you leave the app or lock the phone. Cached audio, charts, and covers work offline. **Update downloader** checks the official yt-dlp nightly release and verifies its SHA-256 before updating the extractor independently of the APK.
- Three original synthesized piano sketches ship with the game for instant offline play.

Downloads use best audio, MP3 quality 0, embedded metadata, and embedded JPG artwork. Internal filenames use video IDs so duplicate titles cannot overwrite one another. Charts combine the bundled [Beat This!](https://github.com/CPJKU/beat_this) neural beat/downbeat tracker with SuperFlux attack detection. Learned musical pulse anchors the rhythm; audible attacks provide exact tile times. Recurrent harmony, timbre and rhythm identify phrase families, so repeated sections reuse a recognizable motif. Section intensity controls density, accents become doubles, and stable sustained tones can become holds or short slides. A final two-thumb planner prevents three/four-tile chords, crossing slides, and simultaneous demands on a busy hand. Very fast and compound-meter pulses are interpreted at a playable musical level. Scroll speed follows the whole song's tempo while song playback speed remains independently adjustable.

Analysis runs locally on background workers; no audio is uploaded and no model download is required. A 100-track annotated GTZAN-mini comparison improved beat F1 from 80.7% to 88.5% (70 ms tolerance; this measures beat detection, not human-rated chart quality). These are automatically generated gameplay charts, not piano transcription or hand-authored commercial maps. Playback position drives rendering and judgement. Each tile starts with a fresh touch; continuing to drag after a hold or slide cannot trigger the next tile. Tapping a visible tile too early removes it and costs one life, without another penalty later. Existing songs rebuild their charts with version 5 when needed. See [chart-generation notes](docs/chart-generation.md) for algorithms, reproducible measurements and limitations.

Public/unlisted playlists have no song-count cap. Imports with more than 100 unique playable songs ask for confirmation because keeping a large music library can use substantial phone storage. Only the next few songs are prepared initially. Each song still has a 20-minute / 100 MB source limit. Private playlists requiring account cookies are not supported. Use audio you have permission to download and play. YouTube can block requests or change extraction; retry or update the built-in downloader when necessary.

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

Install the debug APK and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` first. Live tests require internet access. JVM tests cover scoring, timing, lives and multipliers, tile variety, arcade segments, progression, URL validation, literal downloader arguments, deterministic audio-driven chart generation (attack alignment, syncopation, constant-volume pitch changes, accelerating tempo, rests in silence, denser loud sections, chords on drops, non-overlapping gestures), two-thumb limits, sustained-tone detection, repeated phrase consistency, compound-meter handling, frontend reference parity, synchronized chord bonuses, fresh lane layouts with safe slide paths, and arcade buffer replenishment. Storage tests cover age-based eviction, recent plays, fresh downloads, shared songs, active playback protection, and keeping cache details confined to Settings. Robolectric regression tests exercise independent shuffled imports, stable ordering after restarts, shared download reuse, recovery of interrupted downloads, progress-based preload windows, cache clearing, selection by playlist ID, playlist switching, renaming and deleting playlists, and locked-title/accessibility masking without an emulator.

## Source and credits

The app is GPL-3.0; see `LICENSE` and `THIRD_PARTY_NOTICES.md` for reused YTDLnis code, native runtime provenance, component source links, and the MIT tile/particle adaptations. License texts are included in the APK. Releases also provide this project's corresponding source archive and the upstream source/build references.

- `app/` — native interface, Canvas game, audio judgement, local playlists/cache, foreground download queue, and Android runtime integration.
- `tools/prepare_android_runtimes.py` — checksum-pinned official runtime preparation.
- `tools/prepare_rhythm_model.py` — verifies or restores the checksum-pinned neural rhythm model.
- `tools/evaluate_rhythm.py` — optional evaluation against external human beat annotations.
- `tools/generate_demo.py` — reproducible original demo audio/charts.
- `companion/` — retained desktop prototype and reference onset generator for development; **the Android app does not call it**. Its tests can be run with `python3 -m unittest discover -s companion -v` when system FFmpeg is installed.

The project is independent of commercial Piano Tiles games. No commercial game's source, branding, or audio is included.
