package com.keyflow.pianotiles.library

import android.content.Context
import com.keyflow.pianotiles.arcade.Arcade
import com.keyflow.pianotiles.chart.BeatThis
import com.keyflow.pianotiles.chart.Chart
import com.keyflow.pianotiles.chart.ChartAudio
import com.keyflow.pianotiles.chart.ChartStructure
import com.keyflow.pianotiles.chart.ManiaChartModel
import com.keyflow.pianotiles.chart.MusicFeatures
import com.keyflow.pianotiles.chart.MusicalRhythm
import com.keyflow.pianotiles.chart.PreparedChart
import com.keyflow.pianotiles.chart.QuickChart
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
    /** Held while a song downloads, and while a level is made; cleanup takes both. */
    private val downloadLock = Any()
    private val chartLock = Any()
    private val playing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val preparing = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Double>>()
    /** The beat of songs whose full level is being made, for a quick level asked for meanwhile. */
    private val analysed = java.util.concurrent.ConcurrentHashMap<String, Pair<MusicFeatures, MusicalRhythm>>()
    /** [readPlaylists]' parsed playlists with the revision and file time they were read at. */
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, Pair<Pair<Long, Long>, JSONObject>>()
    @Volatile internal var cacheGeneration = 0L
        private set

    init {
        // Songs that failed as unavailable before such songs were taken out go now.
        for (song in savedPlaylists().flatMap(::songsOf))
            if (song.optString("status") == "error" && gone(song.optString("error"))) dropUnavailable(song)
        // A process restart cannot have any active downloads. Repair persisted transient states.
        for (playlist in savedPlaylists()) {
            var changed = false
            for (song in songsOf(playlist)) {
                val status = song.optString("status")
                if (store.offline(song)) {
                    if (status != "ready") { song.put("status", "ready"); changed = true }
                } else if (status in listOf("ready", "caching", "queued")) {
                    song.put("status", "pending")
                    changed = true
                }
            }
            if (changed) save(playlist)
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
        }.filter { store.offline(it) }.associateBy { it.optString("id") }
        val songs = mutableListOf<JSONObject>()
        for (entry in importEntries(raw)) {
            val sid = entry.getString("id")
            if (store.unavailable(sid)) continue
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
                    for (key in listOf("title", "artist", "duration")) if (previous.has(key)) song.put(key, previous.get(key))
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
            val id = playlist.getString("id")
            replace(index(id), playlist.toString())
            revisions.merge(id, 1L, Long::plus)
        }

    /** Changes whenever the playlist is saved in this process, so unchanged ones need no reading. */
    fun revision(id: String): Long = revisions[id] ?: 0L

    fun snapshot(id: String): JSONObject? =
        synchronized(lock) {
            runCatching { JSONObject(index(id).readText()) }.getOrNull()
        }

    /** Fresh copies of every playlist, safe to change and [save]. */
    fun savedPlaylists(): List<JSONObject> = synchronized(lock) {
        indexFiles().mapNotNull { snapshot(it.nameWithoutExtension) }.let(::usable)
    }

    /**
     * Every playlist for reading only, parsed again only after it changes. Plans and screens
     * that look at large playlists every few seconds use this; callers must not modify them.
     */
    fun readPlaylists(): List<JSONObject> {
        val files = indexFiles()
        parsed.keys.retainAll(files.map { it.nameWithoutExtension }.toSet())
        return files.mapNotNull { file ->
            val id = file.nameWithoutExtension
            // Read before the file, so a save in between makes the next call parse it again.
            val version = revision(id) to file.lastModified()
            parsed[id]?.takeIf { it.first == version }?.second
                ?: snapshot(id)?.also { parsed[id] = version to it }
        }.let(::usable)
    }

    private fun indexFiles() = indexes.listFiles().orEmpty().filter { it.name.matches(INDEX_NAME) }

    private fun usable(playlists: List<JSONObject>) = playlists
        .filter { it.optString("id").isNotBlank() && it.optJSONArray("songs") != null }
        .sortedBy { it.optString("title").lowercase(java.util.Locale.ROOT) }

    fun protectPlayback(id: String) { playing.add(id) }
    fun releasePlayback(id: String) { playing.remove(id) }

    /**
     * Disk cleanup never changes playlists, scores or progression. It spares a playing song,
     * every song the preparation plan wants and playlists set to keep their downloads.
     */
    fun pruneUnused(days: Int, now: Long = System.currentTimeMillis()): Int = cleanup {
        require(days in 1..365)
        if (!store.prefs.getBoolean("smartStorage", true)) return@cleanup 0
        val cutoff = now - days * 86_400_000L
        val files = File(context.filesDir, "songs").listFiles().orEmpty().groupBy { it.name.substringBefore('.') }
        var removed = 0
        val playlists = readPlaylists()
        val kept = Preparation.wantedIds(context) + playlists.filter(store::keepsDownloads).flatMap(::songsOf).map { it.optString("id") }.toSet()
        for (entry in Arcade.pool(playlists)) {
            if (!store.prefs.getBoolean("smartStorage", true)) break
            if (entry.demo || entry.id in kept || entry.id in playing) continue
            val audio = store.songFile(entry.song, "mp3")
            if (!audio.isFile) continue
            // A fresh download gets a full retention period, even if it was played long ago.
            val lastUse = maxOf(store.lastPlayed(entry.song), audio.lastModified())
            if (lastUse >= cutoff) continue
            files[entry.id].orEmpty().forEach { it.delete() }
            removed++
        }
        if (removed > 0) synchronized(lock) {
            for (playlist in savedPlaylists()) {
                val songs = playlist.getJSONArray("songs")
                for (i in 0 until songs.length()) {
                    val song = songs.getJSONObject(i)
                    if (!store.offline(song) && song.optString("status") == "ready")
                        song.put("status", "pending")
                }
                save(playlist)
            }
        }
        removed
    }

    /** Whether the preparation workers should bring every song of this playlist onto the phone. */
    fun setDownloadAll(id: String, all: Boolean): JSONObject = update(id) { it.put("downloadAll", all) }

    fun rename(id: String, title: String): JSONObject = update(id) { it.put("title", title.trim().take(MAX_TITLE)) }

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
            for (file in listOf(store.songFile(song, "mp3"), store.chartFile(song), store.quickChartFile(song), store.songFile(song, "jpg")))
                file.delete()
        }
    }

    /**
     * Frees a playlist's downloads, except songs another playlist also has, the next level and
     * the locked one after it, and a song that is playing. Scores and unlocks are kept.
     */
    fun removeDownloads(id: String): Int = cleanup {
        val playlist = snapshot(id) ?: return@cleanup 0
        val shared = savedPlaylists().filter { it.optString("id") != id }.flatMap(::songsOf).map { it.optString("id") }.toSet()
        val next = store.essential(playlist).map { it.optString("id") }.toSet()
        val files = File(context.filesDir, "songs").listFiles().orEmpty().groupBy { it.name.substringBefore('.') }
        var removed = 0
        for (song in songsOf(playlist)) {
            val songId = song.optString("id")
            if (song.optBoolean("demo") || songId in shared || songId in next || songId in playing) continue
            val found = files[songId].orEmpty()
            val download = File(context.cacheDir, "downloads/$songId")
            if (found.isEmpty() && !download.exists()) continue
            found.forEach { it.delete() }
            download.deleteRecursively()
            removed++
        }
        synchronized(lock) {
            val latest = snapshot(id) ?: return@synchronized
            // Freeing the songs also stops downloading them all again.
            latest.remove("downloadAll")
            for (song in songsOf(latest))
                if (!store.offline(song) && song.optString("status") in setOf("ready", "error"))
                    song.put("status", "pending")
            save(latest)
        }
        removed
    }

    /** How many of [songs] are ready to play offline, and the space their files take. */
    fun usage(songs: List<JSONObject>): Pair<Int, Long> {
        val files = File(context.filesDir, "songs").listFiles().orEmpty().groupBy { it.name.substringBefore('.') }
        val ids = songs.filter { !it.optBoolean("demo") }.map { it.optString("id") }.toSet()
        val bytes = ids.sumOf { id ->
            files[id].orEmpty().sumOf { it.length() } +
                File(context.cacheDir, "downloads/$id").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        val offline = store.offlineCheck()
        return songs.count { !it.optBoolean("demo") && offline(it) } to bytes
    }

    fun setKeepDownloads(id: String, keep: Boolean): JSONObject = update(id) { it.put("keepDownloads", keep) }

    private fun update(id: String, change: (JSONObject) -> Unit): JSONObject = synchronized(lock) {
        val playlist = requireNotNull(snapshot(id)) { "Playlist not found." }
        change(playlist)
        save(playlist)
        playlist
    }

    private fun songsOf(playlist: JSONObject): List<JSONObject> {
        val songs = playlist.getJSONArray("songs")
        return (0 until songs.length()).map { songs.getJSONObject(it) }
    }

    fun setPreload(id: String, count: Int): JSONObject = update(id) {
        require(count in LibraryStore.MIN_PRELOAD..LibraryStore.MAX_PRELOAD)
        it.put("preloadCount", count)
    }

    /** Space taken by downloaded songs, their levels and covers, and unfinished downloads. */
    fun downloadedBytes(): Long = listOf(File(context.filesDir, "songs"), File(context.cacheDir, "downloads"))
        .sumOf { folder -> folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    fun clearDownloads() = cleanup {
        cacheGeneration++
        File(context.filesDir, "songs").deleteRecursively()
        File(context.cacheDir, "downloads").deleteRecursively()
        synchronized(lock) {
            for (playlist in savedPlaylists()) {
                val songs = playlist.getJSONArray("songs")
                for (i in 0 until songs.length()) {
                    val song = songs.getJSONObject(i)
                    song.put("status", if (store.offline(song)) "ready" else "pending")
                    song.remove("error")
                }
                save(playlist)
            }
        }
    }

    /**
     * Downloads one song's audio and cover into the songs folder, where it waits for its level.
     * [progress] receives a message and the whole preparation's fraction, of which the download
     * is the first [DOWNLOAD_SHARE]. Returns how long it took, or null when there was nothing to do.
     */
    fun download(
        playlistId: String,
        songId: String,
        generation: Long = cacheGeneration,
        progress: (String, Double) -> Unit,
    ): Long? = synchronized(downloadLock) {
        if (generation == cacheGeneration) downloadNow(playlistId, songId, progress) else null
    }

    /**
     * Makes the full level of a downloaded song. [shouldYield] is asked between steps of the
     * analysis; when it turns true, this stops with [Yielded] and the song waits for its turn
     * again. Returns how long it took, or null when there was nothing to do.
     */
    fun prepare(
        playlistId: String,
        songId: String,
        generation: Long = cacheGeneration,
        shouldYield: () -> Boolean = { false },
        progress: (String, Double) -> Unit,
    ): Long? = synchronized(chartLock) {
        if (generation == cacheGeneration) prepareNow(playlistId, songId, shouldYield, progress) else null
    }

    /** Thrown when making a level gives way to a song that is needed sooner. */
    class Yielded : RuntimeException("Preparation paused for another song")

    /** The song in its playlist, with the title the player may see: locked levels stay masked. */
    private fun locate(playlistId: String, songId: String): Pair<JSONObject, String> {
        val playlist = requireNotNull(snapshot(playlistId)) { "Playlist not found. Import it again." }
        val songs = songsOf(playlist)
        val position = songs.indexOfFirst { it.optString("id") == songId }
        require(position >= 0) { "Song not found. Import the playlist again." }
        val song = songs[position]
        return song to SongTitle.display(song.optString("title"), (0 until position).all { store.completed(songs[it]) })
    }

    private fun downloadNow(playlistId: String, songId: String, progress: (String, Double) -> Unit): Long? {
        val (song, title) = locate(playlistId, songId)
        if (store.downloaded(song)) return null
        // Progress stays in memory: saving it would rewrite the whole playlist every percent.
        fun report(message: String, fraction: Double) {
            preparing[songId] = message to fraction
            progress("$title · $message", fraction)
        }
        updateSong(playlistId, songId) { it.put("status", "caching").put("error", "") }
        report("Downloading", 0.0)
        val folder = File(context.cacheDir, "downloads/$songId").apply { deleteRecursively(); mkdirs() }
        val audio = File(folder, "source.mp3")
        val info = File(folder, "source.info.json")
        val started = System.currentTimeMillis()
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
            runtime.execute(request, "song-$songId", 600) { line ->
                val now = System.currentTimeMillis()
                if (now - lastProgress > 750 && (line.contains("[download]") || line.contains("[ExtractAudio]"))) {
                    lastProgress = now
                    val percent = Regex("([0-9.]+)%").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                    when {
                        line.contains("[ExtractAudio]") -> report("Converting audio", DOWNLOAD_SHARE - .03)
                        percent != null -> report("Downloading", percent.coerceIn(0.0, 100.0) / 100 * (DOWNLOAD_SHARE - .03))
                    }
                }
            }
            check(audio.isFile) { "This song could not be downloaded, or exceeds 20 minutes." }
            val metadata = runCatching { JSONObject(info.readText()) }.getOrDefault(JSONObject())
            File(folder, "source.jpg").takeIf { it.exists() }?.let {
                Files.move(it.toPath(), store.songFile(song, "jpg").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            // The audio arriving in the songs folder is what marks the song downloaded.
            Files.move(audio.toPath(), store.songFile(song, "mp3").toPath(), StandardCopyOption.REPLACE_EXISTING)
            // Downloaded metadata belongs to the song, so every playlist that has it gets the same.
            updateSongEverywhere(songId) { value ->
                value.put("status", "pending")
                    .put("title", metadata.optString("track").ifBlank { metadata.optString("title", value.optString("title")) })
                    .put("artist", metadata.optString("artist").ifBlank { metadata.optString("uploader", value.optString("artist")) })
                metadata.optDouble("duration", 0.0).takeIf { it > 0 }?.let { value.put("duration", it) }
            }
            folder.deleteRecursively()
            return System.currentTimeMillis() - started
        } catch (error: Throwable) {
            File(folder, "download.log").writeText(error.message ?: "Download failed")
            if (gone(error.message.orEmpty())) {
                folder.deleteRecursively()
                dropUnavailable(song)
            } else failed(playlistId, songId, error, connectionLost(error))
            throw error
        } finally {
            preparing.remove(songId)
        }
    }

    private fun prepareNow(playlistId: String, songId: String, shouldYield: () -> Boolean, progress: (String, Double) -> Unit): Long? {
        val (song, title) = locate(playlistId, songId)
        val audio = store.songFile(song, "mp3")
        if (!audio.isFile) return null
        if (store.chartFile(song).isFile) {
            updateSong(playlistId, songId) { it.put("status", "ready") }
            return null
        }
        fun report(message: String, fraction: Double) {
            preparing[songId] = message to fraction
            progress("$title · $message", fraction)
        }
        updateSong(playlistId, songId) { it.put("status", "caching").put("error", "") }
        val started = System.currentTimeMillis()
        try {
            val chart = writeChart(song, audio, songId, shouldYield) { message, fraction ->
                report(message, DOWNLOAD_SHARE + fraction * (1 - DOWNLOAD_SHARE))
            }
            store.quickChartFile(song).delete()
            updateSongEverywhere(songId) { it.put("status", "ready").put("duration", chart.duration) }
            return System.currentTimeMillis() - started
        } catch (error: Throwable) {
            if (error is Yielded) {
                updateSong(playlistId, songId) { it.put("status", if (store.offline(song)) "ready" else "pending") }
                throw error
            }
            // Making a level needs no connection, so its failure is about the song.
            failed(playlistId, songId, error, connection = false)
            throw error
        } finally {
            preparing.remove(songId)
            analysed.remove(songId)
        }
    }

    /**
     * Takes a song YouTube won't serve out of every playlist, so it can't hold up a journey, and
     * remembers it so imports leave it out. The player is told on the home screen.
     */
    private fun dropUnavailable(song: JSONObject) = synchronized(lock) {
        val songId = song.optString("id")
        android.util.Log.i("Keyflow", "Taking unavailable $songId out of the playlists")
        store.markUnavailable(songId, song.optString("title").ifBlank { "A song" })
        for (playlist in savedPlaylists()) {
            val songs = songsOf(playlist)
            val kept = songs.filter { it.optString("id") != songId }
            // A playlist keeps its last song, so it never ends up empty.
            if (kept.size != songs.size && kept.isNotEmpty()) save(playlist.put("songs", JSONArray(kept)))
        }
    }

    /** Whether yt-dlp's [message] says YouTube won't serve the song at all, rather than just now. */
    private fun gone(message: String) = UNAVAILABLE.containsMatchIn(message) && !message.contains("try again later", ignoreCase = true)

    private fun failed(playlistId: String, songId: String, error: Throwable, connection: Boolean) =
        updateSong(playlistId, songId) {
            it.put("status", "error")
                .put("failedAt", System.currentTimeMillis())
                .put("connection", connection)
                .put("error", error.message?.takeLast(2000) ?: "Download failed. Please retry.")
        }

    /**
     * Makes a quick level for a downloaded song from its beat, in seconds instead of minutes, so
     * the player can go on while the full level is still being made; the full one replaces it.
     * Reuses the beat when the full level's analysis already found it. [progress] gets 0–1.
     */
    fun quickLevel(song: JSONObject, progress: (Double) -> Unit = {}) {
        val songId = song.getString("id")
        if (store.offline(song)) return
        val audio = store.songFile(song, "mp3")
        check(audio.isFile) { "This song isn't downloaded yet." }
        // The full level may be finding this song's beat right now; sharing it beats repeating it.
        while (analysed[songId] == null) {
            val (stage, fraction) = preparing[songId] ?: break
            if (stage != FINDING_BEAT) break
            progress(((fraction - DOWNLOAD_SHARE) / (1 - DOWNLOAD_SHARE) / BEAT_SHARE).coerceIn(0.0, .95))
            Thread.sleep(100)
        }
        if (store.offline(song)) return
        val (features, rhythm) = analysed[songId] ?: run {
            progress(0.0)
            val features = runtime.audioSamples(audio, "quick-features-$songId", MusicFeatures.RATE, MusicFeatures::readPcm)
            progress(.4)
            features to BeatThis(context).analyze(features) { progress(.4 + it * .0055) }
        }
        check(rhythm.beats.size >= 4) { "This recording has no clear rhythm to create a level. Try another song." }
        val chart = QuickChart.generate(features, rhythm)
        // The full level may have arrived meanwhile; it always wins.
        if (!store.chartFile(song).isFile) replace(store.quickChartFile(song), PreparedChart.encode(chart).toString())
        updateSongEverywhere(songId) { it.put("status", "ready") }
        progress(1.0)
    }

    /** Whether [error] came from a lost or missing connection rather than from the song. */
    fun connectionLost(error: Throwable) = !Preparation.connected(context) || NETWORK_ERROR.containsMatchIn(error.message.orEmpty())

    /**
     * The stage and whole-preparation fraction of a song being worked on now. A downloaded song
     * waiting for its level counts its download as done.
     */
    fun preparing(song: JSONObject): Pair<String, Double>? =
        preparing[song.optString("id")] ?: if (store.downloaded(song)) "Downloaded · next to prepare" to DOWNLOAD_SHARE else null

    /** [progress] gets this step's own fraction; the chart model is most of the work. */
    private fun writeChart(
        song: JSONObject,
        audio: File,
        songId: String,
        shouldYield: () -> Boolean,
        progress: (String, Double) -> Unit,
    ): Chart {
        progress(FINDING_BEAT, 0.0)
        val features = runtime.audioSamples(audio, "rhythm-features-$songId", MusicFeatures.RATE, MusicFeatures::readPcm)
        val rhythm = BeatThis(context).analyze(features) { if (shouldYield()) throw Yielded() }
        check(rhythm.beats.size >= 4) { "This recording has no clear rhythm to create a level. Try another song." }
        // A quick level asked for while the tiles are placed can start from this beat.
        analysed[songId] = features to rhythm
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
        replace(store.chartFile(song), PreparedChart.encode(chart).toString())
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

    private fun updateSongEverywhere(songId: String, update: (JSONObject) -> Unit) =
        synchronized(lock) {
            for (playlist in savedPlaylists()) {
                val songs = songsOf(playlist).filter { it.getString("id") == songId }
                if (songs.isEmpty()) continue
                songs.forEach(update)
                save(playlist)
            }
        }

    /** Cleanup waits for the song being downloaded and the level being made to finish. */
    private fun <T> cleanup(block: () -> T): T = synchronized(downloadLock) { synchronized(chartLock) { block() } }

    private fun index(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid playlist ID" }
        return File(indexes, "$id.json")
    }

    companion object {
        // Process-wide, so a save through any instance is seen by every reader.
        private val revisions = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val SONG_ID = Regex("[A-Za-z0-9_-]{11}")
        private val INDEX_NAME = Regex("[A-Za-z0-9_-]{1,80}\\.json")
        private val UNAVAILABLE = Regex("Video unavailable|This video is (not|no longer) available|Private video|" +
            "video has been removed|not (made )?available in your country|account associated with this video has been terminated|" +
            "confirm your age|members-only|Join this channel", RegexOption.IGNORE_CASE)
        private val NETWORK_ERROR = Regex("Network is unreachable|timed out|Temporary failure in name resolution|" +
            "Name or service not known|Connection (refused|reset|aborted)|No address associated", RegexOption.IGNORE_CASE)

        /**
         * Writes [file] whole or not at all. The text reaches the disk before the rename, so a
         * crash or power cut can't leave an empty playlist or chart behind.
         */
        fun replace(file: File, text: String) {
            val temporary = File(file.path + ".part")
            java.io.FileOutputStream(temporary).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        /** Shares of a song's preparation: the download, then beats before the slower chart model. */
        const val DOWNLOAD_SHARE = .35
        const val BEAT_SHARE = .15
        private const val FINDING_BEAT = "Finding the beat"
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
