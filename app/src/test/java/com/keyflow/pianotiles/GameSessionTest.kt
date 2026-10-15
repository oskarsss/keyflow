package com.keyflow.pianotiles

import org.junit.Assert.*
import org.junit.Test

class GameSessionTest {
    private fun song(count: Int) =
        GameSession((0 until count).map { GameSession.Note(it + 1.0, it % 4) })

    @Test
    fun fullSongUnlocksWithThreeStars() {
        val game = song(48)
        for (i in 0 until 48) assertEquals(1, game.tap(i % 4, i + 1.0))
        game.advance(52.0)
        assertTrue(game.passed())
        assertEquals(3, game.stars())
        assertEquals(48, game.maxCombo)
        assertEquals(1.0, game.accuracy(), .0001)
        assertFalse(game.failed())
    }

    @Test
    fun duplicateTapsCannotEarnExtraHits() {
        val game = song(1)
        game.tap(0, 1.0)
        game.tap(0, 1.0)
        assertEquals(1, game.hits)
        assertEquals(1, game.misses)
        assertEquals(0, game.combo)
    }

    @Test
    fun lateNotesExpireOnlyOnce() {
        val game = song(12)
        game.advance(9.3)
        game.advance(9.3)
        assertEquals(9, game.misses)
        assertTrue(game.failed())
        assertFalse(game.passed())
    }

    @Test
    fun tapsMatchLaneAndClosestNote() {
        val game = GameSession(listOf(GameSession.Note(1.0, 0), GameSession.Note(1.2, 0)))
        assertEquals(1, game.tap(0, 1.16))
        assertEquals(0, game.notes[0].judgement)
        assertEquals(1, game.notes[1].judgement)
        assertEquals(3, game.tap(1, 1.16))
    }

    @Test
    fun touchingATileEarlyConsumesItWithOnlyOnePenalty() {
        for (kind in listOf("tap", "hold", "slide", GameSession.GOLD, GameSession.AVOID)) {
            val note = GameSession.Note(3.0, 0, kind, if (kind in listOf("hold", "slide")) 1.0 else 0.0)
            val next = GameSession.Note(3.5, 0)
            val game = GameSession(listOf(note, next))
            assertEquals(3, game.press(0, 2.0, 7, target = note))
            assertEquals(3, note.judgement)
            assertEquals(0, game.hits)
            assertNull(game.activeKind(7))
            game.move(7, .5f, 3.0)
            game.release(7, 3.0)
            game.advance(3.2)
            assertEquals(1, game.misses)
            assertEquals(GameSession.MAX_LIVES - 1, game.lives)
            assertEquals(0, game.bonuses)
            assertEquals(0, game.dodged)
            assertEquals(1, game.tap(0, 3.5))
        }
    }

    @Test
    fun touchingALaterTileDoesNotConsumeTheClosestTile() {
        val first = GameSession.Note(2.1, 0)
        val later = GameSession.Note(3.0, 0)
        val game = GameSession(listOf(first, later))
        assertEquals(3, game.press(0, 2.0, 7, target = later))
        assertEquals(0, first.judgement)
        assertEquals(1, game.tap(0, 2.1))
        game.advance(3.3)
        assertEquals(1, game.misses)
    }

    @Test
    fun touchedTileExpiringDuringInputOnlyCostsOneLife() {
        for (kind in listOf("tap", GameSession.GOLD, GameSession.AVOID)) {
            val note = GameSession.Note(2.0, 0, kind)
            val game = GameSession(listOf(note))
            assertEquals(3, game.press(0, 2.2, 7, target = note))
            game.advance(3.0)
            assertEquals(1, game.misses)
            assertEquals(0, game.dodged)
            assertEquals(GameSession.MAX_LIVES - 1, game.lives)
        }
    }

    @Test
    fun goodHitsAndPassBoundaryAreSeparate() {
        val game = song(8)
        for (i in 0 until 6) assertEquals(2, game.tap(i % 4, i + 1.12))
        game.advance(12.0)
        assertTrue(game.passed())
        assertEquals(1, game.stars())
        assertEquals(.525, game.accuracy(), .0001)
        val below = song(8)
        for (i in 0 until 5) below.tap(i % 4, i + 1.0)
        below.advance(12.0)
        assertFalse(below.passed())
        assertEquals(0, below.stars())
    }

    @Test
    fun visualPositionMatchesAudioClock() {
        assertEquals(800f, TileScheduler.bottom(5.0, 5.0, 100f, 800f, 2.0), .001f)
        assertEquals(100f, TileScheduler.bottom(5.0, 3.0, 100f, 800f, 2.0), .001f)
        assertEquals(450f, TileScheduler.bottom(5.0, 4.0, 100f, 800f, 2.0), .001f)
    }

    @Test
    fun eightMissesFailEvenAfterHittingEnoughNotes() {
        val game = song(48)
        for (i in 0 until 36) game.tap(i % 4, i + 1.0)
        repeat(8) { game.tap(3, 36.3) }
        assertTrue(game.failed())
        assertFalse(game.passed())
        assertEquals(0, game.stars())
    }

