# Keyflow

A native **Kotlin Android** piano-tiles game that turns a YouTube Music playlist into a path of levels. A local Python companion imports the song list, prepares the first three songs with yt-dlp, extracts covers and metadata, and builds tile charts from audio onsets.

## Play

- Paste a public or unlisted playlist on first launch, or share a link to Keyflow from YouTube Music.
- Every level shows the source song artwork. Locked covers are blurred and dimmed; covers become clear when unlocked.
- Tap each of the four lanes when a tile's bottom edge reaches the glowing line. **Hold** the glowing sustained rails until their tails finish, and **slide** your finger along diagonal rails between lanes. Move across ordinary tiles to play a flowing drag phrase; empty dragging is forgiving. Multiple fingers are supported, with fading touch trails and hit particles.
- Hit at least 75% of notes and finish with fewer than eight misses to pass. Complete songs in playlist order to unlock the next level.
- Earn up to three stars from timing accuracy, build combos, and keep your best scores. Progress survives app restarts and switching playlists.
- The first three prepared songs download to the phone automatically. Downloaded tracks, charts, and covers work offline; further tracks download when selected.
- Play settings include 0.8× / 1× / 1.15× speed, a headphone timing offset, a **per-playlist preload count from 0 to 20** (default 3), and cache cleanup that preserves progress. Changing the preload count controls future automatic downloads; existing downloaded tracks remain until cleared.
- Three original synthesized piano sketches ship with the app, so no server is required to try the game.

## Start the companion

Requires Python 3.10+, **ffmpeg**, and a current **yt-dlp**. Using the Python environment below avoids outdated OS packages:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade -r companion/requirements.txt
python companion/server.py --host 0.0.0.0 --port 8765
```

In the app's **Companion connection**, enter `http://10.0.2.2:8765` for an Android emulator, or `http://YOUR_COMPUTER_LAN_IP:8765` for a phone on the same Wi-Fi. Paste the pairing code printed by the companion. If port 8765 is occupied, choose a different port and use the same port in the app.

The service defaults to loopback when `--host` is omitted. Every API and media request requires the pairing code. The code, playlist index, diagnostics, MP3s, covers, and charts stay in `companion/cache/`, which is excluded from Git. This is a local companion; use HTTPS if running it across an untrusted network. Only public/unlisted playlists are supported, with at most 200 unique tracks and 20 minutes per track. Use audio you have permission to download and play.

Downloads use the requested best-audio / MP3 quality 0 / metadata / embedded JPG thumbnail options. Internal filenames use video IDs so identical song titles cannot overwrite each other; artist and track names remain in the embedded metadata and level display. Album artwork comes from the source metadata; a video thumbnail is used when YouTube does not supply dedicated album art.

Generated charts use a lightweight mono energy-onset detector. Quiet ambient tracks fall back to an estimated rhythm grid. These are generated gameplay charts, not manually transcribed piano scores. Playback position drives both rendering and judgement so audio and tiles do not drift apart.

## Build and install

Open the root folder in Android Studio, or use the included Gradle wrapper. Requires JDK 21, Android SDK platform 35 and build tools 36.0.0. Minimum Android version is 8.0 (API 26).

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK runs independently of Android Studio and the companion once a song is downloaded. The GitHub release includes a signed APK. The release signing key and password are retained locally in the ignored `artifacts/` directory; keep them to sign compatible future updates.

To create another signed release, provide `KEYFLOW_KEYSTORE`, `KEYFLOW_STORE_PASSWORD`, and `KEYFLOW_KEY_PASSWORD`, then run `./gradlew :app:assembleRelease`. Without these environment variables, release builds are unsigned.

## Verify

```bash
python3 -m unittest discover -s companion -v
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

The device test plays the first two original songs with MotionEvents through the real touch handlers against the MediaPlayer clock: taps, holds, and guided slides. It checks pause/resume, three stars, sequential unlocks, and saved progress after activity recreation. An optional live companion test plays a cached playlist track and exercises failure. The default test sets the emulator's active library to the demo collection. Screenshots are saved in the app's external files directory under `evidence/`.

The companion tests cover authenticated HTTP, input validation, playlist order, prefetch limits, media ranges, and deterministic charts generated from actual audio. Game unit tests cover scoring, duplicate taps, missed notes, nearest-note matching, pass thresholds, and visual timing.

## Structure

- `app/` — Kotlin activity, Canvas rendering, audio-clock judgement, on-device storage, and companion client.
- `companion/server.py` — Python standard-library HTTP service; yt-dlp and ffmpeg run as subprocesses.
- `tools/generate_demo.py` — reproducible original demo music and charts.
- `THIRD_PARTY_NOTICES.md` — MIT source attribution for the adapted scheduler and particle effects.

The project is independent of the commercial Piano Tiles games. No commercial game's source, branding, or audio is included.
