package com.keyflow.pianotiles.chart

import kotlin.math.abs
import kotlin.random.Random

/** Continuous, bar-aligned musical highlights. The original audio remains intact. */
object LevelExcerpt {
    data class Range(val start: Double, val end: Double)

    /** Cuts this close to the best one's score are as good to play. */
    private const val MARGIN = .1
    /** Cuts start at least this far apart, so each play sounds different. */
    private const val APART = 15.0
    const val MAX_CUTS = 5

    fun choose(duration: Double, sections: List<Chart.Section>, downbeats: List<Double>): Range? =
        choices(duration, sections, downbeats).firstOrNull()

    /**
     * Up to [MAX_CUTS] highlights of at most 2:30, best first. Each one scores nearly as well as
     * the best, so any of them makes a good level. Songs of 2:30 or less have none: they play whole.
     */
    fun choices(duration: Double, sections: List<Chart.Section>, downbeats: List<Double>): List<Range> {
        if (!duration.isFinite() || duration <= 150) return emptyList()
        val boundaries = (listOf(0.0) + downbeats.filter { it >= 0 && it <= duration } + listOf(duration)).distinct().sorted()
        if (boundaries.size < 3) return listOf(Range(0.0, 150.0))
        val scored = ArrayList<Pair<Range, Double>>()
        for (start in boundaries) {
            val end = boundaries.lastOrNull { it <= start + 150 && it > start } ?: continue
            val length = end - start
            if (length < 120) continue
            var weighted = 0.0
            val families = mutableSetOf<Int>()
            for (section in sections) {
                val overlap = minOf(end, section.end) - maxOf(start, section.start)
                if (overlap <= 0) continue
                weighted += overlap * section.intensity
                families.add(section.family)
            }
            // Prefer a chorus plus its surrounding context, while keeping level lengths similar.
            scored.add(Range(start, end) to weighted / length + .35 * length / 150 + minOf(families.size, 6) * .015)
        }
        val best = scored.maxOfOrNull { it.second } ?: return listOf(Range(0.0, 150.0))
        val cuts = ArrayList<Range>()
        for ((range, score) in scored.sortedByDescending { it.second }) {
            if (score < best - MARGIN || cuts.size == MAX_CUTS) break
            if (cuts.all { abs(it.start - range.start) >= APART }) cuts.add(range)
        }
        return cuts
    }

    /** A random cut, other than [previous] when there is a choice. */
    fun pick(cuts: List<Range>, previous: Double?, random: Random): Range? {
        val fresh = cuts.filter { previous == null || abs(it.start - previous) > .01 }.ifEmpty { cuts }
        return fresh.randomOrNull(random)
    }
}
