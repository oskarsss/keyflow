package com.keyflow.pianotiles

import kotlin.random.Random
import org.json.JSONObject

/** Arcade modes chain short segments of random songs from every playlist. */
object Arcade {
    enum class Mode(val key: String, val title: String) {
        TIME("time", "Time Attack"),
        ENDLESS("endless", "Endless"),
    }

    /** Durations offered for Time Attack, in minutes. */
    val MINUTES = listOf(1, 2, 3, 5)

    const val SEGMENT_MS = 45_000

    /** Songs that should be playable offline before a run starts. */
    const val READY_TARGET = 2

    /** Retain the next picks, then draw from the whole catalog, including music not yet saved. */
    fun plan(pool: List<Entry>, previous: List<String>, excluded: Set<String>, retryLater: Set<String>, random: Random): List<Entry> {
        val eligible = pool.filter { !it.demo && it.id !in excluded && it.id !in retryLater }.distinctBy { it.id }
        val byId = eligible.associateBy { it.id }
        val kept = previous.distinct().mapNotNull { byId[it] }.take(READY_TARGET)
        return kept + eligible.filter { entry -> kept.none { it.id == entry.id } }.shuffled(random)
            .take(READY_TARGET - kept.size)
    }

    /** Fill the upcoming buffer, counting pending work, without choosing the current/recent songs. */
    fun prefetch(
        pool: List<Entry>, ready: Set<String>, pending: Set<String>, excluded: Set<String>,
        retryLater: Set<String>, random: Random,
    ): List<Entry> {
        val eligible = pool.filter { !it.demo && it.id !in excluded }.distinctBy { it.id }
        val available = eligible.count { it.id in ready || it.id in pending }
        return eligible.filter { it.id !in ready && it.id !in pending && it.id !in retryLater }
            .shuffled(random).take(maxOf(0, READY_TARGET - available))
    }

    class Entry(val playlistId: String, val song: JSONObject) {
        val id: String
            get() = song.optString("id")

        val demo
            get() = song.optBoolean("demo")
    }

    /** Running totals for one arcade run, carried from song to song. */
    class Run(val mode: Mode, val minutes: Int) {
        var score = 0
        var hits = 0
        var scored = 0
        var perfect = 0
        var misses = 0
        var maxCombo = 0
        var songs = 0
        var lives = GameSession.MAX_LIVES
        var combo = 0
        var timeLeftMs = minutes * 60_000L
        val recent = ArrayDeque<String>()

        /** Endless gets faster with every song: quicker music and quicker tiles. */
        val speed
            get() = if (mode == Mode.ENDLESS) minOf(1.35f, 1f + .05f * songs) else null

        val travel
            get() = if (mode == Mode.ENDLESS) maxOf(.95, 1.75 - .09 * songs) else null

        /** Tile variety ramps up with the run, reusing the journey's level rules. */
        val variety
            get() = if (mode == Mode.ENDLESS) minOf(2 + songs, 8) else 3

        val bestKey
            get() = if (mode == Mode.TIME) "best.time.$minutes" else "best.endless"

        fun absorb(session: GameSession, timeLeft: Long) {
            score += session.score
            hits += session.hits
            scored += session.scored
            perfect += session.perfect
            misses += session.misses
            maxCombo = maxOf(maxCombo, session.maxCombo)
            songs++
            lives = session.lives
            combo = session.combo
            if (mode == Mode.TIME) timeLeftMs = timeLeft
        }

        fun accuracy() = if (scored == 0) 0.0 else (perfect + (hits - perfect) * .7) / scored

        fun remember(id: String) {
            recent.addLast(id)
            while (recent.size > 3) recent.removeFirst()
        }
    }

    /**
     * Where a segment starts and ends in a song, in milliseconds. Short songs play whole (0 to 0);
     * longer ones skip the intro and keep the full segment inside the song.
     */
    fun segment(durationMs: Int, random: Random): Pair<Int, Int> {
        if (durationMs <= SEGMENT_MS + 15_000) return 0 to 0
        val earliest = (durationMs * .08).toInt()
        val latest = durationMs - SEGMENT_MS - 5_000
        val start = if (latest <= earliest) earliest else random.nextInt(earliest, latest)
        return start to start + SEGMENT_MS
    }

    /** Notes that fit a segment, leaving room for the countdown and the fade-out. */
    fun clip(notes: List<GameSession.Note>, startMs: Int, endMs: Int, leadIn: Double = .4): List<GameSession.Note> {
        if (startMs == 0 && endMs == 0) return notes
        val from = startMs / 1000.0 + leadIn
        val to = if (endMs == 0) Double.MAX_VALUE else endMs / 1000.0 - .6
        return notes.filter { it.time >= from && it.time + it.duration <= to }
    }

    /** A random song, avoiding the last few, preferring imported music over built-in songs. */
    fun pick(ready: List<Entry>, recent: Collection<String>, random: Random): Entry? {
        if (ready.isEmpty()) return null
        val imported = ready.filter { !it.demo }
        val pool = imported.ifEmpty { ready }
        val fresh = pool.filter { it.id !in recent }.ifEmpty { pool }
        return fresh[random.nextInt(fresh.size)]
    }
}
