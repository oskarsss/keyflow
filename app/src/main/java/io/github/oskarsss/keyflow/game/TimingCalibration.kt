package io.github.oskarsss.keyflow.game

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Learns the timing offset from where the player's hits land. Speakers, Bluetooth, the touch
 * screen and the display all add delay that differs per phone, so a fixed offset is never right.
 * A consistent lean early or late is device delay, not skill; random spread is skill.
 */
object TimingCalibration {
    const val MIN_HITS = 20
    const val RANGE_MS = 300

    /** Leans smaller than this are within the spread of human tapping. */
    private const val DEAD_ZONE_MS = 10.0
    private const val GAIN = .7
    private const val MAX_STEP_MS = 60.0

    /** The median signed error in real milliseconds, or null when a run has too few hits. */
    fun lean(errorsMs: List<Double>, speed: Float = 1f): Double? {
        if (errorsMs.size < MIN_HITS) return null
        val sorted = errorsMs.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        return median / speed
    }

    /** The offset after a run that leaned [leanMs] (positive = late). */
    fun adjust(offsetMs: Int, leanMs: Double?): Int {
        if (leanMs == null || abs(leanMs) < DEAD_ZONE_MS) return offsetMs
        val step = (leanMs * GAIN).coerceIn(-MAX_STEP_MS, MAX_STEP_MS)
        return (offsetMs - step).roundToInt().coerceIn(-RANGE_MS, RANGE_MS)
    }
}
