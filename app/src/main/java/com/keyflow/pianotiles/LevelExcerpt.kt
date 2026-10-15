package com.keyflow.pianotiles

/** One continuous, bar-aligned musical highlight. The original audio remains intact. */
object LevelExcerpt {
    data class Range(val start: Double, val end: Double)

    fun choose(duration: Double, sections: List<AudioChart.Section>, downbeats: List<Double>): Range? {
        if (!duration.isFinite() || duration <= 150) return null
        val boundaries = (listOf(0.0) + downbeats.filter { it >= 0 && it <= duration } + listOf(duration)).distinct().sorted()
        if (boundaries.size < 3) return Range(0.0, 150.0)
        var best: Range? = null
        var bestScore = Double.NEGATIVE_INFINITY
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
            val score = weighted / length + .35 * length / 150 + minOf(families.size, 6) * .015
            if (score > bestScore) { bestScore = score; best = Range(start, end) }
        }
        return best ?: Range(0.0, 150.0)
    }
}
