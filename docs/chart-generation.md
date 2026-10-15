# Audio-driven tile generation

The previous generator primarily measured changes in loudness and bass energy, then placed tiles
on tracked beats and selected half-beats. A plausible tempo is not enough to identify when a
melody actually plays: syncopated attacks and notes that change pitch without changing volume
can fall between that grid.

The Android generator now uses a Kotlin implementation of the SuperFlux approach: compare
log-frequency spectra against earlier spectra, with a frequency maximum filter to reduce false
attacks from vibrato. Adaptive peak picking supplies candidate tile times. Beat tracking supplies
bar structure and metrical weight; it does not move the selected attacks onto a grid. Bar intensity
controls density, accent strength controls chords, and sustained gaps can become holds or slides.
The lane heuristic uses the spectral centre of newly appearing energy, rather than transcribing
individual piano pitches. The implementation builds on the existing chart rewrite in the workspace.

Research and alternatives considered:

| Approach | Relevance to this app |
| --- | --- |
| [SuperFlux reference implementation](https://github.com/CPJKU/SuperFlux) and [librosa example](https://librosa.org/doc/0.11.0/auto_examples/plot_superflux.html) | Detect attacks with less sensitivity to vibrato. The algorithm can run directly in Kotlin without shipping a Python scientific stack or trained models. This is the chosen approach. |
| [TarsosDSP](https://github.com/JorenSix/TarsosDSP) | Java audio processing with onset detection and BeatRoot tracking. A viable JVM library to compare in future; beat tracking alone would still need gameplay selection and spacing rules. |
| [madmom](https://github.com/CPJKU/madmom) | Provides neural onset and beat tracking. Its Python/native dependencies require additional Android integration, and its distributed models have a separate noncommercial license. |
| [Essentia RhythmExtractor2013](https://essentia.upf.edu/reference/std_RhythmExtractor2013.html) | Provides beat positions and confidence using several features; requires 44.1 kHz input. Would need a native integration and still needs note selection. |
| [Dance Dance Convolution](https://arxiv.org/abs/1703.06891) | Learned rhythm-game chart generation separates placement times from step selection. Relevant longer-term work, with training and inference requirements beyond the current on-device heuristic. |

Tests synthesize audio with known attack timestamps, including syncopation, equal-volume pitch
changes, and accelerating tempo. Selected tiles must stay within 45 ms of an actual attack; gesture
tails must leave room before the next tile. Existing tests also check silence breaks, section
density, drop chords, repeatability, and lane validity. Input regressions check that a completed or
failed gesture cannot consume a following tap, gold tile, or bomb through continuing motion.

This is automatic audio analysis, not a hand-authored chart or full music transcription. Spectral
changes from vocals and effects can still be selected, weak attacks can be missed, and tempo or
bar phase can be ambiguous. Audio with insufficient rhythmic evidence retains the estimated-grid
fallback. Listening tests on representative songs remain necessary to assess musical feel.

Chart cache version 4 makes downloaded songs rebuild their charts before play through the existing
download/cache flow. Built-in demo charts remain prebuilt assets.
