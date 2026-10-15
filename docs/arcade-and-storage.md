# Arcade preparation and playback

Endless and Time Attack share a two-song upcoming buffer. Picks come from the full imported
catalog, rather than only the files already on the phone. The buffer retains a pending pick,
replaces it after play or failure, and prefers a prepared upcoming pick at the next transition.
Cached imported songs remain the fallback when preparation is slow or the connection is lost;
a single available song can repeat. Built-in songs cover an empty library.

Preparation starts on the arcade picker and continues during a run. Explicitly selected songs
take queue priority, followed by upcoming arcade songs, then bulk and journey preparation.
Requests are deduplicated, failures back off for five minutes, and clearing downloads invalidates
the in-memory buffer. Storage details and download controls are confined to Settings, apart from the storage warning
when importing a large playlist.

Smart storage defaults to 30 days and supports 1–365 days or disabling cleanup. Background checks
on launch/resume run at most once every six hours; changing the setting requests a fresh check.
Age is measured from the later of the last play and download time. Playing songs, upcoming picks
and a small fallback reserve are protected. Cleanup removes the song's audio, charts and cover,
while retaining playlists, scores and unlocks. Shared songs are processed once.

Each play receives a lane permutation that differs from the previous layout. Musical times and
durations remain unchanged, chords stay together, and permutations that introduce conflicts with
overlapping slide paths are rejected. Clearing every tap in a chord within 65 ms earns
`100 × (chord size − 1) × multiplier` extra points, with a gold burst and bonus banner.

The stutter investigation removed chart parsing, arcade catalog reads and preparation queue
planning from the main thread. Rail gradients and HUD labels reuse cached data, empty gesture
updates avoid allocating an iterator, and unchanged download notifications are no longer sent on
every progress update. Native helpers use a lower CPU priority when Android's `nice` executable is
available, and PCM decoding uses one worker thread. Segment playback waits for seeking to finish
before starting the countdown. The emulator uses SwiftShader software graphics, so its frame
timings are not a physical-phone FPS benchmark.

Regression coverage includes buffer refill, pending requests, historical and fresh failures,
clearing the buffer, full-catalog selection, offline repeats, layout variation and slide safety,
chord scoring, cleanup protection and Settings-only storage details. Android tests exercise both
arcade modes without network, real touchscreen gameplay, native decoding and downloader startup.

Playlist imports have no song-count limit. Source metadata is streamed to a temporary file rather
than the runtime's 8 MB diagnostic buffer, then removed after parsing. Reading metadata does not
save a collection or start downloads. The app counts unique valid song IDs on a worker thread;
more than 100 prompts for storage confirmation before saving. Cancel and Back abandon the import.
The initial preparation window remains a few songs regardless of collection size. The native
regression test processes a 350-song fixture with more than 9 MB of metadata, checks cancellation
without saving, and confirms that all songs survive the approved import.