    @Test
    fun holdMustStayDownUntilTheTail() {
        val game = GameSession(listOf(GameSession.Note(1.0, 0, "hold", 1.0)))
        assertEquals(4, game.press(0, 1.0, 7))
        game.advance(1.5)
        assertEquals(0, game.hits)
        game.release(7, 1.95)
        assertEquals(1, game.hits)
        assertEquals(0, game.misses)
        val early = GameSession(listOf(GameSession.Note(1.0, 0, "hold", 1.0)))
        early.press(0, 1.0, 7)
        early.release(7, 1.3)
        assertEquals(1, early.misses)
        assertEquals(0, early.hits)
    }

    @Test
    fun slideTracksTheMovingLaneAndFailsIfFingerStops() {
        val game = GameSession(listOf(GameSession.Note(1.0, 0, "slide", 1.0, 3)))
        game.press(0, 1.0, 2)
        for (i in 1..10) game.move(2, .5f + i * .3f, 1.0 + i * .1)
        assertEquals(1, game.hits)
        assertEquals(0, game.misses)
        val stopped = GameSession(listOf(GameSession.Note(1.0, 0, "slide", 1.0, 3)))
        stopped.press(0, 1.0, 2)
        stopped.advance(1.5)
        stopped.advance(1.8)
        assertEquals(1, stopped.misses)
    }

    @Test
    fun aBriefSlideExcursionCanRecoverWhileAProlongedExitFailsOnce() {
        val note = GameSession.Note(1.0, 0, "slide", 2.0, 1)
        val game = GameSession(listOf(note))
        game.press(0, 1.0, 7)
        game.move(7, 3.5f, 1.4)
        game.advance(1.6)
        assertEquals(0, game.misses)
        game.move(7, note.laneAt(1.61), 1.61)
        game.move(7, note.laneAt(2.0) + .85f, 2.0)
        game.advance(2.01)
        assertEquals(0, game.misses)
        game.move(7, 3.5f, 2.1)
        game.advance(2.4)
        game.advance(4.0)
        assertEquals(1, game.misses)
    }

    @Test
    fun multiTouchAndPauseRegripPreserveHolds() {
        val game =
            GameSession(
                listOf(
                    GameSession.Note(1.0, 0, "hold", 1.0),
                    GameSession.Note(1.0, 3, "hold", 1.0),
                ),
            )
        game.press(0, 1.0, 7)
        game.press(3, 1.0, 8)
        game.suspendGestures()
        game.press(0, 1.5, 9)
        game.press(3, 1.5, 10)
        game.release(9, 1.95)
        game.release(10, 1.95)
        assertEquals(2, game.hits)
        assertEquals(0, game.misses)
    }

    @Test
    fun emptyDraggingDoesNotHitTilesOrCostLives() {
        val game = GameSession(listOf(GameSession.Note(5.0, 1)))
        assertEquals(0, game.move(1, .5f, 1.0))
        assertEquals(0, game.misses)
        assertEquals(0, game.move(1, 1.5f, 5.0))
        assertEquals(0, game.hits)
        assertEquals(1, game.press(1, 5.0, 1))
        assertEquals(1, game.hits)
    }

    @Test
    fun completedGestureCannotDragIntoTheNextTile() {
        for (kind in listOf("hold", "slide")) {
            for (nextKind in listOf("tap", GameSession.GOLD, GameSession.AVOID)) {
                val endLane = if (kind == "slide") 3 else 0
                val next = GameSession.Note(2.1, endLane, nextKind)
                val game = GameSession(listOf(GameSession.Note(1.0, 0, kind, 1.0, endLane), next))
                game.press(0, 1.0, 7)
                for (i in 1..20) game.move(7, .5f + endLane * i / 20f, 1.0 + i * .05)
                assertEquals(1, game.hits)
                // More MOVE events arrive while the finger is still down, inside the next window.
                repeat(3) { assertEquals(0, game.move(7, endLane + .5f, 2.02 + it * .01)) }
                assertEquals(0, next.judgement)
                assertEquals(0, game.misses)
                game.release(7, 2.05)
                assertEquals(if (nextKind == GameSession.AVOID) 3 else 1, game.press(endLane, 2.1, 7))
            }
        }
    }

    @Test
    fun failedGestureCannotDragIntoAnotherTileAndOtherFingersStillWork() {
        val next = GameSession.Note(1.9, 0)
        val chord = GameSession.Note(1.1, 2)
        val game = GameSession(listOf(GameSession.Note(1.0, 0, "slide", 1.0, 3), chord, next))
        game.press(0, 1.0, 7)
        assertEquals(1, game.press(2, 1.1, 8))
        game.advance(1.5)
        game.advance(1.8)
        assertEquals(1, game.misses)
        assertEquals(0, game.move(7, .5f, 1.85))
        assertEquals(0, next.judgement)
        game.release(7, 1.86)
        assertEquals(1, game.press(0, 1.9, 7))
    }

    @Test
    fun hitStreaksRestoreLostLivesUpToTheMaximum() {
        val game = song(80)
        repeat(3) { assertEquals(3, game.tap(3, 0.0)) }
        assertEquals(GameSession.MAX_LIVES - 3, game.lives)
        for (i in 0 until 25) game.tap(i % 4, i + 1.0)
        assertEquals(GameSession.MAX_LIVES - 2, game.lives)
        for (i in 25 until 80) game.tap(i % 4, i + 1.0)
        assertEquals(GameSession.MAX_LIVES, game.lives)
        assertEquals(3, game.misses)
        assertFalse(game.failed())
    }
}
