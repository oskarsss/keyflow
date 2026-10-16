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

    /** Fresh songs kept ready ahead of a run, so the next song never waits for a download. */
    const val BUFFER = 3

    /** Arcade plays remembered, so the songs played longest ago come back first. */
    const val HISTORY = 40

    /** Until this many Journey levels are reached, Arcade borrows the nearest locked ones. */
    const val MIN_POOL = 6

    /**
     * Songs Arcade may play: Journey levels already reached, so locked levels are not given away.
     * [distance] is how far past the player's next level a song is; zero or less means reached.
     */
    fun eligible(pool: List<Entry>, distance: Map<String, Int>): List<Entry> {
        val imported = pool.filter { !it.demo }.distinctBy { it.id }
        val reached = imported.filter { (distance[it.id] ?: Int.MAX_VALUE) <= 0 }
        return if (reached.size >= MIN_POOL) reached
            else imported.sortedBy { distance[it.id] ?: Int.MAX_VALUE }.take(MIN_POOL)
    }

    /** Songs played too recently to count as fresh, leaving room for [BUFFER] others. */
    fun stale(eligible: Int, history: List<String>): Set<String> =
        history.takeLast((eligible - BUFFER).coerceIn(0, HISTORY)).toSet()

    /**
     * New downloads that bring the fresh, ready songs back up to [BUFFER]. Each song played goes
     * stale, so every song played adds one new song until the whole pool is ready. Songs Arcade
     * has never played come first.
     */
    fun prefetch(
        eligible: List<Entry>, ready: Set<String>, pending: Set<String>, history: List<String>,
        retryLater: Set<String>, random: Random,
    ): List<Entry> {
        val stale = stale(eligible.size, history)
        val fresh = eligible.count { (it.id in ready || it.id in pending) && it.id !in stale }
        val played = history.toSet()
        return eligible.filter { it.id !in ready && it.id !in pending && it.id !in retryLater }
            .shuffled(random).sortedBy { it.id in played }
            .take(maxOf(0, BUFFER - fresh))
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

    /**
     * A random song among the few Arcade played longest ago, avoiding the run's last few and
     * preferring imported music over built-in songs.
     */
    fun pick(ready: List<Entry>, recent: Collection<String>, random: Random, history: List<String> = emptyList()): Entry? {
        if (ready.isEmpty()) return null
        val imported = ready.filter { !it.demo }
        val pool = imported.ifEmpty { ready }
        val fresh = pool.filter { it.id !in recent }.ifEmpty { pool }
        val lastPlayed = history.withIndex().associate { it.value to it.index }
        val oldest = fresh.shuffled(random).sortedBy { lastPlayed[it.id] ?: -1 }
        return oldest[random.nextInt(minOf(CHOICES, oldest.size))]
    }

    private const val CHOICES = 3
}
