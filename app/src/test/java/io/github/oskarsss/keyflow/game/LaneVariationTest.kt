package io.github.oskarsss.keyflow.game

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class LaneVariationTest {
    @Test
    fun replayChangesLanesWithoutChangingRhythmOrNoteTypes() {
        val source = (0..30).map { GameSession.Note(1.0 + it, it % 4) } +
            listOf(GameSession.Note(40.0, 0), GameSession.Note(40.0, 2), GameSession.Note(41.0, 1, "slide", 1.0, 3))
        val first = TileVariety.shuffleLanes(source, Random(4))
        val second = TileVariety.shuffleLanes(source, Random(5), first.key)
        assertNotEquals(first.key, second.key)
        assertNotEquals(first.notes.map { it.lane }, second.notes.map { it.lane })
        for (layout in listOf(first, second)) {
            assertEquals(source.map { it.time }, layout.notes.map { it.time })
            assertEquals(source.map { it.kind }, layout.notes.map { it.kind })
            assertEquals(source.map { it.duration }, layout.notes.map { it.duration })
            assertEquals(2, layout.notes.count { it.time == 40.0 })
        }
    }

    @Test
    fun laneChangesKeepOverlappingSlidePathsAwayFromOtherNotes() {
        val source = listOf(GameSession.Note(1.0, 0, "slide", 2.0, 1), GameSession.Note(2.0, 3))
        repeat(50) {
            val layout = TileVariety.shuffleLanes(source, Random(it))
            val slide = layout.notes[0]
            assertTrue(layout.notes[1].lane !in minOf(slide.lane, slide.endLane)..maxOf(slide.lane, slide.endLane))
        }
        val simultaneous = listOf(GameSession.Note(1.0, 0), GameSession.Note(1.0, 1, "slide", 2.0, 2))
        repeat(30) {
            val layout = TileVariety.shuffleLanes(simultaneous, Random(it))
            val slide = layout.notes.first { n -> n.kind == "slide" }
            val tap = layout.notes.first { n -> n.kind == "tap" }
            assertTrue(tap.lane !in minOf(slide.lane, slide.endLane)..maxOf(slide.lane, slide.endLane))
        }
    }

    @Test
    fun evenASingleLaneChartMovesToADifferentLaneOnReplay() {
        val source = (0..10).map { GameSession.Note(1.0 + it, 1) }
        var previous = TileVariety.shuffleLanes(source, Random(1))
        repeat(30) {
            val next = TileVariety.shuffleLanes(source, Random(it), previous.key)
            assertNotEquals(previous.notes[0].lane, next.notes[0].lane)
            previous = next
        }
    }
}
