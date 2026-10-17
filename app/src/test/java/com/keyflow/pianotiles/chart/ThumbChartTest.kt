package com.keyflow.pianotiles.chart

import com.keyflow.pianotiles.game.GameSession
import com.keyflow.pianotiles.game.TileVariety
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class ThumbChartTest {
    @Test
    fun evenLegacyFourTileChordsAndCrossingSlidesUseAtMostTwoHands() {
        val source = (0..3).map { Chart.Note(2.0, it) } + listOf(
            Chart.Note(3.0, 0, "slide", 2.0, 3),
            Chart.Note(3.2, 1), Chart.Note(3.2, 2), Chart.Note(3.2, 3),
            Chart.Note(3.5, 0, "hold", 2.0), Chart.Note(3.5, 3, "hold", 2.0),
            Chart.Note(4.0, 1), Chart.Note(4.0, 2),
        )
        val playable = ThumbChart.makePlayable(source)
        assertEquals(2, playable.count { it.time == 2.0 })
        assertTrue(playable.filter { it.kind == "slide" }.all { it.endLane / 2 == it.lane / 2 && kotlin.math.abs(it.endLane - it.lane) <= 1 })
        for (note in playable) {
            val active = playable.filter { it.time <= note.time && it.time + maxOf(it.duration, .18) > note.time }
            assertTrue("More than two simultaneous actions: $active", active.size <= 2)
            assertEquals(active.size, active.map { it.lane / 2 }.distinct().size)
        }
        val sessionNotes = playable.map { GameSession.Note(it.time, it.lane, it.kind, it.duration, it.endLane) }
        for (seed in 0 until 20) {
            val shuffled = TileVariety.shuffleLanes(sessionNotes, Random(seed)).notes
            for (moment in shuffled.groupBy { it.time }.values) assertEquals(moment.size, moment.map { it.lane / 2 }.distinct().size)
        }
    }

    @Test
    fun randomDecorationCannotCreateNewPlayableAttackTimesOrThirdFingerRequirements() {
        val source = (0 until 100).map { GameSession.Note(2.0 + it * .4, intArrayOf(0, 2, 1, 3)[it % 4]) }
        val result = TileVariety.decorate(source, 20, 45)
        assertEquals(source.map { it.time }.toSet(), result.filter { it.kind != GameSession.AVOID }.map { it.time }.toSet())
        assertTrue(result.filter { it.kind != GameSession.AVOID }.groupBy { it.time }.values.all { it.size <= 2 })
    }
}
