package com.keyflow.pianotiles

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
    fun nextPicksExploreTheWholeCatalogRatherThanOnlyReadyMusic() {
        val pool = pool()
        val selected = (0..30).flatMap { seed -> Arcade.plan(pool, emptyList(), emptySet(), emptySet(), Random(seed)) }
        assertEquals(pool.map { it.id }.toSet(), selected.map { it.id }.toSet())
        val initial = Arcade.plan(pool, emptyList(), emptySet(), emptySet(), Random(3))
        val next = Arcade.plan(pool, initial.map { it.id }, setOf(initial[0].id), emptySet(), Random(4))
        assertEquals(2, next.size)
        assertTrue(next.any { it.id == initial[1].id })
        assertFalse(next.any { it.id == initial[0].id })
    }

    @Test
    fun preparesTwoRandomSongsAndRefillsAfterTheCurrentSongUsesOne() {
        val pool = pool()
        val initial = Arcade.prefetch(pool, emptySet(), emptySet(), emptySet(), emptySet(), Random(2))
        assertEquals(2, initial.size)
        assertEquals(2, initial.map { it.id }.toSet().size)
        val ready = initial.map { it.id }.toSet()
        assertTrue(Arcade.prefetch(pool, ready, emptySet(), emptySet(), emptySet(), Random(3)).isEmpty())
        val more = Arcade.prefetch(pool, ready, emptySet(), setOf(initial[0].id), emptySet(), Random(4))
        assertEquals(1, more.size)
        assertTrue(more[0].id !in ready)
    }

    @Test
    fun pendingRequestsAndFailuresDoNotCauseDuplicateOrInfiniteDownloads() {
        val wanted = Arcade.prefetch(pool(), setOf("song0"), setOf("song1"), setOf("song0"), setOf("song2"), Random(1))
        assertEquals(1, wanted.size)
        assertTrue(wanted[0].id !in setOf("song0", "song1", "song2"))
        assertTrue(Arcade.prefetch(pool(), emptySet(), setOf("song0", "song1"), emptySet(), emptySet(), Random(1)).isEmpty())
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
