package com.keyflow.pianotiles

import kotlin.math.*

/** Whole-song metadata for highlights and scrolling; never chooses or moves the learned notes. */
object ChartStructure {
    fun describe(features: MusicFeatures, detected: MusicalRhythm, learned: List<AudioChart.Note>): AudioChart.Chart {
        val rhythm = detected.forGameplay()
        val notes = learned.filter {
            features.energy[(it.time * 50).toInt().coerceIn(0, features.frames - 1)] >= .0008f
        }
        check(notes.isNotEmpty()) { "This recording has no clear audible notes to play." }
        val boundaries = (listOf(0.0) + rhythm.downbeats.filterIndexed { index, _ -> index % 2 == 0 } + listOf(features.duration))
            .distinct().sorted()
        val ranges = boundaries.zipWithNext().filter { it.second - it.first >= .5 }
        val energy = ranges.map { (start, end) ->
            val a = (start * 50).toInt().coerceIn(0, features.frames - 1)
            val b = (end * 50).toInt().coerceIn(a + 1, features.frames)
            (a until b).sumOf { features.energy[it].toDouble() } / (b - a)
        }
        val activity = ranges.map { (start, end) -> notes.count { it.time >= start && it.time < end } / (end - start) }
        fun rank(values: List<Double>, value: Double) =
            (values.count { it < value * .98 } + .5 * values.count { abs(it - value) <= maxOf(1e-9, value * .02) }) / values.size.coerceAtLeast(1)
        val families = mutableListOf<DoubleArray>()
        val loudest = energy.maxOrNull()?.coerceAtLeast(.000001) ?: 1.0
        val sections = ranges.mapIndexed { index, (start, end) ->
            val descriptor = DoubleArray(96)
            for (part in 0 until 8) {
                val a = ((start + (end - start) * part / 8) * 50).toInt().coerceIn(0, features.frames - 1)
                val b = ((start + (end - start) * (part + 1) / 8) * 50).toInt().coerceIn(a + 1, features.frames)
                for (frame in a until b) for (pitch in 0 until 12)
                    descriptor[part * 12 + pitch] += features.chroma[frame * 12 + pitch] / (b - a)
            }
            fun similarity(other: DoubleArray): Double {
                var dot = 0.0; var aa = 0.0; var bb = 0.0
                for (i in descriptor.indices) { dot += descriptor[i] * other[i]; aa += descriptor[i] * descriptor[i]; bb += other[i] * other[i] }
                return if (aa * bb > 1e-12) dot / sqrt(aa * bb) else 0.0
            }
            val family = families.indices.filter { similarity(families[it]) >= .95 }.maxByOrNull { similarity(families[it]) }
                ?: families.size.also { families.add(descriptor) }
            val intensity = if (energy[index] < loudest * .07) 0.0 else
                (.6 * rank(energy, energy[index]) + .3 * rank(activity, activity[index]) + .1 * energy[index] / loudest).coerceIn(0.0, 1.0)
            AudioChart.Section(start, end, family, intensity)
        }
        val phase = rhythm.downbeats.firstOrNull()?.let { rhythm.beats.binarySearch(it).mod(4) } ?: 0
        return AudioChart.Chart(features.duration, ManiaChartModel.METHOD, notes, rhythm.beats.toList(), phase,
            rhythm.bpm, rhythm.downbeats.toList(), sections)
    }
}
