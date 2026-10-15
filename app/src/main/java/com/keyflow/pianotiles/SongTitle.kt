package com.keyflow.pianotiles

/** Keep a small hint for locked levels without revealing short titles or splitting Unicode pairs. */
object SongTitle {
    fun display(title: String, unlocked: Boolean): String {
        if (unlocked) return title
        return Regex("\\S+").replace(title) { word ->
            val points = word.value.codePoints().toArray()
            val visible = when {
                points.size >= 3 -> 2
                points.size >= 2 -> 1
                else -> 0
            }
            String(points, 0, visible) + "*".repeat(points.size - visible)
        }
    }
}
