package com.keyflow.pianotiles

import org.junit.Assert.*
import org.junit.Test

class ChordBonusTest {
    @Test
    fun synchronizedDoublesAwardBonusOnlyOnce() {
        val game = GameSession(listOf(GameSession.Note(1.0, 0), GameSession.Note(1.0, 3)))
        game.press(0, .99, 1)
        game.press(3, 1.02, 2)
        assertEquals(1, game.chordBonuses)
        assertEquals(100, game.lastChordBonus)
        assertEquals(300, game.score)
        assertArrayEquals(intArrayOf(0, 3), game.lastChordLanes)
        game.press(3, 1.03, 2)
        game.advance(2.0)
        assertEquals(1, game.chordBonuses)
        assertEquals(300, game.score)
    }

    @Test
    fun staggeredOrIncompleteChordsDoNotEarnSynchronizationBonus() {
        val staggered = GameSession(listOf(GameSession.Note(1.0, 0), GameSession.Note(1.0, 3)))
        staggered.press(0, .93, 1)
        staggered.press(3, 1.07, 2)
        assertEquals(2, staggered.hits)
        assertEquals(0, staggered.chordBonuses)
        val missed = GameSession(listOf(GameSession.Note(1.0, 0), GameSession.Note(1.0, 3)))
        missed.press(0, 1.0, 1)
        missed.advance(1.3)
        assertEquals(0, missed.chordBonuses)
    }

    @Test
    fun triplesAwardLargerBonusesAndBombsAreExcluded() {
        val game = GameSession((0..2).map { GameSession.Note(1.0, it) } + GameSession.Note(1.0, 3, GameSession.AVOID))
        repeat(3) { game.press(it, 1.0 + it * .01, it) }
        assertEquals(200, game.lastChordBonus)
        assertEquals(500, game.score)
        game.advance(1.3)
        assertEquals(1, game.dodged)
        assertEquals(1, game.chordBonuses)
    }
}
