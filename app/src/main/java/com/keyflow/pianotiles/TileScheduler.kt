package com.keyflow.pianotiles

/**
 * Adapted from sandeepsj/piano-falling-tiles, TileScheduler.kt (MIT). Copyright (c) 2025 sandeepsj.
 * See THIRD_PARTY_NOTICES.md. The bottom edge reaches the strike line exactly at the note's
 * audio-clock time.
 */
object TileScheduler {
    fun bottom(
        noteTime: Double,
        songTime: Double,
        top: Float,
        line: Float,
        lookAhead: Double,
    ): Float = (line - (noteTime - songTime) * (line - top) / lookAhead).toFloat()
}
