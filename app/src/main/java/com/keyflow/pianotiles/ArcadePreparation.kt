package com.keyflow.pianotiles

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Keeps a few fresh songs ready for the arcade modes without blocking playback or the mode
 * picker, and remembers what Arcade played so the songs played longest ago come back first.
 */
class ArcadePreparation internal constructor(
    context: Context,
    private val online: (Context) -> Boolean = { connected(it) },
    private val request: (Context, Arcade.Entry, Boolean) -> Unit = { c, entry, idle ->
        DownloadService.start(c, entry.playlistId, entry.id, background = true, idle = idle)
    },
) {
    private val context = context.applicationContext
    private val store = LibraryStore(this.context)
    private val library = OnDeviceLibrary.get(this.context)
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "arcade-preparation")
    }
    @Volatile private var enabled = false
    // Accessed exclusively on the preparation thread.
    private val pending = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, Long>()
    private var generation = library.cacheGeneration

    init {
        worker.scheduleWithFixedDelay({ if (enabled) runCatching { replenish(idle = false) } }, 0, 10, TimeUnit.SECONDS)
    }

    /** Keeps the buffer full while the player is in Arcade. */
    fun start() {
        enabled = true
        worker.execute { if (enabled) runCatching { replenish(idle = false) } }
    }

    /**
     * Fills the buffer once from the home screen, after the Journey's own downloads, so the first
     * run already has fresh songs.
     */
    fun prepareSoon() {
        worker.schedule({ runCatching { replenish(idle = true) } }, HOME_DELAY_S, TimeUnit.SECONDS)
    }

    fun pause() { enabled = false }
    fun close() { enabled = false; worker.shutdownNow() }

    fun pick(ready: List<Arcade.Entry>, recent: Collection<String>, random: Random): Arcade.Entry? {
        val allowed = eligible(library.arcadeSongs()).map { it.id }.toSet()
        // Locked levels are only played when nothing else, not even a built-in song, is ready.
        val choices = ready.filter { it.id in allowed }.ifEmpty { ready.filter { it.demo } }.ifEmpty { ready }
        return Arcade.pick(choices, recent, random, history())
    }

    /** Remembers a song Arcade is about to play. */
    fun played(id: String) = synchronized(this) {
        val history = (history().filter { it != id } + id).takeLast(Arcade.HISTORY)
        store.prefs.edit().putString(HISTORY_KEY, history.joinToString(",")).apply()
    }

    fun history(): List<String> =
        store.prefs.getString(HISTORY_KEY, "").orEmpty().split(',').filter { it.isNotBlank() }

    /** How far past each playlist's next level a song is; a song in several playlists takes the nearest. */
    private fun eligible(pool: List<Arcade.Entry>): List<Arcade.Entry> {
        val distance = HashMap<String, Int>()
        for (playlist in library.savedPlaylists()) {
            val songs = playlist.getJSONArray("songs")
            val next = (0 until songs.length()).firstOrNull { !store.completed(songs.getJSONObject(it)) } ?: songs.length()
            for (i in 0 until songs.length()) distance.merge(songs.getJSONObject(i).optString("id"), i - next, ::minOf)
        }
        return Arcade.eligible(pool, distance)
    }

    private fun replenish(idle: Boolean) {
        if (generation != library.cacheGeneration) {
            generation = library.cacheGeneration
            pending.clear()
            failures.clear()
        }
        val pool = library.arcadeSongs()
        val ready = pool.filter { store.offline(it.song) }.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        val byId = pool.associateBy { it.id }
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val (id, since) = iterator.next()
            if (id in ready || id !in byId) iterator.remove()
            else if (byId[id]?.song?.let { it.optString("status") == "error" && it.optLong("failedAt", 0) >= since } == true || now - since > 660_000) {
                failures[id] = now
                iterator.remove()
            }
        }
        failures.entries.removeAll { now - it.value > 300_000 }
        val eligible = eligible(pool)
        val history = history()
        val stale = Arcade.stale(eligible.size, history)
        // Cleanup keeps the songs a run will reach for first.
        val lastPlayed = history.withIndex().associate { it.value to it.index }
        val reserve = eligible.filter { it.id in ready && it.id !in stale }.sortedBy { lastPlayed[it.id] ?: -1 }
            .take(Arcade.BUFFER).map { it.id }.toMutableSet()
        reserve.addAll(pending.keys)
        store.prefs.edit().putStringSet("arcade.reserve", reserve).apply()
        if (!online(context)) return
        val wanted = Arcade.prefetch(eligible, ready, pending.keys, history, failures.keys, Random.Default)
        if (wanted.isEmpty()) return
        // Protect a re-charted older audio file before a queued cleanup can acquire the cache lock.
        reserve.addAll(wanted.map { it.id })
        store.prefs.edit().putStringSet("arcade.reserve", reserve).apply()
        for (entry in wanted) {
            if (!idle && !enabled) break
            request(context, entry, idle)
            pending[entry.id] = now
        }
    }

    companion object {
        private const val HISTORY_KEY = "arcade.history"
        private const val HOME_DELAY_S = 5L

        fun connected(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = manager.activeNetwork ?: return false
            return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }
}
