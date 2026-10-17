package com.keyflow.pianotiles.library

import com.keyflow.pianotiles.arcade.Arcade
import kotlin.random.Random
import org.json.JSONObject

/**
 * Every song the app wants on the phone, in the order the single preparation worker takes them.
 * Playlists and Arcade only say what they want; the worker keeps no queue of its own and reads a
 * fresh plan before each song, so progress or a changed setting counts straight away.
 */
object PreparationPlan {
    /** Why a song is wanted, most urgent first. */
    enum class Reason {
        /** The player opened this song and is waiting for it. */
        WAITING,
        /** An Arcade run is being played and has no fresh song ready for its next one. */
        ARCADE_NEXT,
        /** A playlist's next level. */
        NEXT,
        /** The locked level after a playlist's next one. */
        LOCKED,
        /** Fresh songs for Endless and Time Attack. */
        ARCADE,
        /** The rest of a playlist's Download ahead window. */
        AHEAD,
        /** A playlist set to download every song. */
        ALL,
    }

    class Item(val playlistId: String, val song: JSONObject, val reason: Reason) {
        val id: String get() = song.optString("id")
    }

    /** What the plan is made from. [playlists] are the imported ones; [current] goes first. */
    class Inputs(
        val playlists: List<JSONObject>,
        val current: String?,
        val completed: (JSONObject) -> Boolean,
        val ready: (JSONObject) -> Boolean,
        val arcadeHistory: List<String> = emptyList(),
        val waiting: Pair<String, String>? = null,
        val now: Long = System.currentTimeMillis(),
        /** An Arcade run is being played. */
        val arcadePlaying: Boolean = false,
    )

    /** A failed song is retried after this long, unless the player opens it. */
    const val RETRY_MS = 15 * 60_000L
    /** A song that failed only because the connection dropped is retried sooner. */
    const val CONNECTION_RETRY_MS = 60_000L

    /** Every wanted song, ready or not, most important first. A song appears once. */
    fun wanted(inputs: Inputs): List<Item> {
        val playlists = inputs.playlists.sortedByDescending { it.optString("id") == inputs.current }
        val items = ArrayList<Item>()
        val seen = HashSet<String>()
        fun add(playlistId: String, song: JSONObject, reason: Reason) {
            if (seen.add(song.optString("id"))) items.add(Item(playlistId, song, reason))
        }
        inputs.waiting?.let { (playlistId, songId) ->
            playlists.firstOrNull { it.optString("id") == playlistId }?.let(::songsOf)
                ?.firstOrNull { it.optString("id") == songId }?.let { add(playlistId, it, Reason.WAITING) }
        }
        val windows = playlists.map { it.optString("id") to window(it, inputs.completed) }
        // Arcade counts the playlists' next two levels as on their way, so it only adds what it lacks.
        val coming = (items.map { it.song } + windows.flatMap { it.second.take(2) })
            .filter { !inputs.ready(it) }.map { it.optString("id") }.toSet()
        val arcade = arcade(playlists, inputs, coming)
        arcade.urgent?.let { add(it.playlistId, it.song, Reason.ARCADE_NEXT) }
        for ((id, songs) in windows) songs.getOrNull(0)?.let { add(id, it, Reason.NEXT) }
        for ((id, songs) in windows) songs.getOrNull(1)?.let { add(id, it, Reason.LOCKED) }
        for (entry in arcade.wanted) add(entry.playlistId, entry.song, Reason.ARCADE)
        for ((id, songs) in windows) songs.drop(2).forEach { add(id, it, Reason.AHEAD) }
        for (playlist in playlists.filter { it.optBoolean("downloadAll") }) {
            val songs = songsOf(playlist)
            val from = nextIndex(songs, inputs.completed).coerceAtLeast(0)
            (songs.drop(from) + songs.take(from)).forEach { add(playlist.optString("id"), it, Reason.ALL) }
        }
        return items
    }

