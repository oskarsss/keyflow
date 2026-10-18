# Architecture

Keyflow is one Android app module written in Kotlin with no UI framework: views are built in code
and the game draws on a `Canvas`. Everything runs on the phone.

## Source layout

`app/src/main/java/com/keyflow/pianotiles/`

| Package | What it holds |
| --- | --- |
| *(root)* | `MainActivity`, which owns the app's state and lifecycle, and one file per screen. Screens are `MainActivity` extension functions: `AddPlaylistScreen`, `PlaylistsScreen` (including each playlist's settings), `HomeScreen`, `ArcadeScreen`, `SettingsScreen`, `PlayScreen` (loading, play, pause, results) and shared `Scaffolding`. |
| `game` | Gameplay: `GameSession` (rules, scoring, lives), `GameView` (the frame loop and touch), `Highway` (perspective, scenery and drawing primitives), `NoteRenderer` (tiles, holds and slides), `HitFeedback` (flashes, banners and haptics), `GameHud` (score bar, countdown and ending), `GameClock` (audio clock), `TimingCalibration`, `TileVariety` (lane layouts and decoration), `HitParticles`. |
| `chart` | Turning audio into a level: `MusicFeatures` and `BeatThis` (beats), `ChartAudio`, `ManiaTokens` and `ManiaChartModel` (tile placement), `QuickChart` (a level from the beat alone), `ThumbChart` (two-thumb gate), `ChartStructure` and `LevelExcerpt` (sections, journey highlight), `PreparedChart` (saved format). |
| `library` | Playlists and downloads: `OnDeviceLibrary` (playlists, downloads, full and quick levels, storage cleanup), `LibraryStore` (files, progress, settings), `PreparationPlan` (what is wanted, in order), `Preparation` (each worker's next song, the waiting song, the queues, readiness and download conditions), `PreparationTime` (learned times and the two workers' schedule), `DownloadService` (the download and level workers), `PlaylistUrl`, `SongTitle`, `Covers`. |
| `arcade` | Endless and Time Attack: `Arcade` (runs, song pool, buffer rules) and `ArcadeSongs` (song choice and play history). |
| `ui` | `Ui` (palette and view helpers), `Glyph` (icons), `TileArtwork`, `Confetti`. |
| `downloader` | yt-dlp, Python and FFmpeg runtime, adapted from YTDLnis. |

Unit tests in `app/src/test` mirror these packages; Robolectric runs the screen tests without a
device. `app/src/androidTest` holds device tests that need the native runtime or real audio.

## From playlist to level

1. **Import.** On the first start, adding a playlist is the only way in. Once it is saved, the
   warm-up page offers the built-in songs while the worker prepares its first levels, and their
   home screen shows when the playlist is ready. yt-dlp reads the playlist's song list. Valid, unique songs are shuffled once into a
   new playlist file. Songs already on the phone are reused, with their downloaded titles.
2. **Plan.** Playlists and Arcade say which songs they want; nothing keeps a queue of requests.
   `PreparationPlan` orders every wanted song in steps:
   1. the song the player opened and is waiting for, and, while an Arcade run is played with no
      fresh song ready, the run's next song;
   2. every playlist's next level, then every playlist's locked level after that;
   3. Arcade's fresh songs;
   4. the rest of each playlist's *Download ahead* window;
   5. playlists set to download every song.
   The open playlist goes first within a step, and a song appears once.
   `DownloadService` runs two workers in a foreground service. One downloads the wanted songs that
   aren't on the phone; the other makes full levels for wanted songs that are downloaded but don't
   have one. Each reads a fresh plan before every song, so neither waits for the other: levels
   are made while the next songs download, and without a connection. A finished download wakes
   the level worker. While either works, the service keeps the processor awake, and Wi-Fi at
   full speed during a download, so locking the phone doesn't stall them; each song renews the
   hold for at most 30 minutes, and it ends when both are done. Android allows this kind of
   service six hours a day; when they are used up the workers stop after their current step and
   the service ends at once, and opening the app starts them again. The service stops when both are done; `Preparation.wake` starts it when
   something wanted is missing, and again when the phone gets a connection, Wi-Fi or a charger
   while the app is open. A plan reads each playlist only after it changes and checks which songs
   are downloaded, playable and fully prepared from one listing of the songs folder, so large
   playlists cost little to plan.
   Steps 4 and 5 download only on Wi-Fi (an unmetered network) by default; a charger, when the
   player asks for one, holds back both their downloads and their levels.
   A song that failed waits 15 minutes before it is tried again (one minute when the connection
   dropped). A song YouTube won't serve at all (unavailable, private, removed, blocked in the
   country, age-restricted or members-only, but not its "try again later" rate limit) is taken
   out of every playlist so it can't hold up a journey, remembered so imports leave it out, and
   named once in a dialog on the home screen. A song that fails on its own is passed over; two lost connections in a row end the
   download worker's pass. A level being made gives way to a downloaded song from step 1, or,
   from steps 3–5, to a playlist's downloaded next or locked level, such as the new locked level
   after a clear; from steps 4–5 it also stops when the charger goes. Its download stays.
   While a game is on screen, the level worker rests as long as each step of the analysis took,
   so the game has the phone to itself half the time. Songs from step 1 aren't held back.
