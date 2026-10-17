# Architecture

Keyflow is one Android app module written in Kotlin with no UI framework: views are built in code
and the game draws on a `Canvas`. Everything runs on the phone.

## Source layout

`app/src/main/java/com/keyflow/pianotiles/`

| Package | What it holds |
| --- | --- |
| *(root)* | `MainActivity`, which owns the app's state and lifecycle, and one file per screen. Screens are `MainActivity` extension functions: `AddPlaylistScreen`, `PlaylistsScreen` (including each playlist's settings), `HomeScreen`, `ArcadeScreen`, `SettingsScreen`, `PlayScreen` (loading, play, pause, results) and shared `Scaffolding`. |
| `game` | Gameplay: `GameSession` (rules, scoring, lives), `GameView` (the frame loop and touch), `Highway` (perspective, scenery and drawing primitives), `NoteRenderer` (tiles, holds and slides), `HitFeedback` (flashes, banners and haptics), `GameHud` (score bar, countdown and ending), `GameClock` (audio clock), `TimingCalibration`, `TileVariety` (lane layouts and decoration), `HitParticles`. |
| `chart` | Turning audio into a level: `MusicFeatures` and `BeatThis` (beats), `ChartAudio`, `ManiaTokens` and `ManiaChartModel` (tile placement), `ThumbChart` (two-thumb gate), `ChartStructure` and `LevelExcerpt` (sections, journey highlight), `PreparedChart` (saved format). |
| `library` | Playlists and downloads: `OnDeviceLibrary` (playlists, download and preparation, storage cleanup), `LibraryStore` (files, progress, settings), `PreparationPlan` (what is wanted, in order), `Preparation` (the waiting song, readiness and download conditions), `PreparationTime` (learned preparation times), `DownloadService` (the one preparation worker), `PlaylistUrl`, `SongTitle`, `Covers`. |
| `arcade` | Endless and Time Attack: `Arcade` (runs, song pool, buffer rules) and `ArcadeSongs` (song choice and play history). |
| `ui` | `Ui` (palette and view helpers), `Glyph` (icons), `TileArtwork`, `Confetti`. |
| `downloader` | yt-dlp, Python and FFmpeg runtime, adapted from YTDLnis. |

Unit tests in `app/src/test` mirror these packages; Robolectric runs the screen tests without a
device. `app/src/androidTest` holds device tests that need the native runtime or real audio.

## From playlist to level

1. **Import.** yt-dlp reads the playlist's song list. Valid, unique songs are shuffled once into a
   new playlist file. Songs already on the phone are reused, with their downloaded titles.
2. **Plan.** Playlists and Arcade say which songs they want; nothing keeps a queue of requests.
   `PreparationPlan` orders every wanted song in steps:
   1. the song the player opened and is waiting for, and, while an Arcade run is played with no
      fresh song ready, the run's next song;
   2. every playlist's next level, then every playlist's locked level after that;
   3. Arcade's fresh songs;
   4. the rest of each playlist's *Download ahead* window;
   5. playlists set to download every song.
   The open playlist goes first within a step, and a song appears once. Steps 4 and 5 wait for
   Wi-Fi (an unmetered network) by default, and for a charger when the player asks for that.
   `DownloadService` is the one worker. It runs in a foreground service, reads a fresh plan before
   each song and stops when nothing wanted is missing; `Preparation.wake` starts it when something
   is, and again when the phone gets a connection, Wi-Fi or a charger while the app is open. A
   plan reads each playlist only after it changes and checks which songs are ready from one
   listing of the songs folder, so large playlists cost little to plan.
   A song that failed waits 15 minutes before it is tried again (one minute when the connection
   dropped). A song that fails on its own is passed over; two lost connections in a row end the
   worker's pass. A song being charted gives way to a song from step 1, or, from steps 3–5, to a
   playlist's missing next or locked level, such as the new locked level after a clear; from
   steps 4–5 it also stops when Wi-Fi or the charger goes. Its finished download is kept.
   While a game is on screen, the worker rests as long as each step of the analysis took, so the
   game has the phone to itself half the time. Songs from step 1 aren't held back.
