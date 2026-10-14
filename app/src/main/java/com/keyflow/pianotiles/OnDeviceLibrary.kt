package com.keyflow.pianotiles

import android.content.Context
import com.keyflow.pianotiles.downloader.NativeRuntime
import com.keyflow.pianotiles.downloader.YTDLRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** App-private playlists, audio, covers and charts; no companion connection. */
class OnDeviceLibrary private constructor(private val context: Context) {
    val runtime = NativeRuntime(context)
    private val store = LibraryStore(context)
    private val indexes = File(context.filesDir, "playlists").apply { mkdirs() }
    private val lock = Any()

    fun importPlaylist(value: String, id: String, progress: (String) -> Unit): JSONObject {
        val url = PlaylistUrl.normalize(value)
        progress("Reading your playlist on this phone…")
        val request =
            YTDLRequest(url)
                .addOption("--flat-playlist")
                .addOption("--dump-single-json")
                .addOption("--yes-playlist")
                .addOption("--playlist-end", 200)
                .addOption("--socket-timeout", 20)
        val raw =
            JSONObject(
                runtime
                    .execute(request, id, 180) { if (it.startsWith("Preparing")) progress(it) }
                    .out,
            )
        val playlistId =
            MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(20)
        val old = snapshot(playlistId)
        val entries = raw.optJSONArray("entries") ?: JSONArray()
        val songs = JSONArray()
        val seen = mutableSetOf<String>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val sid = entry.optString("id")
            if (!sid.matches(Regex("[A-Za-z0-9_-]{11}")) || !seen.add(sid)) continue
            val song =
                JSONObject()
                    .put("id", sid)
                    .put("title", entry.optString("title", "Untitled song"))
                    .put(
                        "artist",
                        entry.optString("artist").ifBlank {
                            entry.optString("uploader").ifBlank {
                                entry.optString("channel", "YouTube Music")
                            }
                        },
                    )
                    .put("duration", entry.optDouble("duration", 0.0))
                    .put("status", "pending")
            val thumbs = entry.optJSONArray("thumbnails")
            if (thumbs != null && thumbs.length() > 0)
                song.put(
                    "thumbnail",
                    thumbs.optJSONObject(thumbs.length() - 1)?.optString("url", ""),
                )
            if (store.offline(song)) {
                val previous =
                    old?.optJSONArray("songs")?.let { values ->
                        (0 until values.length())
                            .map { values.getJSONObject(it) }
                            .firstOrNull { it.optString("id") == sid }
                    }
                if (previous != null)
                    for (key in listOf("title", "artist", "duration", "chartMethod")) if (
                        previous.has(key)
                    )
                        song.put(key, previous.get(key))
                song.put("status", "ready")
            }
            songs.put(song)
        }
        check(songs.length() > 0) {
            "No playable songs found. Check your playlist link or update the downloader in Play settings."
        }
        val count = store.prefs.getInt("preload.$playlistId", old?.optInt("preloadCount", 3) ?: 3)
        return JSONObject()
            .put("id", playlistId)
            .put("title", raw.optString("title", "My playlist"))
            .put("url", url)
            .put("songs", songs)
            .put("preloadCount", count)
            .also(::save)
    }

    fun save(playlist: JSONObject) =
        synchronized(lock) {
            val file = index(playlist.getString("id"))
            val temporary = File(file.path + ".tmp")
            temporary.writeText(playlist.toString())
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

    fun snapshot(id: String): JSONObject? =
        synchronized(lock) {
            runCatching { JSONObject(index(id).readText()) }.getOrNull()
        }

    fun bootstrap(playlist: JSONObject) {
        if (playlist.optString("id") == "keyflow-originals") return
        if (snapshot(playlist.getString("id")) == null) {
            val copy = JSONObject(playlist.toString())
            val songs = copy.getJSONArray("songs")
            for (i in 0 until songs.length()) {
                val song = songs.getJSONObject(i)
                song.put("status", if (store.offline(song)) "ready" else "pending")
                song.remove("audio")
                song.remove("chart")
                song.remove("cover")
            }
            save(copy)
        }
    }

    fun setPreload(id: String, count: Int): JSONObject = synchronized(lock) {
        require(count in 0..20)
        val playlist = requireNotNull(snapshot(id)) { "Import your playlist again." }
        playlist.put("preloadCount", count)
        save(playlist)
        playlist
    }

    fun cacheSong(playlistId: String, songId: String, progress: (String) -> Unit) {
        val playlist =
            requireNotNull(snapshot(playlistId)) { "Playlist not found. Import it again." }
        val songs = playlist.getJSONArray("songs")
        val song =
            (0 until songs.length())
                .map { songs.getJSONObject(it) }
                .first { it.getString("id") == songId }
        if (store.offline(song)) {
            updateSong(playlistId, songId) { it.put("status", "ready") }
            return
        }
        updateSong(playlistId, songId) {
            it.put("status", "caching")
                .put("error", "")
                .put("progress", "Downloading on this phone…")
        }
        val folder = File(context.cacheDir, "downloads/$songId").apply { mkdirs() }
        val job = "song-$songId"
        try {
            val request =
                YTDLRequest("https://www.youtube.com/watch?v=$songId")
                    .addOption("--format", "bestaudio")
                    .addOption("--extract-audio")
                    .addOption("--audio-format", "mp3")
                    .addOption("--audio-quality", 0)
                    .addOption("--add-metadata")
                    .addOption("--embed-thumbnail")
                    .addOption("--write-thumbnail")
                    .addOption("--convert-thumbnails", "jpg")
                    .addOption("--write-info-json")
                    .addOption("--no-mtime")
                    .addOption("--newline")
                    .addOption("--socket-timeout", 20)
                    .addOption("--retries", 2)
                    .addOption("--max-filesize", "100M")
                    .addOption("--match-filters", "duration <= 1200")
                    .addOption("--output", File(folder, "source.%(ext)s").path)
            var lastProgress = 0L
            runtime.execute(request, job, 600) { line ->
                val now = System.currentTimeMillis()
                if (
                    now - lastProgress > 750 &&
                        (line.contains("[download]") ||
                            line.contains("[ExtractAudio]") ||
                            line.startsWith("Preparing"))
                ) {
                    lastProgress = now
                    val percent = Regex("([0-9.]+)%").find(line)?.groupValues?.get(1)
                    val message = when {
                        percent != null -> "Downloading audio · $percent%"
                        line.contains("[ExtractAudio]") -> "Preparing MP3 audio…"
                        line.startsWith("Preparing") -> line
                        else -> "Downloading audio on this phone…"
                    }
                    updateSong(playlistId, songId) { it.put("progress", message) }
                    progress("${song.optString("title")}: $message")
                }
            }
            val audio = File(folder, "source.mp3")
            check(audio.isFile) { "This song could not be downloaded, or exceeds 20 minutes." }
            progress("Finding the rhythm in ${song.optString("title")}…")
            updateSong(playlistId, songId) {
                it.put("progress", "Building tap, hold and slide patterns…")
            }
            val samples = runtime.audioSamples(audio, "chart-$songId", AudioChart::readPcm)
            val chart = AudioChart.generate(samples, songId)
            val notes = JSONArray()
            for (note in chart.notes) notes.put(
                JSONObject()
                    .put("time", note.time)
                    .put("lane", note.lane)
                    .put("kind", note.kind)
                    .put("duration", note.duration)
                    .put("endLane", note.endLane),
            )
            val metadata = JSONObject(File(folder, "source.info.json").readText())
            val chartFile = store.songFile(song, "json")
            File(chartFile.path + ".part")
                .apply {
                    writeText(
                        JSONObject()
                            .put("version", 2)
                            .put("duration", chart.duration)
                            .put("method", chart.method)
                            .put("notes", notes)
                            .toString(),
                    )
                }
                .let {
                    Files.move(it.toPath(), chartFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            Files.move(
                audio.toPath(),
                store.songFile(song, "mp3").toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            File(folder, "source.jpg")
                .takeIf { it.exists() }
                ?.let {
                    Files.move(
                        it.toPath(),
                        store.songFile(song, "jpg").toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            updateSong(playlistId, songId) { value ->
                value
                    .put("status", "ready")
                    .put("duration", chart.duration)
                    .put("chartMethod", chart.method)
                    .put("progress", "Offline ready")
                    .put(
                        "title",
                        metadata.optString("track").ifBlank {
                            metadata.optString("title", song.optString("title"))
                        },
                    )
                    .put(
                        "artist",
                        metadata.optString("artist").ifBlank {
                            metadata.optString("uploader", song.optString("artist"))
                        },
                    )
            }
            folder.deleteRecursively()
        } catch (error: Throwable) {
            File(folder, "download.log").writeText(error.message ?: "Download failed")
            updateSong(playlistId, songId) {
                it.put("status", "error")
                    .put("error", error.message?.takeLast(2000) ?: "Download failed. Please retry.")
            }
            throw error
        }
    }

    private fun updateSong(playlistId: String, songId: String, update: (JSONObject) -> Unit) =
        synchronized(lock) {
            val playlist = requireNotNull(snapshot(playlistId))
            val songs = playlist.getJSONArray("songs")
            for (i in 0 until songs.length()) if (songs.getJSONObject(i).getString("id") == songId)
                update(songs.getJSONObject(i))
            save(playlist)
        }

    private fun index(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid playlist ID" }
        return File(indexes, "$id.json")
    }

    companion object {
        @Volatile private var instance: OnDeviceLibrary? = null

        fun get(context: Context): OnDeviceLibrary =
            instance
                ?: synchronized(this) {
                    instance ?: OnDeviceLibrary(context.applicationContext).also { instance = it }
                }
    }
}
