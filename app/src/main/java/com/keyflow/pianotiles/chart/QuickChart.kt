package com.keyflow.pianotiles.chart

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A level made from the beat in a moment, for a song whose full level is still being made. Tiles
 * follow the beat through the audible bars, with off-beats where the music hits hard in its loud
 * parts, holds over bars that ring on and chords on strong downbeats. The full level replaces it.
 */
object QuickChart {
    const val METHOD = "quick-beat-v1"
    private const val FPS = 50
    private const val SILENT = .0008f
    private val patterns = arrayOf(
        intArrayOf(0, 2, 1, 3, 0, 3, 1, 2),
        intArrayOf(1, 2, 0, 3, 1, 3, 0, 2),
        intArrayOf(0, 3, 1, 2, 1, 3, 0, 2),
        intArrayOf(1, 3, 0, 2, 0, 3, 1, 2),
    )

    fun generate(features: MusicFeatures, detected: MusicalRhythm, seed: Int): Chart {
        val rhythm = detected.forGameplay()
        val beats = rhythm.beats
        require(beats.size >= 4) { "This recording has no clear rhythm to create a level. Try another song." }
        val flux = onsets(features)
        fun frame(time: Double) = (time * FPS).roundToInt().coerceIn(0, features.frames - 1)
        // The strongest attack within 40 ms, since a beat and its onset rarely share a frame.
        fun hit(time: Double) = (frame(time) - 2..frame(time) + 2).maxOf { flux[it.coerceIn(0, flux.lastIndex)] }
        fun loud(time: Double) = features.energy[frame(time)]
        // What counts as a strong or weak attack is relative to this song's own beats.
        val beatHits = beats.map(::hit).sorted()
        val strong = beatHits[beatHits.size * 3 / 4]
        val weak = beatHits[beatHits.size / 4]
        val downbeats = rhythm.downbeats.takeIf { it.size >= 2 } ?: beats.filterIndexed { i, _ -> i % 4 == 0 }.toDoubleArray()
        val downbeatSet = downbeats.toHashSet()
        val intensity = barIntensity(features, downbeats)
        fun barOf(time: Double) = (downbeats.indexOfLast { it <= time }).coerceAtLeast(0)

        val notes = ArrayList<Chart.Note>()
        var step = 0
        var holdUntil = -1.0
        var lastHold = -100.0
        var lastChord = -100.0
        for ((k, time) in beats.withIndex()) {
            val period = (beats.getOrElse(k + 1) { time + 60 / rhythm.bpm } - time).coerceIn(.25, 1.5)
            if (time < 1.0 || time > features.duration - .8 || time < holdUntil || loud(time) < SILENT) continue
            val bar = barOf(time)
            val level = intensity[bar]
            if (level < .08) continue
            val pattern = patterns[(seed + bar / 2).mod(patterns.size)]
            val lane = pattern[step++ % pattern.size]
            val downbeat = time in downbeatSet
            // A downbeat followed by quiet beats that still ring becomes a hold, now and then.
            val length = minOf(period * 2 - .25, 1.6)
            if (downbeat && level >= .3 && length >= .5 && time - lastHold > period * 16 &&
                hit(time + period) < weak && hit(time + 2 * period) < weak * 1.2 && loud(time + period * 1.5) > loud(time) * .5) {
                notes.add(Chart.Note(time, lane, "hold", length))
                holdUntil = time + length + .2
                lastHold = time
                continue
            }
            notes.add(Chart.Note(time, lane))
            if (downbeat && level >= .75 && hit(time) >= strong && time - lastChord >= period * 8) {
                notes.add(Chart.Note(time, if (lane < 2) 2 + step % 2 else step % 2))
                lastChord = time
            }
            val half = time + period / 2
            if (level >= .55 && period / 2 >= .21 && half < features.duration - .8 && hit(half) >= strong)
                notes.add(Chart.Note(half, pattern[step++ % pattern.size]))
        }
        val rounded = notes.map { it.copy(time = Math.round(it.time * 1000) / 1000.0, duration = Math.round(it.duration * 1000) / 1000.0) }
        return ChartStructure.describe(features, detected, ThumbChart.makePlayable(rounded), METHOD)
    }

    /** How much the sound rises from each frame to the next: high where notes and drums start. */
    private fun onsets(features: MusicFeatures): FloatArray {
        val bins = MusicFeatures.BINS
        val flux = FloatArray(features.frames)
        for (f in 1 until features.frames) {
            var sum = 0f
            for (b in 0 until bins) {
                val rise = features.mel[f * bins + b] - features.mel[(f - 1) * bins + b]
                if (rise > 0) sum += rise
            }
            flux[f] = sum
        }
        return flux
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
}