    /**
     * The wanted songs still to prepare. A song that failed recently waits for [RETRY_MS] so a
     * broken song can't hold up the rest, except when the player is waiting for it.
     */
    fun pending(items: List<Item>, ready: (JSONObject) -> Boolean, now: Long): List<Item> =
        items.filter { item ->
            !ready(item.song) && (item.reason == Reason.WAITING || !recentlyFailed(item.song, now))
        }

    /**
     * Whether the song being prepared gives way to [first], the plan's first song now. A running
     * Arcade game's next song stops anything else, and every playlist's next two levels stop a
     * song for Arcade's buffer, Download ahead or Download all, such as the new locked level
     * after a clear. Songs of the same step don't interrupt each other.
     */
    fun outranks(first: Item?, current: Item): Boolean =
        first != null && first.id != current.id && step(first.reason) <= step(Reason.LOCKED) &&
            step(first.reason) < step(current.reason)

    private fun step(reason: Reason) = when (reason) {
        Reason.WAITING, Reason.ARCADE_NEXT -> 0
        Reason.NEXT, Reason.LOCKED -> 1
        else -> 2
    }

    fun recentlyFailed(song: JSONObject, now: Long) =
        song.optString("status") == "error" &&
            now - song.optLong("failedAt", 0) < if (song.optBoolean("connection")) CONNECTION_RETRY_MS else RETRY_MS

    /** A playlist's Download ahead window from its next level; nothing once every level is cleared. */
    fun window(playlist: JSONObject, completed: (JSONObject) -> Boolean): List<JSONObject> {
        val songs = songsOf(playlist)
        val from = nextIndex(songs, completed)
        if (from < 0) return emptyList()
        val count = playlist.optInt("preloadCount", LibraryStore.DEFAULT_PRELOAD)
            .coerceIn(LibraryStore.MIN_PRELOAD, LibraryStore.MAX_PRELOAD)
        return songs.subList(from, minOf(from + count, songs.size))
    }

    /** Arcade's songs, and the one a run being played needs now because none fresh is ready. */
    private class ArcadeWants(val wanted: List<Arcade.Entry>, val urgent: Arcade.Entry?)

    /**
     * Arcade's songs: the fresh ones it has ready, oldest played first, then new ones that top it
     * back up to [Arcade.BUFFER]. The top-up choice is random but stays the same until Arcade
     * plays another song, so each fresh plan asks for the same songs.
     */
    private fun arcade(playlists: List<JSONObject>, inputs: Inputs, coming: Set<String>): ArcadeWants {
        val eligible = Arcade.eligible(Arcade.pool(playlists), Arcade.distances(playlists, inputs.completed))
        val history = inputs.arcadeHistory
        val ready = eligible.filter { inputs.ready(it.song) }.map { it.id }.toSet()
        val stale = Arcade.stale(eligible.size, history)
        val lastPlayed = history.withIndex().associate { it.value to it.index }
        val kept = eligible.filter { it.id in ready && it.id !in stale }.sortedBy { lastPlayed[it.id] ?: -1 }.take(Arcade.BUFFER)
        val failed = eligible.filter { recentlyFailed(it.song, inputs.now) }.map { it.id }.toSet()
        val added = Arcade.prefetch(eligible, ready, coming, history, failed, Random(history.joinToString(",").hashCode()))
        // With nothing fresh ready, a run would repeat a song it just played.
        val urgent = if (!inputs.arcadePlaying || kept.isNotEmpty()) null
            else added.firstOrNull() ?: eligible.firstOrNull { it.id in coming && it.id !in failed }
        return ArcadeWants(kept + added, urgent)
    }

    private fun nextIndex(songs: List<JSONObject>, completed: (JSONObject) -> Boolean) =
        songs.indexOfFirst { !completed(it) }

    private fun songsOf(playlist: JSONObject): List<JSONObject> {
        val songs = playlist.getJSONArray("songs")
        return (0 until songs.length()).map { songs.getJSONObject(it) }
    }
}
