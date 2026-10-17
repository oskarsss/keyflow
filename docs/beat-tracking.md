# Beat tracking

Every chart is conditioned on the song's musical clock: its beats and downbeats. Keyflow finds
them on the phone with the [Beat This!](https://github.com/CPJKU/beat_this) `final0` model
([ISMIR 2024](https://arxiv.org/abs/2407.21658), MIT), shipped as a fixed 30-second dynamic INT8
ONNX graph in `assets/rhythm/`. The beats feed the [chart model](chart-model.md), the journey
highlight, the scroll speed and the pulsing hit targets.

## Pipeline

1. Decode the downloaded audio through the bundled FFmpeg to 22,050 Hz mono, streaming it
   rather than holding full PCM in memory (`chart/MusicFeatures.kt`).
2. Match the published frontend: centered 1024-point periodic-Hann STFT, reflection padding,
   441-sample hop (50 frames/s), magnitude divided by `sqrt(1024)`, 128 unnormalized Slaney mel
   bands from 30–11,000 Hz, then `log1p(1000 × mel)`. Comparison with the reference on a real
   30-second clip gave a maximum absolute feature error of **4.77e-7**. A synthetic golden fixture
   (`app/src/test/resources/rhythm/frontend-reference.f32`) tests both reflected edges and
   interior frames.
3. Run the model with two CPU workers and spinning disabled (`chart/BeatThis.kt`). Chunks have
   1500 frames; six frames are discarded at each edge and overlapping predictions keep their first
   valid value. Postprocessing follows upstream: seven-frame maxima, a positive-logit threshold,
   adjacent-plateau merging and downbeat snapping.
4. Interpret the raw pulse for play (`chart/MusicalRhythm.kt`). A meter-consistency dynamic
   program removes spurious half-bar anchors. An obvious fast six-pulse compound bar becomes two
   main pulses, and very fast simple-meter music can use half time. In House of the Rising Sun
   this gives about 79 BPM rather than a 231 BPM arpeggio stream.

A song with fewer than four beats is rejected with a message, rather than given a random level.

## Measured accuracy

We evaluated all **100 recordings** in the [ISMIR 2021 GTZAN mini collection](https://github.com/TempoBeatDownbeat/gtzan_mini)
against the [published Beat This! annotations](https://github.com/CPJKU/beat_this_annotations)
(audio tree `a61439e86c13037011fde8e0f0743ec55c50bce3`, annotation tree
`fd9fcc0896cb78730bae735102c8753ef3a3badd`): ten 30-second excerpts per genre. The `final0`
training split excludes GTZAN. F1 uses one-to-one matches within **70 ms**, discards the first
five seconds and averages tracks equally. The measurement uses the Kotlin frontend with the
committed ONNX model and the upstream postprocessor, **before** step 4's gameplay interpretation.

| Genre | Beat F1 | Downbeat F1 |
| --- | ---: | ---: |
| blues | 88.7% | 52.9% |
| classical | 67.0% | 57.7% |
| country | 95.8% | 94.6% |
| disco | 92.8% | 82.6% |
| hiphop | 98.4% | 91.1% |
| jazz | 80.7% | 52.7% |
| metal | 77.5% | 65.9% |
| pop | 99.5% | 98.0% |
| reggae | 95.9% | 67.2% |
| rock | 88.5% | 62.6% |
| **All 100 tracks** | **88.49%** | **72.54%** |

[Per-track results](rhythm-evaluation.csv) include the weak cases, particularly dense metal,
classical and metrically ambiguous recordings. Beat F1 measures the clock only. It says nothing
about how enjoyable the tiles are, which needs play testing.

## Reproducing the evaluation

`python3 tools/prepare_rhythm_model.py --check` verifies the committed model against its manifest.
For external recordings, prepare 22,050 Hz mono signed 16-bit little-endian `.pcm22` files, with
annotations in matching `.beats` files of two columns (seconds, beat number in the bar). Keep
evaluation audio outside tracked paths, for example under the ignored `artifacts/`.

```bash
KEYFLOW_RHYTHM_EVALUATION="$PWD/artifacts/evaluation" \
  ./gradlew :app:testDebugUnitTest --tests '*RhythmEvaluationTest' --rerun-tasks
# In a separate venv: pip install numpy scipy onnxruntime
python tools/evaluate_rhythm.py artifacts/evaluation
```

The first command exports the Kotlin frontend's mel features; the second runs the model on them
and writes `evaluation.json`. Regular unit tests need neither the files nor Python packages.
