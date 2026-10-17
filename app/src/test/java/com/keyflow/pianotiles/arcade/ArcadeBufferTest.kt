package com.keyflow.pianotiles.arcade

import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ArcadeBufferTest {
    private fun pool() = (0..7).map { Arcade.Entry("mix", JSONObject().put("id", "song$it")) }

    @Test
    fun arcadePlaysReachedLevelsAndBorrowsTheNearestLockedOnesWhileThereAreFew() {
        val pool = (0..11).map { Arcade.Entry("mix", JSONObject().put("id", "song$it")) }
        // Level 3 is next: 0..2 are cleared, 3 is playable and 4.. are locked.
        val distance = pool.indices.associate { "song$it" to it - 3 }
        assertEquals((0 until Arcade.MIN_POOL).map { "song$it" }, Arcade.eligible(pool, distance).map { it.id })
        val further = pool.indices.associate { "song$it" to it - 8 }
        assertEquals((0..8).map { "song$it" }, Arcade.eligible(pool, further).map { it.id })
        val demo = Arcade.Entry("demo", JSONObject().put("id", "demo").put("demo", true))
        assertFalse(Arcade.eligible(pool + demo, further).any { it.demo })
    }

    @Test
    fun keepsThreeFreshSongsReadyAndAddsOneForEverySongPlayed() {
        val pool = pool()
        val initial = Arcade.prefetch(pool, emptySet(), emptySet(), emptyList(), emptySet(), Random(2))
        assertEquals(Arcade.BUFFER, initial.size)
        assertEquals(Arcade.BUFFER, initial.map { it.id }.toSet().size)
        val ready = initial.map { it.id }.toMutableSet()
        assertTrue(Arcade.prefetch(pool, ready, emptySet(), emptyList(), emptySet(), Random(3)).isEmpty())
        val history = mutableListOf<String>()
        repeat(pool.size - Arcade.BUFFER) {
            history += ready.first { it !in history }
            val more = Arcade.prefetch(pool, ready, emptySet(), history, emptySet(), Random(4 + it))
            assertEquals("after ${history.size} songs", 1, more.size)
            assertTrue(more[0].id !in ready && more[0].id !in history)
            ready += more[0].id
        }
        // The whole pool is ready, so nothing more is downloaded however much is played.
        assertEquals(pool.size, ready.size)
        history += ready.first { it !in history }
        assertTrue(Arcade.prefetch(pool, ready, emptySet(), history, emptySet(), Random(9)).isEmpty())
    }

    @Test
    fun songsArcadeNeverPlayedAreDownloadedFirst() {
        val pool = pool()
        val history = (0..4).map { "song$it" }
        repeat(10) { seed ->
            val wanted = Arcade.prefetch(pool, emptySet(), emptySet(), history, emptySet(), Random(seed))
            assertEquals(setOf("song5", "song6", "song7"), wanted.map { it.id }.toSet())
        }
    }

    @Test
    fun pendingRequestsAndFailuresDoNotCauseDuplicateOrInfiniteDownloads() {
        val wanted = Arcade.prefetch(pool(), setOf("song0"), setOf("song1"), emptyList(), setOf("song2"), Random(1))
        assertEquals(1, wanted.size)
        assertTrue(wanted[0].id !in setOf("song0", "song1", "song2"))
        assertTrue(Arcade.prefetch(pool(), emptySet(), setOf("song0", "song1", "song2"), emptyList(), emptySet(), Random(1)).isEmpty())
    }

    @Test
    fun picksComeFromTheSongsPlayedLongestAgo() {
        val pool = pool()
        val history = listOf("song7", "song6", "song5", "song4", "song3", "song2", "song1", "song0")
        val picked = (0..40).map { Arcade.pick(pool, emptyList(), Random(it), history)!!.id }.toSet()
        assertEquals(setOf("song7", "song6", "song5"), picked)
        val unplayed = (0..40).map { Arcade.pick(pool, emptyList(), Random(it), history.drop(1))!!.id }.toSet()
        assertTrue("song7" in unplayed)
    }

    @Test
    fun offlineRunsCanRepeatTheOnlyCachedSongAndPreferItOverDemos() {
        val cached = pool()[0]
        val demo = Arcade.Entry("demo", JSONObject().put("id", "demo").put("demo", true))
        for (mode in Arcade.Mode.values()) {
            val run = Arcade.Run(mode, 1)
            run.remember(cached.id)
            assertEquals(cached.id, Arcade.pick(listOf(demo, cached), run.recent, Random(5))!!.id)
        }
    }
}
