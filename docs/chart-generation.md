# Musical clock evaluation and the previous version 5 planner

**Current production uses [human-chart-trained generation, version 6](chart-learning.md).**
The beat-detector evaluation below still applies to its conditioning clock. The phrase planner,
song chart counts and fallback behavior documented here describe the previous version 5 baseline.

The earlier generator selected independent SuperFlux peaks and ranked bars by energy. That could
produce plausible timestamps without a coherent playable rhythm: a vocal consonant or guitar
change could win over a drum pulse, each bar could choose a different subset, and drops could ask
for four fingers. Version 5 separates pulse, audible attacks, repeated phrases and hand planning.

## Research and the chosen approach

| Source | What it contributes / why it is not the whole solution |
| --- | --- |
| [Beat This!, ISMIR 2024](https://arxiv.org/abs/2407.21658), [official code and weights](https://github.com/CPJKU/beat_this) | Learned beat and downbeat detection over mixed audio. The MIT-licensed `final0` model is shipped as a fixed 30-second dynamic INT8 ONNX graph and runs locally. It replaces the loudness/autocorrelation tracker as the primary musical clock. |
| [BeatNet, ISMIR 2021](https://github.com/mjhydri/BeatNet) | Joint beat/downbeat/meter tracking with CRNN and particle/DBN inference. A serious alternative; its streaming pipeline does not solve phrase repetition or touch choreography. |
| [SuperFlux](https://github.com/CPJKU/SuperFlux) | Supplies audible attack candidates and their microtiming. It is useful alongside a learned pulse, but selecting its largest peaks alone does not produce a good rhythm-game chart. |
| [Dance Dance Convolution](https://arxiv.org/abs/1703.06891), [implementation](https://github.com/chrisdonahue/ddc) | Separates placement timing from action selection and learns from human-created DDR charts. This separation informs the pipeline; its old TensorFlow/Essentia stack and DDR action vocabulary are not shipped. No DDC weights/code are copied. |
| [Time-based Chart Partitioning, AIIDE 2025](https://ojs.aaai.org/index.php/AIIDE/article/view/36808) | Combines onset detection, beat snapping and local pattern matching. Supports treating musical coherence as a separate problem from detecting acoustic events. This project implements its own phrase planner rather than copying that system. |

Many polished rhythm-game charts are human-authored. Automatic systems exist, but a beat detector
alone is not a substitute for their choreography. This implementation combines a pretrained rhythm
model with original phrase and two-thumb planning; it does not claim note-for-note transcription.

## On-device pipeline

1. Decode the actual playback audio through the bundled FFmpeg. A streaming 11,025 Hz pass produces
   SuperFlux attack times; a 22,050 Hz pass produces the neural frontend. Neither retains full PCM.
2. Match the published frontend: centered 1024-point periodic-Hann STFT, reflection padding,
   441-sample hop (50 frames/s), magnitude divided by `sqrt(1024)`, 128 unnormalized Slaney mel
   bands from 30–11,000 Hz, then `log1p(1000 × mel)`. Independent reference comparison on a real
   30-second clip gave maximum absolute feature error **4.77e-7**. A synthetic golden fixture
   tests both reflected edges as well as interior frames.
3. Run the `final0` model with two CPU workers and spinning disabled. Chunk inputs have 1500 frames;
   six frames are discarded at each edge and overlapping predictions keep their first valid value.
   First/last padding and shifted final chunks cover every real frame. Postprocessing follows the
   upstream seven-frame maxima, positive-logit threshold, adjacent-plateau merging and downbeat
   snapping. Session/tensor resources close after each analysis.
4. Clean spurious half-bar anchors with a meter-consistency dynamic program. Interpret an obvious
   fast six-pulse compound bar as two main pulses; very fast simple-meter music can use half time.
   This is gameplay pulse interpretation, separate from the raw detector measured below. In the
   requested House of the Rising Sun recording it yields about 79 BPM rather than a 231 BPM
   arpeggio stream; the requested metal cover uses about 94 rather than 188 BPM.
5. Group two bars at a time. Compare time-aligned chroma, timbre and softly binned attack profiles
   to identify recurring phrase families. Soft phase bins tolerate a small onset-window shift.
   A family's representative supplies a shared rhythmic slot mask and lane motif. Audible
   downbeat anchors are retained before fills; section intensity controls density. Repetition can
   adapt to a genuinely missing attack without making up a tile in silence.
6. Match candidate musical slots to real attacks, including straight and three-part subdivisions.
   Keep the attack's measured microtiming instead of moving every note onto an artificial grid.
   Chord accents use at most one tile on each side. A short temporal median suppresses percussive
   transients when checking sustained spectral shape; continuous-energy checks prevent separate
   plucks being converted into a long note. Holds/slides have a cooldown and bounded length.
7. Apply an unconditional two-thumb gate to new and legacy charts. At most two actions can overlap,
   one per hand; a busy hand cannot be assigned another action. Slides move only to the adjacent
   lane on the same side. Replays use a hand-preserving lane permutation, so they vary without
   breaking repeated musical motifs or creating crossing paths. Decoration adds rewards/hazards,
   never new random playable attack times or new random chords.

If a song has too little neural pulse evidence, an attack-only fallback can still be used. The
production preparation path rejects the old estimated-grid fallback instead of presenting a
random-looking level for audio with no usable musical evidence. Chart cache version **5** rebuilds
existing music through the usual preparation queue, reusing its audio when it is already present.
All analysis runs off the UI thread; it does not upload music or download a model at runtime.

## Measured beat accuracy

Evaluated all **100 recordings** in the [ISMIR 2021 GTZAN mini collection](https://github.com/TempoBeatDownbeat/gtzan_mini)
against the [published Beat This! beat/downbeat annotations](https://github.com/CPJKU/beat_this_annotations).
Audio tree: `a61439e86c13037011fde8e0f0743ec55c50bce3`; annotation tree:
`fd9fcc0896cb78730bae735102c8753ef3a3badd`. There are ten 30-second excerpts per genre.
The published `final0` training split excludes GTZAN. These results describe this fixed subset,
not every possible recording or genre.

F1 uses one-to-one matches within **70 ms**, discards the first five seconds, and averages tracks
equally. The old result is the previous Kotlin beat tracker, run on the same audio. New results
use the actual Kotlin frontend exported to the committed ONNX model on the CPU provider and the
upstream minimal postprocessor, **before** gameplay half-time/compound-meter interpretation.

| Genre | Previous beat F1 | Neural beat F1 | Neural downbeat F1 |
| --- | ---: | ---: | ---: |
| blues | 85.8% | 88.7% | 52.9% |
| classical | 58.6% | 67.0% | 57.7% |
| country | 84.8% | 95.8% | 94.6% |
| disco | 92.9% | 92.8% | 82.6% |
| hiphop | 96.6% | 98.4% | 91.1% |
| jazz | 53.4% | 80.7% | 52.7% |
| metal | 80.2% | 77.5% | 65.9% |
| pop | 92.9% | 99.5% | 98.0% |
| reggae | 85.7% | 95.9% | 67.2% |
| rock | 76.1% | 88.5% | 62.6% |
| **All 100 tracks** | **80.70%** | **88.49%** | **72.54%** |

[Per-track measurements](rhythm-evaluation.csv) retain regressions too: the model is not uniformly
better, particularly on some dense metal, classical and metrically ambiguous recordings. Beat F1
is **not** a score for tile enjoyment, melody transcription, note density or chorus recognition.
Those need separate constraints, regression fixtures and listening/play testing.

## Requested-song checks

The local development corpus includes Bea — Safety Net, My Chemical Romance — Teenagers,
Vladimir Zelentsov — Прекрасное далёко (metal version, confirmed by the user), Любэ — Дорога,
KALEO — Way Down We Go, and The Animals — House of the Rising Sun. These recordings exercise
straight, swung, compound, slow and dense arrangements. They are external test inputs and are
not included in repository/release assets. All six passed the Android test through the actual
FFmpeg → Kotlin features → ONNX → phrase planner → saved chart path without a network request.
The test checks that each chart has more than 60 notes and no simultaneous actions sharing a hand.
The evaluated MP3 copies produced these results on the Android 15 x86_64 emulator:

| Recording | Gameplay BPM | Notes | Repeated phrase families |
| --- | ---: | ---: | ---: |
| Safety Net | 130.4 | 556 | 10 |
| Teenagers | 111.1 | 352 | 4 |
| Прекрасное далёко, Zelentsov metal version | 93.7 | 220 | 5 |
| Дорога | 115.4 | 604 | 12 |
| Way Down We Go | 81.1 | 387 | 3 |
| House of the Rising Sun | 78.9 | 412 | 23 |

These counts demonstrate production-path coverage, not independent human judgments of chorus
boundaries or timing accuracy. A separate native test uses original piano audio with known attack
times and checks that every generated tile is within 60 ms of a real attack.

## Playback and length

The stationary green target band corresponds to the actual ±80 ms perfect window; the broader
accepted hit window remains ±180 ms. The lower tile edge reaches the fixed line at its note time.
Moving transverse road stripes are removed. Good hits distinguish early/late. Slides show a white
position ring and direction, accept ±0.9 lane around the path, and allow a 240 ms excursion before
failing. Crossing a slide lane and completing a held tail provide haptic feedback when enabled.

Normal journey scroll speed follows whole-song tempo, with conservative bounds and the existing
Slow/Normal/Fast setting. Playback rate remains a separate setting; sections do not abruptly
change scrolling speed. Endless retains its deliberate stage-by-stage acceleration.

Long journey levels default to a **single continuous, bar-aligned highlight of at most 150 seconds**,
ranked by section activity and context. They retain a short lead-in and an ending fade. This is
an excerpt, not a montage of spliced choruses. Settings offers the full original song. Originals
shorter than 150 seconds play whole; arcade segments retain their existing length.

## Reproducing the evaluation

`tools/prepare_rhythm_model.py --check` verifies the committed asset and its manifest. For external
recordings, prepare 22,050 Hz mono signed-16 little-endian `.pcm22` files and equivalent 11,025 Hz
`.pcm11` files with matching stems. Human annotation files use matching `.beats` stems and two
columns (seconds, beat number in bar). Keep evaluation audio outside tracked source paths.

```bash
KEYFLOW_RHYTHM_EVALUATION="$PWD/artifacts/evaluation" \
  ./gradlew :app:testDebugUnitTest --tests '*RhythmEvaluationTest' --rerun-tasks
# In a separate development venv: pip install numpy scipy onnxruntime
python tools/evaluate_rhythm.py artifacts/evaluation
# Repeat the export to also generate the musical charts from the predicted rhythm files.
KEYFLOW_RHYTHM_EVALUATION="$PWD/artifacts/evaluation" \
  ./gradlew :app:testDebugUnitTest --tests '*RhythmEvaluationTest' --rerun-tasks
```

Regular unit tests do not need those files or Python scientific packages. They cover frontend
reference parity, complete chunk stitching, pulse/meter interpretation, repeated chorus motifs,
chorus density, real-attack alignment, sustained tones versus plucks, two-hand occupancy,
slide recovery, highlight boundaries and existing game/import/storage behavior. Android tests
cover the bundled model with original piano audio and can optionally process the six-song corpus
using `-e rhythmFixtureDir /path/accessible/to/the/app`. Emulator timing is not a physical-phone
latency or frame-rate benchmark.
