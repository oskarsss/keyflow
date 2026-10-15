package com.keyflow.pianotiles

import org.junit.Assert.*
import org.junit.Test

class LevelExcerptTest {
    @Test
    fun highlightsPreferChorusesAndStayWithinTwoAndAHalfMinutesOnBarBoundaries() {
        val bars = (0..150).map { it * 2.0 }
        val sections = (0 until 75).map { i ->
            val start = i * 4.0
            AudioChart.Section(start, start + 4, if (start in 120.0..240.0) 1 else 0, if (start in 120.0..240.0) .95 else .15)
        }
        val range = LevelExcerpt.choose(300.0, sections, bars)!!
        assertTrue(range.start > 80)
        assertTrue(range.end - range.start in 120.0..150.0)
        assertTrue(range.start in bars && range.end in bars)
        assertTrue(range.start <= 120 && range.end >= 240)
        assertNull(LevelExcerpt.choose(120.0, sections, bars))
        val clipped = Arcade.clip((0 until 300).map { GameSession.Note(it.toDouble(), it % 4) }, 100_000, 250_000, 1.3)
        assertTrue(clipped.all { it.time >= 101.3 && it.time <= 249.4 })
    }

    @Test
    fun wholeSongTempoChangesScrollSpeedWithoutChangingAudioPlaybackRate() {
        val base = 1.7
        assertTrue(GameView.travelForTempo(base, 80.0) > base)
        assertTrue(GameView.travelForTempo(base, 180.0) < base)
        assertEquals(base, GameView.travelForTempo(base, 0.0), 1e-9)
        assertEquals(base, GameView.travelForTempo(base, Double.NaN), 1e-9)
    }
}
