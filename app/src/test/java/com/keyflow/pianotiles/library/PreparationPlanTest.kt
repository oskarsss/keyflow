package com.keyflow.pianotiles.library

import com.keyflow.pianotiles.arcade.Arcade
import com.keyflow.pianotiles.library.PreparationPlan.Reason
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreparationPlanTest {
    private fun playlist(id: String, size: Int, preload: Int = LibraryStore.DEFAULT_PRELOAD) = JSONObject()
        .put("id", id)
        .put("preloadCount", preload)
        .put("songs", JSONArray((0 until size).map { JSONObject().put("id", "$id$it").put("status", "pending") }))

    private fun inputs(
        playlists: List<JSONObject>, current: String? = null, completed: Set<String> = emptySet(),
        ready: Set<String> = emptySet(), history: List<String> = emptyList(), waiting: Pair<String, String>? = null,
        arcadePlaying: Boolean = false,
    ) = PreparationPlan.Inputs(playlists, current, { it.optString("id") in completed }, { it.optString("id") in ready },
        history, waiting, arcadePlaying = arcadePlaying)

    private fun plan(inputs: PreparationPlan.Inputs) = PreparationPlan.wanted(inputs).map { it.id to it.reason }

    @Test
    fun everyPlaylistsNextAndLockedLevelsComeBeforeArcadeAndDownloadAhead() {
        val a = playlist("a", 10)
        val b = playlist("b", 10, preload = 3)
        val plan = plan(inputs(listOf(a, b), current = "b", completed = setOf("a0", "b0", "b1"), waiting = "a" to "a7"))
        assertEquals("a7" to Reason.WAITING, plan[0])
        // The open playlist goes first within each step.
        assertEquals(listOf("b2" to Reason.NEXT, "a1" to Reason.NEXT, "b3" to Reason.LOCKED, "a2" to Reason.LOCKED), plan.subList(1, 5))
        val reasons = plan.map { it.second }
        assertEquals(reasons, reasons.sorted())
        // Arcade borrows near levels while few are reached; those it took aren't asked for twice.
        val arcade = plan.filter { it.second == Reason.ARCADE }.map { it.first }.toSet()
        assertEquals(listOf("b4", "a3", "a4", "a5").filter { it !in arcade }, plan.filter { it.second == Reason.AHEAD }.map { it.first })
        assertEquals(plan.size, plan.map { it.first }.toSet().size)
    }

    @Test
    fun aRunningArcadeGameWithNothingFreshGetsItsNextSongFirst() {
        val mix = playlist("m", 20)
        val completed = (0 until 12).map { "m$it" }.toSet()
        // Arcade just played the only fresh song it had.
        val history = listOf("m0")
        val playing = plan(inputs(listOf(mix), completed = completed, ready = setOf("m0"), history = history, arcadePlaying = true))
        assertEquals(Reason.ARCADE_NEXT, playing[0].second)
        assertTrue(playing[0].first in completed && playing[0].first != "m0")
        assertEquals(listOf("m12" to Reason.NEXT, "m13" to Reason.LOCKED), playing.subList(1, 3))
        // Outside a run, the playlist's levels come first.
        assertEquals("m12" to Reason.NEXT, plan(inputs(listOf(mix), completed = completed, ready = setOf("m0"), history = history))[0])
        // With a fresh song ready, the run has its next one.
        val stocked = plan(inputs(listOf(mix), completed = completed, ready = setOf("m0", "m5"), history = history, arcadePlaying = true))
        assertTrue(stocked.none { it.second == Reason.ARCADE_NEXT })
    }

    @Test
    fun aPlaylistsNextTwoLevelsInterruptArcadeAndDownloadAheadButNotEachOther() {
        val song = { id: String -> JSONObject().put("id", id) }
        fun item(id: String, reason: Reason) = PreparationPlan.Item("m", song(id), reason)
        val locked = item("m3", Reason.LOCKED)
        for (reason in listOf(Reason.ARCADE, Reason.AHEAD, Reason.ALL))
            assertTrue(PreparationPlan.outranks(locked, item("x", reason)))
        assertFalse(PreparationPlan.outranks(locked, item("x", Reason.NEXT)))
        assertFalse(PreparationPlan.outranks(item("m2", Reason.NEXT), item("x", Reason.LOCKED)))
        assertFalse(PreparationPlan.outranks(item("y", Reason.ARCADE), item("x", Reason.AHEAD)))
        assertFalse(PreparationPlan.outranks(item("x", Reason.LOCKED), item("x", Reason.AHEAD)))
        assertFalse(PreparationPlan.outranks(null, item("x", Reason.AHEAD)))
        // A running Arcade game's next song stops even a playlist's next level.
        assertTrue(PreparationPlan.outranks(item("a", Reason.ARCADE_NEXT), item("x", Reason.NEXT)))
        assertFalse(PreparationPlan.outranks(item("a", Reason.ARCADE_NEXT), item("x", Reason.WAITING)))
    }

    @Test
    fun clearingALevelMovesTheLockedOneOnAtOnce() {
        val mix = playlist("m", 8)
        val before = plan(inputs(listOf(mix), completed = setOf("m0")))
        assertEquals(listOf("m1" to Reason.NEXT, "m2" to Reason.LOCKED), before.take(2))
        val after = plan(inputs(listOf(mix), completed = setOf("m0", "m1")))
        assertEquals(listOf("m2" to Reason.NEXT, "m3" to Reason.LOCKED), after.take(2))
        // A cleared playlist wants nothing for itself.
        assertTrue(PreparationPlan.wanted(inputs(listOf(mix), completed = (0 until 8).map { "m$it" }.toSet()))
            .none { it.reason in setOf(Reason.NEXT, Reason.LOCKED, Reason.AHEAD) })
    }

    @Test
    fun readySongsStayWantedButOnlyMissingOnesArePendingAndFailuresWaitUnlessOpened() {
        val mix = playlist("m", 8)
        val now = 10 * PreparationPlan.RETRY_MS
        mix.getJSONArray("songs").getJSONObject(1).put("status", "error").put("failedAt", now - 1000)
        val input = inputs(listOf(mix), ready = setOf("m0"))
        val wanted = PreparationPlan.wanted(input)
        assertEquals("m0", wanted.first().id)
        val pending = PreparationPlan.pending(wanted, input.ready, now).map { it.id }
        assertFalse("m0" in pending)
        assertFalse("m1" in pending)
        // The next level is ready and the locked one waits after failing, so the rest move up.
        assertEquals(PreparationPlan.wanted(input).map { it.id }.filter { it !in setOf("m0", "m1") }, pending)
        assertTrue("m1" in PreparationPlan.pending(wanted, input.ready, now + PreparationPlan.RETRY_MS).map { it.id })
        // A dropped connection says nothing about the song, so it comes back within a minute.
        mix.getJSONArray("songs").getJSONObject(1).put("connection", true)
        assertFalse("m1" in PreparationPlan.pending(wanted, input.ready, now).map { it.id })
        assertTrue("m1" in PreparationPlan.pending(wanted, input.ready, now + PreparationPlan.CONNECTION_RETRY_MS).map { it.id })
        mix.getJSONArray("songs").getJSONObject(1).remove("connection")
        val opened = inputs(listOf(mix), ready = setOf("m0"), waiting = "m" to "m1")
        assertEquals("m1", PreparationPlan.pending(PreparationPlan.wanted(opened), opened.ready, now).first().id)
    }

    @Test
    fun arcadeAsksForTheSameFreshSongsUntilItPlaysAnother() {
        val mix = playlist("m", 20)
        val completed = (0 until 12).map { "m$it" }.toSet()
        fun arcade(history: List<String>, ready: Set<String> = emptySet()) =
            plan(inputs(listOf(mix), completed = completed, ready = ready, history = history))
                .filter { it.second == Reason.ARCADE }.map { it.first }
        val first = arcade(emptyList())
        // The playlist's next level is reached and already on its way, so it counts as one of three.
        assertEquals(Arcade.BUFFER - 1, first.size)
        assertEquals(first, arcade(emptyList()))
        // Only reached levels, so locked ones are never given away.
        assertTrue(first.all { it in completed })
        // Ready fresh songs stay wanted and need no top-up.
        assertEquals(first.toSet(), arcade(emptyList(), first.toSet()).toSet())
        // Playing one makes it stale, and exactly one new song takes its place.
        val after = arcade(listOf(first[0]), first.toSet())
        assertEquals(first.size, after.size)
        assertFalse(first[0] in after)
        assertTrue(first[1] in after)
        // With nothing reached yet, it borrows the playlist's nearest levels, which are wanted anyway.
        assertTrue(plan(inputs(listOf(mix))).filter { it.second == Reason.ARCADE }.all { it.first.removePrefix("m").toInt() < Arcade.MIN_POOL })
    }

    @Test
    fun downloadAllTakesEverySongFromTheNextLevelOn() {
        val mix = playlist("m", 6, preload = 2).put("downloadAll", true)
        val plan = plan(inputs(listOf(mix), completed = setOf("m0", "m1", "m2")))
        assertEquals(listOf("m3", "m4"), plan.filter { it.second <= Reason.LOCKED }.map { it.first })
        val arcade = plan.filter { it.second == Reason.ARCADE }.map { it.first }.toSet()
        assertEquals(listOf("m5", "m0", "m1", "m2").filter { it !in arcade }, plan.filter { it.second == Reason.ALL }.map { it.first })
        assertEquals(6, plan.size)
    }
}
