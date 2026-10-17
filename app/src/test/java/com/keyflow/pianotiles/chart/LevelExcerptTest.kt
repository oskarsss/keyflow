package com.keyflow.pianotiles.chart

import com.keyflow.pianotiles.arcade.Arcade
import com.keyflow.pianotiles.game.GameSession
import com.keyflow.pianotiles.game.GameView
import org.junit.Assert.*
import org.junit.Test

class LevelExcerptTest {
    @Test
    fun highlightsPreferChorusesAndStayWithinTwoAndAHalfMinutesOnBarBoundaries() {
        val bars = (0..150).map { it * 2.0 }
        val sections = (0 until 75).map { i ->
            val start = i * 4.0
            Chart.Section(start, start + 4, if (start in 120.0..240.0) 1 else 0, if (start in 120.0..240.0) .95 else .15)
        }
        val range = LevelExcerpt.choose(300.0, sections, bars)!!
        assertTrue(range.start > 80)
        assertTrue(range.end - range.start in 120.0..150.0)
        assertTrue(range.start in bars && range.end in bars)
        assertTrue(range.start <= 120 && range.end >= 240)
        assertNull(LevelExcerpt.choose(120.0, sections, bars))
        // The other cuts are nearly as good, start well apart and are all whole bars.
        val cuts = LevelExcerpt.choices(300.0, sections, bars)
        assertEquals(range, cuts.first())
        assertTrue(cuts.size in 2..LevelExcerpt.MAX_CUTS)
        for (cut in cuts) {
            assertTrue(cut.start in bars && cut.end in bars && cut.end - cut.start in 120.0..150.0)
            assertTrue(cuts.filter { it != cut }.all { kotlin.math.abs(it.start - cut.start) >= 15 })
        }
        val clipped = Arcade.clip((0 until 300).map { GameSession.Note(it.toDouble(), it % 4) }, 100_000, 250_000, 1.3)
        assertTrue(clipped.all { it.time >= 101.3 && it.time <= 249.4 })
    }

    @Test
    fun eachPlayTakesARandomCutOtherThanTheLastOne() {
        val cuts = listOf(LevelExcerpt.Range(10.0, 160.0), LevelExcerpt.Range(40.0, 190.0), LevelExcerpt.Range(70.0, 220.0))
        val picked = (0 until 60).map { LevelExcerpt.pick(cuts, 40.0, kotlin.random.Random(it))!! }.toSet()
        assertEquals(setOf(cuts[0], cuts[2]), picked)
        assertEquals(cuts[1], LevelExcerpt.pick(cuts.subList(1, 2), 40.0, kotlin.random.Random(1)))
        assertNull(LevelExcerpt.pick(emptyList(), null, kotlin.random.Random(1)))
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
