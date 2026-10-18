package com.keyflow.pianotiles.library

/**
 * A locked level shows the first five characters of its title and *** for the rest. At least
 * three characters stay hidden, so a short title isn't given away; emoji stay whole.
 */
object SongTitle {
    private const val SHOWN = 5
    private const val HIDDEN = 3

    fun display(title: String, unlocked: Boolean): String {
        if (unlocked) return title
        val points = title.trim().codePoints().toArray()
        if (points.isEmpty()) return ""
        val visible = minOf(SHOWN, points.size - HIDDEN).coerceAtLeast(0)
        return String(points, 0, visible) + "***"
    }
}
