package io.github.oskarsss.keyflow.chart

import kotlin.math.abs

/** Musical pulse and meter, independent of gameplay density or lane assignment. */
data class MusicalRhythm(val beats: DoubleArray, val downbeats: DoubleArray) {
    val bpm: Double get() {
        val gaps = beats.asSequence().zipWithNext { a, b -> b - a }.filter { it in .25..1.5 }.sorted().toList()
        return if (gaps.isEmpty()) 120.0 else 60 / gaps[gaps.size / 2]
    }

    /** Stable bar anchors and a comfortable musical pulse, including compound 6/8 and fast rock. */
    fun forGameplay(): MusicalRhythm {
        if (beats.size < 4) return this
        val indices = downbeats.map { beats.binarySearch(it) }.filter { it >= 0 }.distinct().sorted()
        val gaps = indices.zipWithNext { a, b -> b - a }
        val meter = listOf(2, 3, 4, 6).maxBy { candidate -> gaps.count { it == candidate } + if (candidate == 4) .1 else 0.0 }
        val anchors = if (indices.size < 2) (beats.indices step meter).toList() else {
            // Choose a consistent chain rather than treating a spurious half-bar prediction as a new bar.
            val score = DoubleArray(indices.size) { 1.0 }
            val previous = IntArray(indices.size) { -1 }
            for (i in indices.indices) for (j in 0 until i) {
                val gap = indices[i] - indices[j]
                val count = maxOf(1, kotlin.math.round(gap.toDouble() / meter).toInt())
                val penalty = .9 * abs(gap - count * meter) + .15 * (count - 1)
                val value = score[j] + 1 - penalty
                if (value > score[i]) { score[i] = value; previous[i] = j }
            }
            var index = score.indices.maxBy { score[it] }
            val chain = mutableListOf<Int>()
            while (index >= 0) { chain.add(indices[index]); index = previous[index] }
            chain.reverse()
            val filled = mutableListOf<Int>()
            var next = chain.first() % meter
            for (anchor in chain) {
                while (next < anchor - meter / 2) { filled.add(next); next += meter }
                filled.add(anchor)
                next = anchor + meter
            }
            while (next < beats.size) { filled.add(next); next += meter }
            filled.distinct().sorted()
        }
        val fold = when {
            meter == 6 && bpm > 160 -> 3
            bpm > 165 -> 2
            else -> 1
        }
        val cleanedDownbeats = anchors.map { beats[it] }.toDoubleArray()
        if (fold == 1) return MusicalRhythm(beats, cleanedDownbeats)
        val pulse = mutableListOf<Double>()
        val boundaries = (listOf(0) + anchors + listOf(beats.size)).distinct().sorted()
        val normalGap = 60 / bpm
        for ((a, b) in boundaries.zipWithNext()) {
            val start = beats[a]
            val end = beats.getOrElse(b) { beats.last() + normalGap }
            val count = maxOf(1, kotlin.math.round((b - a).toDouble() / fold).toInt())
            for (part in 0 until count) {
                val target = start + (end - start) * part / count
                val found = beats.binarySearch(target)
                val insertion = if (found >= 0) found else -found - 1
                val near = listOf(maxOf(0, insertion - 1), minOf(beats.lastIndex, insertion)).minBy { abs(beats[it] - target) }
                pulse.add(if (abs(beats[near] - target) < normalGap * .35) beats[near] else target)
            }
        }
        val newBeats = pulse.distinct().sorted().toDoubleArray()
        val newDownbeats = cleanedDownbeats.map { down -> newBeats.minBy { abs(it - down) } }.distinct().toDoubleArray()
        return MusicalRhythm(newBeats, newDownbeats)
    }

    companion object {
        fun chunkStarts(frames: Int): List<Int> {
            require(frames > 0)
            val starts = generateSequence(-6) { it + 1488 }.takeWhile { it < frames - 6 }.toMutableList()
            if (frames > 1488) starts[starts.lastIndex] = frames - 1494
            return starts
        }

        /** Upstream minimal postprocessor: 7-frame maxima, logit > 0, adjacent plateaus merged. */
        fun peaks(logits: FloatArray): DoubleArray {
            require(logits.all { it.isFinite() }) { "The rhythm model produced invalid predictions." }
            val indices = ArrayList<Int>()
            for (i in logits.indices) {
                if (logits[i] <= 0) continue
                if ((maxOf(0, i - 3)..minOf(logits.lastIndex, i + 3)).any { logits[it] > logits[i] }) continue
                indices.add(i)
            }
            if (indices.isEmpty()) return DoubleArray(0)
            val result = ArrayList<Double>()
            var average = indices.first().toDouble()
            var count = 1
            for (index in indices.drop(1)) {
                if (index - average <= 1) { count++; average += (index - average) / count }
                else { result.add(average / 50); average = index.toDouble(); count = 1 }
            }
            result.add(average / 50)
            return result.toDoubleArray()
        }

        fun fromLogits(beat: FloatArray, downbeat: FloatArray): MusicalRhythm {
            val beats = peaks(beat)
            val downbeats = if (beats.isEmpty()) DoubleArray(0) else peaks(downbeat).map { d ->
                val found = beats.binarySearch(d)
                if (found >= 0) beats[found] else {
                    val index = -found - 1
                    val before = beats[maxOf(0, index - 1)]
                    val after = beats[minOf(beats.lastIndex, index)]
                    if (abs(before - d) <= abs(after - d)) before else after
                }
            }.distinct().sorted().toDoubleArray()
            return MusicalRhythm(beats, downbeats)
        }
    }
}
