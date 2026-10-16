package com.keyflow.pianotiles

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class LibraryStore(private val context: Context) {
    val prefs = context.getSharedPreferences("keyflow", Context.MODE_PRIVATE)
    var playlist: JSONObject? = prefs.getString("playlistId", null)?.let { id ->
        when {
            id == "keyflow-originals" -> demo()
            !id.matches(Regex("[A-Za-z0-9_-]{1,80}")) -> null
            else -> runCatching {
                JSONObject(File(context.filesDir, "playlists/$id.json").readText())
            }.getOrNull()
        }
    }
        private set

    fun save(value: JSONObject) {
        playlist = value
        prefs.edit().putString("playlistId", value.getString("id")).apply()
    }

    fun demo() =
        context.assets.open("demo/playlist.json").bufferedReader().use { JSONObject(it.readText()) }

    fun songs(): JSONArray = playlist?.optJSONArray("songs") ?: JSONArray()

    private fun key(song: JSONObject) = "song.${song.optString("id") }"

    fun completed(song: JSONObject) = prefs.getBoolean(key(song) + ".completed", false)

    fun stars(song: JSONObject) = prefs.getInt(key(song) + ".stars", 0)

    fun score(song: JSONObject) = prefs.getInt(key(song) + ".score", 0)

    fun unlocked(index: Int) = (0 until index).all { completed(songs().getJSONObject(it)) }

    fun record(song: JSONObject, session: GameSession) {
        val key = key(song)
        prefs
            .edit()
            .putBoolean("$key.completed", completed(song) || session.passed())
            .putInt("$key.stars", maxOf(stars(song), session.stars()))
            .putInt("$key.score", maxOf(score(song), session.score))
            .apply()
    }

    fun completedCount(collection: JSONObject? = playlist): Int {
        val songs = collection?.optJSONArray("songs") ?: return 0
        return (0 until songs.length()).count { completed(songs.getJSONObject(it)) }
    }

    fun songFile(song: JSONObject, extension: String): File {
        val directory = File(context.filesDir, "songs").apply { mkdirs() }
        val id = song.optString("id").replace(Regex("[^A-Za-z0-9_-]"), "")
        require(id.isNotEmpty()) { "Song ID is missing" }
        return File(directory, "$id.$extension")
    }

    fun prepareDemo(song: JSONObject) {
        for (extension in listOf("wav", "json")) {
            val output = songFile(song, extension)
            if (!output.exists())
                context.assets.open("demo/${song.getString("id")}.$extension").use { input ->
                    output.outputStream().use { input.copyTo(it) }
                }
        }
    }

    /** Charts are versioned, so downloaded songs are re-charted when the generator improves. */
    fun chartFile(song: JSONObject) =
        songFile(song, if (song.optBoolean("demo")) "json" else "chart$CHART_VERSION.json")

    fun chart(song: JSONObject) = JSONObject(chartFile(song).readText())

    fun offline(song: JSONObject) =
        song.optBoolean("demo") || (songFile(song, "mp3").isFile && chartFile(song).isFile)

    fun markPlayed(song: JSONObject, now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(key(song) + ".lastPlayed", now).apply()
    }

    fun lastPlayed(song: JSONObject) = prefs.getLong(key(song) + ".lastPlayed", 0L)

    fun preloadCount(collection: JSONObject): Int = prefs.getInt(
        "preload.${collection.optString("id")}", collection.optInt("preloadCount", DEFAULT_PRELOAD),
    ).coerceIn(0, 20)

    /** A fixed window from the first uncleared level, including songs already cached. */
    fun upcoming(collection: JSONObject): List<JSONObject> {
        val songs = collection.getJSONArray("songs")
        val from = (0 until songs.length()).firstOrNull { !completed(songs.getJSONObject(it)) }
            ?: return emptyList()
        return (from until minOf(from + preloadCount(collection), songs.length()))
            .map { songs.getJSONObject(it) }
    }

    // ---- Player progression, shared across playlists.

    val xp
        get() = prefs.getInt("xp", 0)

    /** Experience for a run: score, stars and a clear bonus. */
    fun award(session: GameSession): Int {
        val gained = session.score / 60 + session.stars() * 40 + if (session.passed()) 60 else 10
        prefs.edit().putInt("xp", xp + gained).apply()
        return gained
    }

    /** Consecutive days with at least one finished song; 0 once a day is skipped. */
    fun streak(): Int {
        val today = java.time.LocalDate.now().toEpochDay()
        val last = prefs.getLong("streak.day", Long.MIN_VALUE / 2)
        return if (last >= today - 1) prefs.getInt("streak.count", 0) else 0
    }

    fun markPlayedToday() {
        val today = java.time.LocalDate.now().toEpochDay()
        val last = prefs.getLong("streak.day", Long.MIN_VALUE / 2)
        val count =
            when (last) {
                today -> prefs.getInt("streak.count", 1)
                today - 1 -> prefs.getInt("streak.count", 0) + 1
                else -> 1
            }
        prefs.edit().putLong("streak.day", today).putInt("streak.count", count).apply()
    }

    companion object {
        const val CHART_VERSION = 6

        /** Levels kept prepared ahead of the player's progress. */
        const val DEFAULT_PRELOAD = 5

        /** Level n starts at 150·(n−1)² XP. */
        fun level(xp: Int) = 1 + kotlin.math.sqrt(xp / 150.0).toInt()

        fun levelStart(level: Int) = 150 * (level - 1) * (level - 1)

        fun levelFraction(xp: Int): Float {
            val level = level(xp)
            val from = levelStart(level)
            return (xp - from).toFloat() / (levelStart(level + 1) - from)
        }
    }
}
