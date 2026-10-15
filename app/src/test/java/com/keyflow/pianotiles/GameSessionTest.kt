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
        stopped.advance(1.3)
        stopped.advance(1.5)
        assertEquals(1, stopped.misses)
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
    fun EmptyDraggingDoesNotCostLives() {
        val game = GameSession(listOf(GameSession.Note(5.0, 1)))
        assertEquals(0, game.move(1, .5f, 1.0))
        assertEquals(0, game.misses)
        assertEquals(1, game.move(1, 1.5f, 5.0))
        assertEquals(1, game.hits)
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
