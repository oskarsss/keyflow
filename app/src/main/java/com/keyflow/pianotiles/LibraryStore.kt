package com.keyflow.pianotiles

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class LibraryStore(private val context: Context) {
    val prefs = context.getSharedPreferences("keyflow", Context.MODE_PRIVATE)
    var playlist: JSONObject? = runCatching {
        JSONObject(prefs.getString("playlist", "null")!!)
    }
        .getOrNull()
        private set

    fun save(value: JSONObject) {
        playlist = value
        prefs.edit().putString("playlist", value.toString()).apply()
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

    fun completedCount() = (0 until songs().length()).count { completed(songs().getJSONObject(it)) }

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

    fun chart(song: JSONObject) = JSONObject(songFile(song, "json").readText())

    fun offline(song: JSONObject) =
        song.optBoolean("demo") || (songFile(song, "mp3").isFile && songFile(song, "json").isFile)
}
