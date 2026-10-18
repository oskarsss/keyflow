package com.keyflow.pianotiles.chart

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.roundToInt

/**
 * A level made in a moment, for a song whose full level is still being made. Every sixteenth of
 * the beat is a candidate tile, described by how sharply the sound rises there in six frequency
 * bands, its place in the bar and how loud its bar is. Two small classifiers, distilled from the
 * full chart model's levels, decide which candidates become tiles and which of those chords; on
 * held-out songs they reproduce about three quarters of the full level's tiles. The full level
 * replaces it once made.
 */
object QuickChart {
    const val METHOD = "quick-distilled-v2"
    private const val FPS = 50
    private const val SILENT = .0008f
    /** Candidates scoring at least this become tiles, strongest first, at least [GAP] apart. */
    private const val THRESHOLD = .4
    private const val GAP = .17
    private const val CHORD = .3
    private const val HOLD_MIN = .4
    private const val HOLD_LEVEL = .5
    private const val HOLD_SAME = .93
    private val BANDS = arrayOf(0 to 10, 10 to 25, 25 to 45, 45 to 70, 70 to 100, 100 to 128)

    // Trained on the chart benchmark's 22 songs (artifacts/chart-eval-tools/distil.py). Features,
    // in order: onset strength, its six bands, beat, eighth, sixteenth, downbeat, bar loudness,
    // loudness within the bar, beat length, and how much the onset stands out from its neighbours.
    private val MEAN = doubleArrayOf(1.06546, 1.01101, 1.01101, 0.99682, 1.02515, 1.07711, 1.26237, 0.24965, 0.250098,
        0.500252, 0.078996, 0.501383, 0.00604347, 0.56479, -0.150562, 0.00128532, 0.00166516)
    private val SCALE = doubleArrayOf(0.381139, 0.418408, 0.348897, 0.354063, 0.371398, 0.438827, 0.583906, 0.43281,
        0.433069, 0.5, 0.269733, 0.279357, 0.320187, 0.171124, 0.534122, 0.527376, 0.526168)
    private val NOTE = doubleArrayOf(0.257652, 0.336168, 0.0752557, -0.00668922, -0.0144738, -0.0215224, 0.17217,
        0.381399, 0.10382, -0.42007, 0.0746486, 0.291203, 0.325069, 0.290928, 0.232174, 0.325836, 0.225171, 0.144587)
    private val CHORDS = doubleArrayOf(-0.561071, 0.162079, 0.196192, -0.161877, 0.0616107, 0.109073, 0.280514, 0.381143,
        -0.121211, -0.224939, 0.0396078, 0.533732, 0.468104, 0.256247, 0.800546, -0.214538, 0.325003, -3.07002)

    private class Candidate(val time: Double, val frame: Int, val features: DoubleArray) {
        var tile = 0.0
        var chord = 0.0
    }

