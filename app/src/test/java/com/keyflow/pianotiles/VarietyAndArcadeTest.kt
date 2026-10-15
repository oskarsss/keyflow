package com.keyflow.pianotiles

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VarietyAndArcadeTest {
    private fun chart(count: Int, gap: Double = .5) =
        (0 until count).map { GameSession.Note(1.0 + it * gap, it % 4) }

    @Test
    fun varietyIsDeterministicChronologicalAndCollisionFree() {
        val base = chart(200)
        val first = TileVariety.decorate(base, 5, 42)
        val second = TileVariety.decorate(base, 5, 42)
        assertEquals(first.map { "${it.time}/${it.lane}/${it.kind}" }, second.map { "${it.time}/${it.lane}/${it.kind}" })
        assertTrue(first.zipWithNext().all { (a, b) -> a.time <= b.time })
        assertTrue(first.any { it.kind == GameSession.GOLD })
        assertTrue(first.any { it.kind == GameSession.AVOID })
        assertTrue(first.groupBy { it.time }.values.any { same -> same.count { it.kind != GameSession.AVOID } == 2 })
        for (time in first.groupBy { it.time }.values) assertEquals(time.size, time.map { it.lane }.toSet().size)
        // Every original note is still there, so the music-synced chart is never thinned out.
        assertEquals(base.size, first.count { it.kind != GameSession.AVOID } - first.groupBy { it.time }.values.sumOf { g -> g.count { it.kind != GameSession.AVOID } - 1 })
    }

    @Test
    fun firstLevelOnlyAddsGoldTiles() {
        val notes = TileVariety.decorate(chart(120), 0, 7)
        assertEquals(120, notes.size)
        assertTrue(notes.none { it.kind == GameSession.AVOID })
    }

    @Test
    fun chartedChordsGrowWithTheLevel() {
        val base = (0 until 40).flatMap { i ->
            val time = 1.0 + i
            if (i % 4 == 0) (0..3).map { GameSession.Note(time, it) } else listOf(GameSession.Note(time, i % 4))
        }
        fun largest(level: Int) = TileVariety.decorate(base, level, 3)
            .filter { it.kind != GameSession.AVOID }
            .groupBy { it.time }.values.maxOf { it.size }
        assertEquals(2, largest(0))
        assertEquals(3, largest(1))
        assertEquals(4, largest(5))
        // Charted chords are kept as they are; no random extra chords are mixed in.
        val decorated = TileVariety.decorate(base, 5, 3).filter { it.kind != GameSession.AVOID }
        assertEquals(base.size, decorated.size)
    }

    @Test
    fun bombsCostALifeWhenTouchedAndNothingWhenDodged() {
        val game =
            GameSession(
                listOf(
                    GameSession.Note(1.0, 0),
                    GameSession.Note(1.0, 2, GameSession.AVOID),
                    GameSession.Note(2.0, 1, GameSession.AVOID),
                ),
            )
        assertEquals(1, game.tap(0, 1.0))
        assertEquals(3, game.tap(2, 1.0))
        game.advance(3.0)
        assertEquals(1, game.hits)
        assertEquals(1, game.misses)
        assertEquals(1, game.dodged)
        assertEquals(1, game.scored)
        assertTrue(game.passed())
    }

    @Test
    fun goldTilesPayABonusAndHeal() {
        val game = GameSession(listOf(GameSession.Note(1.0, 0), GameSession.Note(2.0, 1, GameSession.GOLD)))
        game.tap(3, 0.5)
        val lives = game.lives
        val before = game.score
        game.tap(0, 1.0)
        game.tap(1, 2.0)
        assertEquals(lives + 1, game.lives)
        assertTrue(game.score - before >= GameSession.GOLD_BONUS + 200)
    }

    @Test
    fun comboRaisesTheMultiplierUpToFour() {
        val game = GameSession(chart(45))
        assertEquals(1, game.multiplier)
        for (i in 0 until 10) game.tap(i % 4, 1.0 + i * .5)
        assertEquals(2, game.multiplier)
        for (i in 10 until 45) game.tap(i % 4, 1.0 + i * .5)
        assertEquals(4, game.multiplier)
        game.tap(0, 30.0)
        assertEquals(1, game.multiplier)
    }

    @Test
    fun timeAttackSessionsNeverRunOutOfLives() {
        val game = GameSession(chart(20), mortal = false)
        game.advance(60.0)
        assertEquals(20, game.misses)
        assertFalse(game.failed())
    }

    @Test
    fun segmentsStayInsideTheSongAndShortSongsPlayWhole() {
        assertEquals(0 to 0, Arcade.segment(50_000, Random(1)))
        repeat(50) {
            val (start, end) = Arcade.segment(200_000, Random(it))
            assertTrue(start >= 16_000)
            assertEquals(Arcade.SEGMENT_MS, end - start)
            assertTrue(end <= 195_000)
        }
        val clipped = Arcade.clip(chart(400, .25), 20_000, 65_000)
        assertTrue(clipped.all { it.time >= 20.4 && it.time <= 64.4 })
    }

    @Test
    fun runsCarryLivesAndSpeedUpInEndless() {
        val run = Arcade.Run(Arcade.Mode.ENDLESS, 0)
        val first = GameSession(chart(10), run.lives, true, run.combo)
        first.advance(10.0)
        run.absorb(first, 0)
        assertEquals(GameSession.MAX_LIVES - 8, run.lives)
        assertTrue(run.speed!! > 1f)
        assertTrue(run.travel!! < 1.75)
        val picks = (0 until 20).map {
            Arcade.pick(
                listOf(
                    Arcade.Entry("p", org.json.JSONObject().put("id", "a")),
                    Arcade.Entry("p", org.json.JSONObject().put("id", "b")),
                    Arcade.Entry("d", org.json.JSONObject().put("id", "c").put("demo", true)),
                ),
                listOf("a"),
                Random(it),
            )!!.id
        }
        assertTrue(picks.all { it == "b" })
    }
}
