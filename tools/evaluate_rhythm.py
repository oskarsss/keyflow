#!/usr/bin/env python3
"""Evaluate exported Kotlin frontend features against external human beat annotations.

Requires numpy, scipy and onnxruntime. See docs/beat-tracking.md for the two-stage Kotlin
export. Audio and reference labels are evaluation inputs, never packaged with the application.
"""
import argparse
import json
from pathlib import Path
import time
import numpy as np
import onnxruntime as ort
from scipy.ndimage import maximum_filter1d


def peaks(logits):
    candidates = np.flatnonzero((logits > 0) & (logits == maximum_filter1d(logits, 7, mode="constant", cval=-1000)))
    result = []
    if not len(candidates):
        return np.array(result)
    average, count = float(candidates[0]), 1
    for index in candidates[1:]:
        if index - average <= 1:
            count += 1
            average += (index - average) / count
        else:
            result.append(average / 50)
            average, count = float(index), 1
    result.append(average / 50)
    return np.array(result)


def predict(session, spect):
    length = len(spect)
    starts = list(range(-6, length - 6, 1488))
    if length > 1488:
        starts[-1] = length - 1494
    beat = np.full(length, -1000, dtype=np.float32)
    downbeat = beat.copy()
    assigned = np.zeros(length, dtype=bool)
    for start in starts:
        values = np.zeros((1, 1500, 128), dtype=np.float32)
        lo, hi = max(0, start), min(length, start + 1500)
        values[0, lo - start:hi - start] = spect[lo:hi]
        b, d = session.run(["beat", "downbeat"], {"input_spectrogram": values})
        for j in range(6, 1494):
            index = start + j
            if 0 <= index < length and not assigned[index]:
                beat[index], downbeat[index], assigned[index] = b[0, j], d[0, j], True
    assert assigned.all()
    beats = peaks(beat)
    downbeats = np.unique([beats[np.argmin(abs(beats - d))] for d in peaks(downbeat)]) if len(beats) else np.array([])
    return beats, downbeats


def score(reference, estimate, tolerance=.070):
    reference, estimate = sorted(t for t in reference if t >= 5), sorted(t for t in estimate if t >= 5)
    i = j = 0
    errors = []
    while i < len(reference) and j < len(estimate):
        delta = estimate[j] - reference[i]
        if abs(delta) <= tolerance:
            errors.append(abs(delta)); i += 1; j += 1
        elif delta < 0:
            j += 1
        else:
            i += 1
    matches = len(errors)
    return {"f1": 2 * matches / max(1, len(reference) + len(estimate)), "matches": matches,
            "reference": len(reference), "estimate": len(estimate),
            "median_error_ms": float(np.median(errors) * 1000) if errors else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--model", type=Path, default=Path("app/src/main/assets/rhythm/beat-this-final0-int8.onnx"))
    args = parser.parse_args()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(args.model), sess_options=options, providers=["CPUExecutionProvider"])
    results = []
    for path in sorted(args.directory.glob("*.mel.f32")):
        name = path.name.removesuffix(".mel.f32")
        spect = np.fromfile(path, dtype="<f4").reshape(-1, 128)
        started = time.monotonic()
        beats, downbeats = predict(session, spect)
        output = {"beats": beats.tolist(), "downbeats": downbeats.tolist()}
        (args.directory / f"{name}.rhythm.json").write_text(json.dumps(output))
        result = {"track": name, "seconds": round(time.monotonic() - started, 3), "frames": len(spect)}
        reference = args.directory / f"{name}.beats"
        if reference.exists():
            labels = np.loadtxt(reference, ndmin=2)
            result["beats"] = score(labels[:, 0], beats)
            result["downbeats"] = score(labels[labels[:, 1] == 1, 0], downbeats)
        results.append(result)
        print(json.dumps(result), flush=True)
    report = {"tracks": results}
    for field in ["beats", "downbeats"]:
        values = [r[field]["f1"] for r in results if field in r]
        if values:
            report[field + "_mean_f1"] = float(np.mean(values))
    (args.directory / "evaluation.json").write_text(json.dumps(report, indent=2))
    print(json.dumps({k: v for k, v in report.items() if k != "tracks"}), flush=True)


if __name__ == "__main__":
    main()