3. **Prepare.** `OnDeviceLibrary.cacheSong` downloads the audio (35% of the progress bar), finds
   the beats (15%), then runs the chart model (the rest). Progress stays in memory; the playlist
   file is saved only when a song's status changes. The models are stored uncompressed in the APK
   and read from it in place, and `ModelSessions` keeps them loaded while songs follow one
   another. The chart, with the sections and bars
   its highlights are cut from, is written to a temporary file and renamed into place before the audio is moved in, so a song
   counts as ready only when both exist. Opening a level reads that file; no model runs at play
   time.
4. **Play.** `PlayScreen` builds a `GameSession` from the chart, applies a fresh lane layout and
   decoration, and starts `GameView` on a `MediaPlayer`.

## What is kept ready

Whatever the plan wants is kept: every playlist's next and locked levels and its *Download ahead*
window (2–20 levels, 5 by default), Arcade's songs, and every song of a playlist set to download
them all.

- Arcade keeps three songs that it hasn't played recently ready. A song played goes stale, so each
  one played adds a new one until the songs it may use are all on the phone. Arcade only uses
  Journey levels the player has reached, borrowing the nearest locked ones until six are reached.
  It picks among the three songs it played longest ago and remembers its last 40. A playlist's
  next level counts as one of the three, since it is on its way anyway.
- A playlist whose next level isn't ready is greyed out on the Playlists screen, and its home
  screen dims Play. Both say when it will be playable: the time left for every song ahead of that
  level in the plan. `PreparationTime` learns from finished songs how long a download takes on
  this phone and how long charting takes per second of audio, so long songs count for more and a
  kept download counts only its chart.
- *Settings › Download queue* lists every wanted song that isn't on the phone yet, in the
  worker's order with the song in progress first, each with its playlist, why it is wanted and
  when it will be ready. Songs held back (failed, or waiting for Wi-Fi, a charger or a
  connection) come last with the reason. Locked levels keep their masked titles.
- Smart storage removes songs not played within the retention period (30 days by default). It
  spares the playing song, every song the plan wants and playlists set to *Keep downloaded songs*.
  Scores, unlocks and playlists are never removed.
- Playlists and charts are written to a temporary file, synced to disk and renamed, so a crash or
  power cut can't leave one empty.

## Journey highlights

A song longer than 2:30 plays a bar-aligned highlight. `LevelExcerpt` scores every cut of up to
2:30 by its sections' intensity and variety, and keeps up to five that score within 0.1 of the
best and start at least 15 seconds apart. They are chosen from the chart's saved sections and bars
when the level opens, and each play takes a random one other than the last.

## Timing

`MediaPlayer` reports its position in coarse steps. `SteadyPosition` follows it with a clock that
runs at the playback speed and eases toward each report. It holds until the reported position
first moves, so tiles don't run ahead before the speaker plays. When a level starts, `Handover`
joins the countdown's timeline to the music's by easing the tiles' speed, so they never jump.
A song whose audio starts up to 1.5 s late, as the first song of a run can, is eased the same way.
Taps are judged at the moment the finger touched the screen. The timing offset is learned from
the median lean of the player's hits after each song with at least 20 hits.

## Stored data

| Where | What |
| --- | --- |
| `files/playlists/<id>.json` | One playlist: title, shuffled songs with their status, `preloadCount`, `keepDownloads`, `downloadAll`. |
| `files/songs/<songId>.mp3`, `.chart.json`, `.jpg` | A prepared song. Built-in songs use `.wav` copied from assets. |
| `cache/downloads/<songId>/` | A download in progress. A `complete` file marks audio that is kept for a retry. |
| Preferences `keyflow` | Settings, the selected playlist, per-song progress (`song.<id>.completed`, `.stars`, `.score`, `.lastPlayed`, `.highlight` for the last cut played), XP, streak, arcade bests and history, the learned download time and chart speed (`preparation.downloadMs`, `preparation.chartMsPerSecond`), and the Wi-Fi and charger conditions (`downloads.wifiOnly`, `downloads.chargingOnly`). |

During development, data from an earlier build is not migrated (see `AGENTS.md`). A change to the
chart format or a setting's storage can require clearing the app's data.
