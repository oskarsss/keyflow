# Human-chart-trained generation, version 6

Version 6 replaces the handwritten tile-selection planner with a pretrained audio-to-chart model.
Both model inference and audio preparation run on the phone. The download queue saves the complete
chart before marking a song ready: timestamps, lanes, taps, holds, musical metadata and the journey
highlight. Opening a prepared level reads this chart; it does not run either neural model again.
Replay lane permutations and score/reward decoration remain inexpensive playback operations.

## Model and provenance

We adapted the **mania checkpoint of [Mapperatorinator-v32-mini](https://huggingface.co/OliBomby/Mapperatorinator-v32-mini)**,
by OliBomby, from [the upstream project](https://github.com/OliBomby/Mapperatorinator). Its learned
weights come from community-created osu! beatmaps, rather than four project-authored pattern
presets. We did **not** train new weights or fine-tune this checkpoint on Keyflow players.

- Checkpoint revision: `7807f0dc70cab671be012e1f5ddf945b0b8b7278`, subfolder `gamemode=3`.
- Source revision: `0e2b0e387aab4b35c64b0b11b12d47578dea7587`.
- Parameters: 55,706,112. The two bundled INT8 ONNX graphs total 78,516,909 bytes (74.9 MiB).
- Upstream code and checkpoint: MIT. The license ships in `assets/chart/Mapperatorinator-LICENSE.txt`.
- `assets/chart/model.json` records source/checkpoint revisions, checkpoint digest, frontend,
  export dependency versions, parity measurements, asset sizes and SHA-256 digests.

This is an adaptation of an existing trained model, not a claim that Keyflow's data created it.
No community training audio or chart dataset is shipped. Four-key mania offers the relevant
lane/tap/hold vocabulary, but keyboard charts need an additional two-thumb gate for this game.

## Preparation pipeline

1. Decode the actual downloaded playback audio at 22,050 Hz. The existing bundled Beat This!
   model supplies beat/downbeat conditioning, interpreted at a playable pulse for fast or compound
   meter. Its [separate beat-detector evaluation](chart-generation.md#measured-beat-accuracy)
   remains useful; its 88.49% beat F1 does not measure this new chart model's quality.
2. Decode 16,000 Hz mono PCM to a temporary app-private file, measuring the whole-song peak.
   Reading fixed windows keeps full PCM out of the Java heap. Delete the temporary file after
   success, failure or interruption.
3. Match the chart model's frontend: centered, reflection-padded 1024-point periodic-Hann STFT,
   128-sample hop, power spectrum, 128 HTK mel bands spanning 20–8,000 Hz, and `log1p`. Normalize
   PCM by the whole-song peak. A separate Torch-generated synthetic reference checks reflected
   edges and interior frames against Kotlin.
4. Run the portable six-layer encoder and six-layer cached decoder in ONNX Runtime Android's CPU
   provider, with two worker threads and spinning disabled. Each 16.376-second window includes
   rolling chart history and lookahead. The decoder predicts actual note timestamps, columns and
   hold boundaries, conditioned on four keys, low difficulty, the musical clock and earlier notes.
   Greedy decoding masks backward timestamps and columns outside the four-key layout. For a fresh
   window with audible energy and at least four detected beats, a weak immediate stop can yield
   to a plausible learned timestamp (within 2.5 logit units). Confident stops and silence remain
   allowed. This prevents the pretrained model's optional empty intros from discarding clear
   music: both FP32 and INT8 initially skipped 47 seconds of the Animals fixture. The guard does
   not invent a timestamp or change an existing note's timing.
5. Pair hold heads/tails by lane. Apply the existing two-thumb gate: at most two concurrent actions,
   one per hand, bounded hold length and recovery between actions on a busy hand. A silence gate
   removes notes with no audible energy. These safety operations remove, relocate or shorten model outputs;
   they do not add random playable timestamps. Imported learned charts currently use taps and
   holds; the existing authored demos also demonstrate slides.
6. Describe whole-song energy, note density and recurring harmony for highlight selection and
   scrolling metadata. This does not replace the model's tile placements with phrase templates.
7. Choose the continuous journey highlight, serialize everything to a temporary chart file, then
   rename it into place. A song is ready only when its audio and current-version chart both exist.
   Report window progress as “Preparing level”. Interrupted work can retry through the same queue.
   Arcade's upcoming buffer prepares these complete charts too.

Generation is deliberately a preparation cost, not a startup cost. Existing downloaded audio can
be reused while its chart is rebuilt for cache version 6. No PC, network inference, API key or
runtime model download is required. Older charts are not silently presented as new learned charts.

## Conversion and checks

`tools/chart_model/portable.py` implements the checkpoint's attention/RMSNorm/RoPE math with
explicit cross-attention and self-attention caches, using standard ONNX operators. Export checks
compare the portable FP32 implementation to the pinned source with explicit causal attention,
then compare ONNX prefill and cached steps. Maximum absolute differences against upstream on the
export fixture were **1.86e-6 for encoder memory** and **7.82e-5 for decoder logits**. These are FP32
conversion checks, not a claim that quantization leaves all generated charts identical.

Dynamic per-channel INT8 matrix weights reduce storage and CPU work. Embeddings/convolutions stay
float. Native Android inference is tested separately; the shipped graphs are not PyTorch wrappers
and require neither Python nor the upstream training stack during chart inference.

```bash
# Normal builds use committed models and need no training environment.
python3 tools/prepare_chart_model.py --check

# Optional reproducible conversion, with CPU PyTorch in a separate environment:
python3 -m venv artifacts/chart-learning/venv
artifacts/chart-learning/venv/bin/pip install torch==2.10.0 torchaudio==2.10.0 \
  --index-url https://download.pytorch.org/whl/cpu
artifacts/chart-learning/venv/bin/pip install -r tools/chart_model/requirements.txt
artifacts/chart-learning/venv/bin/python tools/prepare_chart_model.py --export
```

Export downloads the pinned source/checkpoint under ignored `artifacts/chart-learning/`; it rejects
an unexpected or modified source checkout. Review the generated graphs, parity, native behavior
and manifest before copying the export to `assets/chart/`. The exported synthetic frontend fixture
belongs in `app/src/test/resources/rhythm/`. No downloaded evaluation music belongs in Git.

JVM coverage includes frontend parity, window coverage, temporary-file cleanup, exact reference
conditioning, monotonic times, four-key masking, empty-intro coverage, overlapping hold pairing, complete serialization,
precomputed highlights and readiness only after atomic chart publication. Native tests run the
actual bundled models and production preparation path, verify chart reuse, and time opening an
already prepared imported level. Optional six-song evaluation uses `rhythmFixtureDir`, as described
in the earlier chart-generation notes.

## What the measurements mean

On the original 84 BPM piano fixture, a native Android 15 x86_64 emulator run produced 47 notes.
All were within 100 ms of an independently known attack; median error was **14 ms**, maximum
**24 ms**. The timed 16 kHz decode/chart-model block took **20.355 seconds**. This is one deliberately
simple recording, not a general chart-timing benchmark. The output was mostly one-beat holds,
reflecting the sustained piano fixture; this does not establish good density for every genre.
An emulator result is not a physical-phone speed, thermal or battery benchmark.

A six-recording production-path check covers Safety Net, Teenagers, Vladimir Zelentsov's metal
Прекрасное далёко, Любэ's Дорога, Way Down We Go and House of the Rising Sun. Its numeric report
will distinguish preparation coverage from human preference: a chart that loads and obeys hand
limits can still need musical improvement.

The model sees short windows with rolling history. It can learn local phrasing but does not
promise identical choreography for choruses minutes apart. Converting keyboard maps to two thumbs
also removes some original patterns. We need listening and play feedback on the new charts before
claiming better enjoyment across these songs; beat F1 and synthetic attack alignment cannot
substitute for that assessment. Further fine-tuning would require a curated, permitted set of
four-lane maps and held-out song-level evaluation, rather than randomly mixing windows of the
same song into training and validation.

## Jev's possible role

The requested [Jev 1.13](https://openrouter.ai/typesafe/jev-1.13) is a text/JSON decision model.
[Typesafe's input contract](https://docs.typesafe.ai/concepts/system-one) does not give that version
raw-audio input. It could optionally rank structured section or pattern summaries after audio
analysis, but it does not replace an audio-timing model. Its musical ranking ability would need
an explicit comparison against player judgments. It is not used in this release, no songs are
uploaded to it, and no API key is needed. Keeping it out of the core path preserves fully local
preparation and offline playback.
