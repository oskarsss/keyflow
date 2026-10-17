package com.keyflow.pianotiles.chart

/**
 * A prepared level: tiles, the musical clock they follow, and the song's sections. [beats] are beat
 * times in seconds; every fourth beat from [barPhase] starts a bar.
 */
data class Chart(
    val duration: Double,
    val method: String,
    val notes: List<Note>,
    val beats: List<Double> = emptyList(),
    val barPhase: Int = 0,
    val bpm: Double = 0.0,
    val downbeats: List<Double> = emptyList(),
    val sections: List<Section> = emptyList(),
) {
    data class Note(
        val time: Double,
        val lane: Int,
        val kind: String = "tap",
        val duration: Double = 0.0,
        val endLane: Int = lane,
    )

    data class Section(val start: Double, val end: Double, val family: Int, val intensity: Double)
}
