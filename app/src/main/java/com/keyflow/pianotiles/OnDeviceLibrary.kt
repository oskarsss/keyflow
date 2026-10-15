package com.keyflow.pianotiles

import android.content.Context
import com.keyflow.pianotiles.downloader.NativeRuntime
import com.keyflow.pianotiles.downloader.YTDLRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/** App-private playlists, audio, covers and charts; no companion connection. */
class OnDeviceLibrary internal constructor(private val context: Context) {
    val runtime by lazy { NativeRuntime(context) }
    private val store = LibraryStore(context)
    private val indexes = File(context.filesDir, "playlists").apply { mkdirs() }
    private val lock = Any()
    private val cacheLock = Any()
    private val playing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile internal var cacheGeneration = 0L
        private set

    init {
        // A process restart cannot have any active downloads. Repair persisted transient states.
        for (playlist in savedPlaylists()) {
            val songs = playlist.getJSONArray("songs")
            for (i in 0 until songs.length()) {
                val song = songs.getJSONObject(i)
                if (store.offline(song)) song.put("status", "ready")
                else if (song.optString("status") in listOf("ready", "caching", "queued")) {
                    song.put("status", "pending")
                    song.remove("progress")
                }
            }
            save(playlist)
        }
    }

    fun importPlaylist(value: String, id: String, progress: (String) -> Unit): JSONObject =
        createPlaylist(readPlaylist(value, id, progress))

    /** Read the complete source before asking for approval or saving a collection. */
    internal fun readPlaylist(value: String, id: String, progress: (String) -> Unit): JSONObject {
        val url = PlaylistUrl.normalize(value)
        progress("Reading your playlist on this phone…")
        val request = YTDLRequest(url)
            .addOption("--flat-playlist")
            .addOption("--dump-single-json")
            .addOption("--yes-playlist")
            .addOption("--socket-timeout", 20)
        // Large playlists can exceed the runtime's bounded diagnostic output buffer.
        val metadata = File.createTempFile("playlist-", ".json", context.cacheDir)
        try {
            runtime.execute(request, id, 600, outputFile = metadata) {
                if (it.startsWith("Preparing")) progress(it)
            }
            return JSONObject(metadata.readText())
        } finally {
            metadata.delete()
        }
    }

    internal fun importSongCount(raw: JSONObject): Int = importEntries(raw).count()

    private fun importEntries(raw: JSONObject): Sequence<JSONObject> {
        val entries = raw.optJSONArray("entries") ?: JSONArray()
        return (0 until entries.length()).asSequence().mapNotNull(entries::optJSONObject)
            .filter { it.optString("id").matches(SONG_ID) }.distinctBy { it.getString("id") }
    }

    /** Import a one-time song list into a new collection; the source is never its identity. */
    internal fun createPlaylist(raw: JSONObject, random: Random = Random.Default): JSONObject {
        val cached = savedPlaylists().flatMap { playlist ->
            val songs = playlist.getJSONArray("songs")
            (0 until songs.length()).map { songs.getJSONObject(it) }
        }.sortedBy { it.has("chartMethod") }.associateBy { it.optString("id") }
        val songs = mutableListOf<JSONObject>()
        for (entry in importEntries(raw)) {
            val sid = entry.getString("id")
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
                val previous = cached[sid]
                if (previous != null)
                    for (key in listOf("title", "artist", "duration", "chartMethod")) if (
                        previous.has(key)
                    )
                        song.put(key, previous.get(key))
                song.put("status", "ready")
            }
            songs.add(song)
        }
        check(songs.isNotEmpty()) {
            "No playable songs found. Check your playlist link or update the downloader in Play settings."
        }
        // Imports keep their source title, numbered when it is already taken.
        val base = raw.optString("title").trim().ifBlank { "My playlist" }.take(MAX_TITLE)
        val taken = savedPlaylists().map { it.optString("title") }.toSet()
        val title = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
        return JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("title", title)
            .put("songs", JSONArray(songs.shuffled(random)))
            .put("preloadCount", 3)
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