3. **Prepare.** `OnDeviceLibrary.download` fetches the audio and cover (35% of the progress bar)
   into a temporary folder and moves them into the songs folder, which marks the song downloaded;
   the downloaded title, artist and length go to every playlist that has the song.
   `OnDeviceLibrary.prepare` then finds the beats (15%) and runs the chart model (the rest).
   Progress stays in memory; the playlist file is saved only when a song's status changes. The models are stored uncompressed in the APK
   and read from it in place, and `ModelSessions` keeps them loaded while songs follow one
   another. The chart, with the sections and bars
   its highlights are cut from, is written to a temporary file and renamed into place, so a song
   counts as ready only when its audio and a level exist. Opening a level reads that file; no
   model runs at play time.
   **Quick levels.** While a downloaded song's full level is still being made, the loading screen
   offers *Play now with a quick level*. `QuickChart` treats every sixteenth of the gameplay beat
   as a candidate tile and describes it by its onset strength in six mel bands (each against the
   75th percentile of the six seconds around it), its place in the bar, its bar's loudness and
   how much it stands out from its neighbours. Two logistic classifiers distilled from the full
   model's levels on the chart benchmark pick the tiles (strongest first, at least 170 ms apart)
   and which become chords; holds come where the harmony and loudness stay steady until the
   hand's next tile, hands take turns and the lane within a hand follows the melody up or down.
   On held-out benchmark songs it reproduces about 73% of the full level's tiles (the earlier
   beat-only quick level managed 43%), and about 84% on songs charted on a phone. It needs only the beat, so it
   takes seconds; when the level worker has already found this song's beat, the quick level
   reuses it and is ready at once. It is saved beside the song and counts as ready everywhere,
   Arcade included, while the song stays in the level worker's queue; the full level replaces it
   and deletes it.
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
  screen dims Play. Both say when it will be playable, from the two workers' schedule, and the
  home screen offers a quick level once the song is downloaded. `PreparationTime` learns from finished songs how long a download takes on
  this phone and how long charting takes per second of audio, so long songs count for more and a
  kept download counts only its chart.
- *Settings › Song queues* lists the two workers' queues: songs still to download, and
  downloaded songs whose full level is still to make, noting those with a quick level. Each is
  in its worker's order with the song in progress first, with its playlist, why it is wanted and
  when its part will be done. `PreparationTime.schedule` runs both queues side by side, so a
  level starts no earlier than its download ends. Songs held back (failed, or waiting for Wi-Fi,
  a charger or a connection) come last with the reason. Locked levels keep their masked titles.
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
| `files/songs/<songId>.mp3`, `.jpg` | A downloaded song and its cover. Built-in songs use `.wav` copied from assets. |
| `files/songs/<songId>.chart.json`, `.quick.json` | The song's full level, or its quick level until the full one is made. |
| `cache/downloads/<songId>/` | A download in progress, and the log of a failed one. |
| Preferences `keyflow` | Settings, the selected playlist, per-song progress (`song.<id>.completed`, `.stars`, `.score`, `.lastPlayed`, `.highlight` for the last cut played), XP, streak, arcade bests and history, the learned download time and chart speed (`preparation.downloadMs`, `preparation.chartMsPerSecond`), and the Wi-Fi and charger conditions (`downloads.wifiOnly`, `downloads.chargingOnly`). |

During development, data from an earlier build is not migrated (see `AGENTS.md`). A change to the
chart format or a setting's storage can require clearing the app's data.