    fun generate(features: MusicFeatures, detected: MusicalRhythm): Chart {
        val rhythm = detected.forGameplay()
        val beats = rhythm.beats
        require(beats.size >= 4) { "This recording has no clear rhythm to create a level. Try another song." }
        val duration = features.duration
        val total = rise(features, 0, MusicFeatures.BINS)
        val strength = relative(total)
        val bands = BANDS.map { (from, to) -> relative(rise(features, from, to)) }
        val downbeats = rhythm.downbeats.takeIf { it.size >= 2 } ?: beats.filterIndexed { i, _ -> i % 4 == 0 }.toDoubleArray()
        val downbeatSet = downbeats.toHashSet()
        val intensity = barIntensity(features, downbeats)
        val energy = features.energy

        val candidates = ArrayList<Candidate>()
        for (k in 0 until beats.size - 1) {
            val start = beats[k]
            val span = beats[k + 1] - start
            if (span !in .22..1.6) continue
            val bar = (downbeats.count { it < start + 1e-6 } - 1).coerceAtLeast(0)
            val barFrom = (downbeats[bar] * FPS).toInt().coerceIn(0, features.frames - 1)
            val barTo = ((downbeats.getOrElse(bar + 1) { duration }) * FPS).toInt().coerceIn(barFrom + 1, features.frames)
            val barEnergy = (barFrom until barTo).sumOf { energy[it].toDouble() } / (barTo - barFrom) + 1e-6
            for (part in 0 until 4) {
                val time = start + span * part / 4
                val f = (time * FPS).roundToInt()
                if (f - 2 < 0 || f + 2 >= strength.size) continue
                if (time < 1.0 || time > duration - .8 || energy[f] < SILENT) continue
                var peak = f - 2
                for (i in f - 1..f + 2) if (strength[i] > strength[peak]) peak = i
                val row = DoubleArray(17)
                row[0] = ln1p(strength[peak])
                for ((b, band) in bands.withIndex()) row[1 + b] = ln1p((f - 2..f + 2).maxOf { band[it] })
                row[7] = if (part == 0) 1.0 else 0.0
                row[8] = if (part == 2) 1.0 else 0.0
                row[9] = if (part % 2 == 1) 1.0 else 0.0
                row[10] = if (part == 0 && start in downbeatSet) 1.0 else 0.0
                row[11] = intensity.getOrElse(bar) { .5 }
                row[12] = ln(energy[f] / barEnergy + 1e-3)
                row[13] = span
                candidates.add(Candidate(peakTime(total, peak), f, row))
            }
        }
        // How much each candidate stands out from the ones beside it.
        for ((i, c) in candidates.withIndex()) {
            val here = c.features[0]
            val before = candidates.getOrNull(i - 1)?.features?.get(0) ?: 0.0
            val after = candidates.getOrNull(i + 1)?.features?.get(0) ?: 0.0
            c.features[14] = here - maxOf(before, after)
            c.features[15] = here - before
            c.features[16] = here - after
            c.tile = score(c.features, NOTE)
            c.chord = score(c.features, CHORDS)
        }

        // The likeliest tiles first, each at least GAP from the ones already taken.
        val taken = ArrayList<Candidate>()
        for (c in candidates.sortedByDescending { it.tile }) {
            if (c.tile < THRESHOLD) break
            if (taken.none { abs(it.time - c.time) < GAP }) taken.add(c)
        }
        taken.sortBy { it.time }
        val notes = ArrayList<Chart.Note>()
        var side = 0
        var pitch = -1
        for ((i, c) in taken.withIndex()) {
            if (c.time < 1.0 || c.time > duration - .8) continue
            // Hands take turns; within a hand, the tile moves the way the melody goes.
            val now = pitchClass(features, c.frame)
            if (pitch >= 0 && now != pitch) side = if (((now - pitch + 18) % 12) - 6 > 0) 1 else 0
            pitch = now
            val hand = i % 2
            val lane = hand * 2 + side
            // A hold lasts until this hand's next tile; the other hand plays on meanwhile.
            val next = taken.getOrNull(i + 2)?.time ?: duration
            val hold = holdLength(features, c.time, next)
            when {
                hold > 0 -> notes.add(Chart.Note(round(c.time), lane, "hold", round(hold)))
                else -> {
                    notes.add(Chart.Note(round(c.time), lane))
                    if (c.chord > CHORD) notes.add(Chart.Note(round(c.time), (1 - hand) * 2 + (1 - side)))
                }
            }
        }
        return ChartStructure.describe(features, detected, ThumbChart.makePlayable(notes), METHOD)
    }

    /**
     * A tile becomes a hold when what it starts rings on: the harmony stays the same and the sound
     * doesn't fade before the hand's next tile, as the full levels do now and then.
     */
    private fun holdLength(features: MusicFeatures, time: Double, next: Double): Double {
        val length = minOf(next - time - .25, 1.6)
        if (length < HOLD_MIN) return 0.0
        val start = ((time + .06) * FPS).toInt().coerceAtMost(features.frames - 1)
        val level = features.energy[start]
        for (step in 1..5) {
            val f = ((time + .06 + (length - .06) * step / 5) * FPS).toInt().coerceAtMost(features.frames - 1)
            if (features.energy[f] < level * HOLD_LEVEL || similarity(features, start, f) < HOLD_SAME) return 0.0
        }
        return length
    }