    fun savedPlaylists(): List<JSONObject> = synchronized(lock) {
        indexes.listFiles().orEmpty()
            .filter { it.name.matches(Regex("[A-Za-z0-9_-]{1,80}\\.json")) }
            .mapNotNull { snapshot(it.nameWithoutExtension) }
            .filter { it.optString("id").isNotBlank() && it.optJSONArray("songs") != null }
            .sortedBy { it.optString("title").lowercase(java.util.Locale.ROOT) }
    }

    /** Called from background preparation; shared songs appear only once. */
    fun arcadeSongs(): List<Arcade.Entry> = savedPlaylists().flatMap { playlist ->
        val songs = playlist.getJSONArray("songs")
        (0 until songs.length()).map { Arcade.Entry(playlist.getString("id"), songs.getJSONObject(it)) }
    }.distinctBy { it.id }

    fun protectPlayback(id: String) { playing.add(id) }
    fun releasePlayback(id: String) { playing.remove(id) }

    /** Disk cleanup never changes playlists, scores or progression, or removes a playing song. */
    fun pruneUnused(days: Int, now: Long = System.currentTimeMillis()): Int = synchronized(cacheLock) {
        require(days in 1..365)
        if (!store.prefs.getBoolean("smartStorage", true)) return@synchronized 0
        val cutoff = now - days * 86_400_000L
        val files = File(context.filesDir, "songs").listFiles().orEmpty().groupBy { it.name.substringBefore('.') }
        var removed = 0
        for (entry in arcadeSongs()) {
            if (!store.prefs.getBoolean("smartStorage", true)) break
            if (entry.demo || entry.id in playing || entry.id in store.prefs.getStringSet("arcade.reserve", emptySet()).orEmpty()) continue
            val audio = store.songFile(entry.song, "mp3")
            if (!audio.isFile) continue
            // A fresh download gets a full retention period, even if it was played long ago.
            val lastUse = maxOf(store.lastPlayed(entry.song), audio.lastModified())
            if (lastUse >= cutoff) continue
            if (entry.id in playing || entry.id in store.prefs.getStringSet("arcade.reserve", emptySet()).orEmpty()) continue
            files[entry.id].orEmpty().forEach { it.delete() }
            removed++
        }
        if (removed > 0) synchronized(lock) {
            for (playlist in savedPlaylists()) {
                val songs = playlist.getJSONArray("songs")
                for (i in 0 until songs.length()) {
                    val song = songs.getJSONObject(i)
                    if (!store.offline(song) && song.optString("status") == "ready")
                        song.put("status", "pending").remove("progress")
                }
                save(playlist)
            }
        }
        removed
    }

    /** Marks every song still to download, so the queue is visible before each one starts. */
    fun markQueued(id: String) = synchronized(lock) {
        val playlist = snapshot(id) ?: return@synchronized
        val songs = playlist.getJSONArray("songs")
        for (i in 0 until songs.length()) {
            val song = songs.getJSONObject(i)
            if (!store.offline(song) && song.optString("status") != "caching")
                song.put("status", "queued").remove("error")
        }
        save(playlist)
    }

    fun rename(id: String, title: String): JSONObject = synchronized(lock) {
        val playlist = requireNotNull(snapshot(id)) { "Playlist not found." }
        playlist.put("title", title.trim().take(MAX_TITLE))
        save(playlist)
        playlist
    }

    /** Removes a collection and any downloads no other collection uses. Scores stay per song. */
    fun deletePlaylist(id: String) {
        val removed = synchronized(lock) { snapshot(id).also { index(id).delete() } } ?: return
        val kept = savedPlaylists().flatMap { playlist ->
            val songs = playlist.getJSONArray("songs")
            (0 until songs.length()).map { songs.getJSONObject(it).optString("id") }
        }.toSet()
        val songs = removed.getJSONArray("songs")
        for (i in 0 until songs.length()) {
            val song = songs.getJSONObject(i)
            if (song.optString("id") in kept || song.optBoolean("demo")) continue
            for (file in listOf(store.songFile(song, "mp3"), store.chartFile(song), store.songFile(song, "json"), store.songFile(song, "jpg")))
                file.delete()
        }
        store.prefs.edit().remove("preload.$id").apply()
    }

    fun setPreload(id: String, count: Int): JSONObject = synchronized(lock) {
        require(count in 0..20)
        val playlist = requireNotNull(snapshot(id)) { "Import your playlist again." }
        playlist.put("preloadCount", count)
        store.prefs.edit().putInt("preload.$id", count).apply()
        save(playlist)
        playlist
    }

