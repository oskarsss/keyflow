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
                    song.remove("prepared")
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
            .put("preloadCount", LibraryStore.DEFAULT_PRELOAD)
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
                    song.remove("prepared")
                    song.remove("error")
                }
                save(playlist)
            }
        }
    }

    /**
     * Downloads and charts one song. [progress] receives a message and the whole preparation's
     * fraction. When [shouldYield] turns true during charting, preparation stops with [Yielded] and
     * the song returns to pending; a finished download is kept for the next attempt.
     */
    fun cacheSong(
        playlistId: String,
        songId: String,
        generation: Long = cacheGeneration,
        shouldYield: () -> Boolean = { false },
        progress: (String, Double) -> Unit,
    ) = synchronized(cacheLock) {
        if (generation == cacheGeneration) cacheSongNow(playlistId, songId, shouldYield, progress)
    }

    /** Thrown when a song's preparation gives way to one the player is waiting for. */
    class Yielded : RuntimeException("Preparation paused for another song")

    private fun cacheSongNow(playlistId: String, songId: String, shouldYield: () -> Boolean, progress: (String, Double) -> Unit) {
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
        fun report(message: String, fraction: Double) {
            updateSong(playlistId, songId) { it.put("progress", message).put("prepared", fraction) }
            progress("$title · $message", fraction)
        }
        val downloaded = store.songFile(song, "mp3")
        if (downloaded.isFile) {
            // Audio from an earlier version only needs a new chart.
            updateSong(playlistId, songId) { it.put("status", "caching").put("error", "") }
            val chart = try {
                writeChart(song, downloaded, songId, shouldYield) { message, fraction -> report(message, fraction) }
            } catch (error: Throwable) {
                updateSong(playlistId, songId) {
                    if (error is Yielded) it.put("status", "pending").remove("progress")
                    else it.put("status", "error").put("failedAt", System.currentTimeMillis())
                        .put("error", error.message ?: "Chart failed. Please retry.")
                    it.remove("prepared")
                }
                throw error
            }
            updateSong(playlistId, songId) {
                it.put("status", "ready")
                    .put("duration", chart.duration)
                    .put("chartMethod", chart.method)
                    .put("progress", "Offline ready")
                    .remove("prepared")
            }
            return
        }
        updateSong(playlistId, songId) { it.put("status", "caching").put("error", "") }
        report("Downloading", 0.0)
        val folder = File(context.cacheDir, "downloads/$songId").apply { mkdirs() }
        val job = "song-$songId"
        val audio = File(folder, "source.mp3")
        val info = File(folder, "source.info.json")
        val complete = File(folder, "complete")
        try {
            // A download whose chart gave way to another song is reused as is.
            if (!complete.isFile) {
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
                    if (now - lastProgress > 750 && (line.contains("[download]") || line.contains("[ExtractAudio]"))) {
                        lastProgress = now
                        val percent = Regex("([0-9.]+)%").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                        when {
                            line.contains("[ExtractAudio]") -> report("Converting audio", DOWNLOAD_SHARE)
                            percent != null -> report("Downloading", percent.coerceIn(0.0, 100.0) / 100 * (DOWNLOAD_SHARE - .03))
                        }
                    }
                }
                check(audio.isFile) { "This song could not be downloaded, or exceeds 20 minutes." }
                complete.createNewFile()
            }
            val chart = writeChart(song, audio, songId, shouldYield) { message, fraction ->
                report(message, DOWNLOAD_SHARE + fraction * (1 - DOWNLOAD_SHARE))
            }
            val metadata = JSONObject(info.readText())
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
                value.remove("prepared")
            }
            folder.deleteRecursively()
        } catch (error: Throwable) {
            if (error is Yielded) {
                updateSong(playlistId, songId) { it.put("status", "pending").remove("progress"); it.remove("prepared") }
                throw error
            }
            File(folder, "download.log").writeText(error.message ?: "Download failed")
            updateSong(playlistId, songId) {
                it.put("status", "error")
                    .put("failedAt", System.currentTimeMillis())
                    .put("error", error.message?.takeLast(2000) ?: "Download failed. Please retry.")
                it.remove("prepared")
            }
            throw error
        }
    }

    /** [progress] gets this step's own fraction; the chart model is most of the work. */
    private fun writeChart(
        song: JSONObject,
        audio: File,
        songId: String,
        shouldYield: () -> Boolean,
        progress: (String, Double) -> Unit,
    ): AudioChart.Chart {
        progress("Finding the beat", 0.0)
        val features = runtime.audioSamples(audio, "rhythm-features-$songId", MusicFeatures.RATE, MusicFeatures::readPcm)
        val rhythm = BeatThis(context).analyze(features)
        check(rhythm.beats.size >= 4) { "This recording has no clear rhythm to create a level. Try another song." }
        if (shouldYield()) throw Yielded()
        progress("Placing tiles", BEAT_SHARE)
        val temporary = File.createTempFile("chart-audio-", ".pcm", context.cacheDir)
        val learned = try {
            runtime.audioSamples(audio, "chart-model-$songId", ChartAudio.RATE) { ChartAudio.readPcm(it, temporary) }.use {
                ManiaChartModel(context).analyze(it, rhythm) { percent ->
                    if (shouldYield()) throw Yielded()
                    progress("Placing tiles", BEAT_SHARE + percent / 100.0 * (.99 - BEAT_SHARE))
                }
            }
        } finally { temporary.delete() }
        val chart = ChartStructure.describe(features, rhythm, learned)
        val chartFile = store.chartFile(song)
        File(chartFile.path + ".part")
            .apply {
                writeText(PreparedChart.encode(chart).toString())
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

        /** Shares of a song's preparation: the transfer, then beats before the slower chart model. */
        const val DOWNLOAD_SHARE = .35
        const val BEAT_SHARE = .15
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