    private fun similarity(features: MusicFeatures, a: Int, b: Int): Double {
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (p in 0 until 12) {
            val x = features.chroma[a * 12 + p].toDouble()
            val y = features.chroma[b * 12 + p].toDouble()
            dot += x * y; aa += x * x; bb += y * y
        }
        return if (aa * bb > 1e-12) dot / kotlin.math.sqrt(aa * bb) else 0.0
    }

    private fun score(row: DoubleArray, weights: DoubleArray): Double {
        var sum = weights.last()
        for (i in row.indices) sum += (row[i] - MEAN[i]) / SCALE[i] * weights[i]
        return 1 / (1 + kotlin.math.exp(-sum))
    }

    /** How much the sound rises from each frame to the next in mel bins [from, to). */
    private fun rise(features: MusicFeatures, from: Int, to: Int): DoubleArray {
        val bins = MusicFeatures.BINS
        val out = DoubleArray(features.frames)
        for (f in 1 until features.frames) {
            var sum = 0.0
            for (b in from until to) {
                val up = features.mel[f * bins + b] - features.mel[(f - 1) * bins + b]
                if (up > 0) sum += up
            }
            out[f] = sum
        }
        return out
    }

    /** [values] against the 75th percentile of the six seconds around them, so quiet songs count too. */
    private fun relative(values: DoubleArray): DoubleArray {
        val out = DoubleArray(values.size)
        val block = 25
        val radius = 150
        for (start in values.indices step block) {
            val window = values.copyOfRange(maxOf(0, start - radius), minOf(values.size, start + block + radius)).sorted()
            val position = .75 * (window.size - 1)
            val low = floor(position).toInt()
            val reference = window[low] + (window[minOf(low + 1, window.lastIndex)] - window[low]) * (position - low) + 1e-6
            for (i in start until minOf(values.size, start + block)) out[i] = values[i] / reference
        }
        return out
    }

    /** The peak's time within its 20 ms frame. */
    private fun peakTime(curve: DoubleArray, i: Int): Double {
        if (i > 0 && i < curve.lastIndex) {
            val a = curve[i - 1]
            val b = curve[i]
            val c = curve[i + 1]
            val curvature = a - 2 * b + c
            if (curvature < 0) return (i + .5 * (a - c) / curvature) / FPS
        }
        return i.toDouble() / FPS
    }

    private fun pitchClass(features: MusicFeatures, frame: Int): Int {
        var best = 0
        for (p in 1 until 12) if (features.chroma[frame * 12 + p] > features.chroma[frame * 12 + best]) best = p
        return best
    }

    /** Each bar's loudness among the song's bars, 0–1; near-silent bars get 0. */
    private fun barIntensity(features: MusicFeatures, downbeats: DoubleArray): DoubleArray {
        val energy = DoubleArray(downbeats.size) { i ->
            val a = (downbeats[i] * FPS).toInt().coerceIn(0, features.frames - 1)
            val b = ((downbeats.getOrElse(i + 1) { features.duration }) * FPS).toInt().coerceIn(a + 1, features.frames)
            (a until b).sumOf { features.energy[it].toDouble() } / (b - a)
        }
        val loudest = energy.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
        // Equally loud bars share the middle of their ranks.
        return DoubleArray(energy.size) { i ->
            if (energy[i] < loudest * .07) 0.0
            else (energy.count { it < energy[i] * .98 } + .5 * energy.count { abs(it - energy[i]) <= energy[i] * .02 }) / energy.size
        }
    }

    private fun round(time: Double) = Math.round(time * 1000) / 1000.0
}