    fun clearDownloads() = synchronized(cacheLock) {
        cacheGeneration++
        File(context.filesDir, "songs").deleteRecursively()
        File(context.cacheDir, "downloads").deleteRecursively()
        synchronized(lock) {
            for (playlist in savedPlaylists()) {
                val songs = playlist.getJSONArray("songs")
                for (i in 0 until songs.length()) {
                    val song = songs.getJSONObject(i)
                    song.put("status", if (store.offline(song)) "ready" else "pending")
                    song.remove("progress")
                    song.remove("error")
                }
                save(playlist)
            }
        }
    }

    fun cacheSong(
        playlistId: String,
        songId: String,
        generation: Long = cacheGeneration,
        progress: (String) -> Unit,
    ) = synchronized(cacheLock) {
        if (generation == cacheGeneration) cacheSongNow(playlistId, songId, progress)
    }

    private fun cacheSongNow(playlistId: String, songId: String, progress: (String) -> Unit) {
        val playlist =
            requireNotNull(snapshot(playlistId)) { "Playlist not found. Import it again." }
        val songs = playlist.getJSONArray("songs")
        val song =
            (0 until songs.length())
                .map { songs.getJSONObject(it) }
                .first { it.getString("id") == songId }
        val position = (0 until songs.length()).first {
            songs.getJSONObject(it).getString("id") == songId
        }
        val title = SongTitle.display(song.optString("title"),
            (0 until position).all { store.completed(songs.getJSONObject(it)) })
        if (store.offline(song)) {
            updateSong(playlistId, songId) { it.put("status", "ready") }
            return
        }
        val downloaded = store.songFile(song, "mp3")
        if (downloaded.isFile) {
            // Audio from an earlier version only needs a new chart.
            updateSong(playlistId, songId) {
                it.put("status", "caching").put("error", "").put("progress", "Building a new chart…")
            }
            progress("Finding the rhythm in $title…")
            val chart = try {
                writeChart(song, downloaded, songId)
            } catch (error: Throwable) {
                updateSong(playlistId, songId) {
                    it.put("status", "error").put("failedAt", System.currentTimeMillis())
                        .put("error", error.message ?: "Chart failed. Please retry.")
                }
                throw error
            }
            updateSong(playlistId, songId) {
                it.put("status", "ready")
                    .put("duration", chart.duration)
                    .put("chartMethod", chart.method)
                    .put("progress", "Offline ready")
            }
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
                    progress("$title: $message")
                }
            }
            val audio = File(folder, "source.mp3")
            check(audio.isFile) { "This song could not be downloaded, or exceeds 20 minutes." }
            progress("Finding the rhythm in $title…")
            updateSong(playlistId, songId) {
                it.put("progress", "Matching tiles to the beat…")
            }
            val chart = writeChart(song, audio, songId)
            val metadata = JSONObject(File(folder, "source.info.json").readText())
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
                    .put("failedAt", System.currentTimeMillis())
                    .put("error", error.message?.takeLast(2000) ?: "Download failed. Please retry.")
            }
            throw error
        }
    }

    private fun writeChart(song: JSONObject, audio: File, songId: String): AudioChart.Chart {
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
        val chartFile = store.chartFile(song)
        File(chartFile.path + ".part")
            .apply {
                writeText(
                    JSONObject()
                        .put("version", LibraryStore.CHART_VERSION)
                        .put("duration", chart.duration)
                        .put("method", chart.method)
                        .put("beats", JSONArray(chart.beats))
                        .put("barPhase", chart.barPhase)
                        .put("notes", notes)
                        .toString(),
                )
            }
            .let { Files.move(it.toPath(), chartFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        store.songFile(song, "json").delete()
        return chart
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
        private val SONG_ID = Regex("[A-Za-z0-9_-]{11}")
        const val MAX_TITLE = 60

        @Volatile private var instance: OnDeviceLibrary? = null

        fun get(context: Context): OnDeviceLibrary {
            val app = context.applicationContext
            return instance?.takeIf { it.context === app } ?: synchronized(this) {
                instance?.takeIf { it.context === app } ?: OnDeviceLibrary(app).also { instance = it }
            }
        }
    }
}
