package io.github.oskarsss.keyflow.chart

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickBeatTest {
    /** A 40 s click track at [bpm]; every fourth click hits the low end harder. */
    private fun clicks(bpm: Double, start: Double): MusicFeatures {
        val frames = 40 * 50
        val mel = FloatArray(frames * MusicFeatures.BINS)
        var t = start
        var n = 0
        while (t < 39.5) {
            val f = Math.round(t * 50).toInt()
            for (b in 0 until MusicFeatures.BINS) mel[f * MusicFeatures.BINS + b] = if (b < 10 && n % 4 == 1) 4f else 2f
            t += 60 / bpm
            n++
        }
        return MusicFeatures(mel, FloatArray(frames * 12), FloatArray(frames) { .02f }, 40.0)
    }

    @Test
    fun findsTheTempoAndPutsBeatsOnTheClicks() {
        for (bpm in listOf(96.0, 120.0, 150.0)) {
            val rhythm = QuickBeat.analyze(clicks(bpm, .3))
            assertEquals(bpm, rhythm.bpm, bpm * .03)
            val period = 60 / bpm
            // Each beat sits within a frame of a click.
            assertTrue(rhythm.beats.all { beat -> abs(((beat - .3) / period) - Math.round((beat - .3) / period)) * period < .021 })
            // The bar starts where the low end hits hardest.
            assertTrue(rhythm.downbeats.all { abs(((it - .3 - period) / (4 * period)) - Math.round((it - .3 - period) / (4 * period))) * 4 * period < .021 })
        }
    }
}
