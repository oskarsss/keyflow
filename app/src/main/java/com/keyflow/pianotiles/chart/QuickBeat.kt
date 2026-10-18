package com.keyflow.pianotiles.chart

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Beats without a neural network, for quick levels: the tempo from the autocorrelation of the
 * onset strength, then Ellis' dynamic programme (2007) for where the beats fall, and a 4/4 bar
 * that starts where the low end hits hardest. It takes milliseconds where Beat This! takes tens of
 * seconds, and quick levels built on it keep nearly all of their match with the full level.
 */
object QuickBeat {
    private const val FPS = 50
    private const val TIGHTNESS = 400.0

    fun analyze(features: MusicFeatures): MusicalRhythm {
        val onset = onsets(features)
        val frames = track(onset, period(onset))
        val low = rise(features, 0, 10)
        val phase = if (frames.size >= 8) (0 until 4).maxBy { k -> (k until frames.size step 4).sumOf { low[frames[it]] } } else 0
        val beats = DoubleArray(frames.size) { frames[it].toDouble() / FPS }
        return MusicalRhythm(beats, (phase until beats.size step 4).map { beats[it] }.toDoubleArray())
    }

    /** Onset strength with its slow trend removed, so loud passages don't swamp the tempo. */
    private fun onsets(features: MusicFeatures): DoubleArray {
        val rise = rise(features, 0, MusicFeatures.BINS)
        val n = rise.size
        val radius = 25
        val out = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until minOf(radius, n)) sum += rise[i]
        for (t in 0 until n) {
            if (t + radius < n) sum += rise[t + radius]
            if (t - radius - 1 >= 0) sum -= rise[t - radius - 1]
            out[t] = maxOf(rise[t] - sum / (2 * radius + 1), 0.0)
        }
        val mean = out.average()
        val deviation = sqrt(out.sumOf { (it - mean) * (it - mean) } / n) + 1e-9
        for (t in 0 until n) out[t] /= deviation
        return out
    }

    /** The beat length in frames: the strongest self-similarity between 60 and 200 BPM, near 120. */
    private fun period(onset: DoubleArray): Int {
        val n = onset.size
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in FPS * 60 / 200..FPS * 60 / 60) {
            if (lag >= n) break
            var dot = 0.0
            for (i in 0 until n - lag) dot += onset[i] * onset[i + lag]
            val bpm = 60.0 * FPS / lag
            val octaves = ln(bpm / 120) / ln(2.0)
            val score = dot / (n - lag) * exp(-.5 * octaves * octaves)
            if (score > bestScore) { bestScore = score; best = lag }
        }
        return best.coerceAtLeast(1)
    }

    /** Beats that land on strong onsets while keeping close to [period] apart. */
    private fun track(onset: DoubleArray, period: Int): IntArray {
        val n = onset.size
        if (n == 0) return IntArray(0)
        val score = onset.copyOf()
        val back = IntArray(n) { -1 }
        val first = -2 * period
        val last = -((period + 1) / 2)
        val penalty = DoubleArray(last - first + 1) { i ->
            val gap = ln(-(first + i).toDouble() / period)
            -TIGHTNESS * gap * gap
        }
        for (t in 0 until n) {
            var bestValue = Double.NEGATIVE_INFINITY
            var bestFrame = -1
            for (i in penalty.indices) {
                val from = t + first + i
                if (from < 0) continue
                val value = score[from] + penalty[i]
                if (value > bestValue) { bestValue = value; bestFrame = from }
            }
            if (bestFrame >= 0 && bestValue > 0) {
                score[t] = onset[t] + bestValue
                back[t] = bestFrame
            }
        }
        var t = (maxOf(0, n - period) until n).maxBy { score[it] }
        val beats = ArrayList<Int>()
        while (t >= 0) {
            beats.add(t)
            t = back[t]
        }
        return beats.asReversed().toIntArray()
    }

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
}
