package com.keyflow.pianotiles

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Maintains a small random buffer without blocking playback or the mode picker. */
class ArcadePreparation internal constructor(
    context: Context,
    private val online: (Context) -> Boolean = { connected(it) },
    private val request: (Context, Arcade.Entry) -> Unit = { c, entry ->
        DownloadService.start(c, entry.playlistId, entry.id, background = true)
    },
) {
    private val context = context.applicationContext
    private val store = LibraryStore(this.context)
    private val library = OnDeviceLibrary.get(this.context)
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "arcade-preparation")
    }
    @Volatile private var enabled = false
    @Volatile private var excluded = emptySet<String>()
    @Volatile private var plannedIds = store.prefs.getStringSet("arcade.upcoming", emptySet()).orEmpty().toList()
    // Accessed exclusively on the preparation thread.
    private val pending = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, Long>()
    private var generation = library.cacheGeneration

    init {
        worker.scheduleWithFixedDelay({ if (enabled) runCatching { replenish() } }, 0, 10, TimeUnit.SECONDS)
    }

    fun start(exclude: Collection<String> = emptyList()) {
        excluded = exclude.toSet()
        enabled = true
        worker.execute { if (enabled) runCatching { replenish() } }
    }

    fun pause() { enabled = false }
    fun close() { enabled = false; worker.shutdownNow() }

    fun pick(ready: List<Arcade.Entry>, recent: Collection<String>, random: Random): Arcade.Entry? {
        val planned = plannedIds
        val next = ready.filter { it.id in planned && it.id !in recent }
        return Arcade.pick(next.ifEmpty { ready }, recent, random)
    }

    private fun replenish() {
        if (generation != library.cacheGeneration) {
            generation = library.cacheGeneration
            pending.clear()
            failures.clear()
            plannedIds = emptyList()
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
        val connected = online(context)
        val plan = if (connected) Arcade.plan(pool, plannedIds, excluded, failures.keys, Random.Default)
            else pool.filter { it.id in plannedIds && it.id !in excluded }
        plannedIds = plan.map { it.id }
        store.prefs.edit().putStringSet("arcade.upcoming", plannedIds.toSet()).apply()
        val reserve = ready.filter { it !in excluded }.shuffled().take(Arcade.READY_TARGET).toMutableSet()
        reserve.addAll(plannedIds)
        reserve.addAll(pending.keys)
        store.prefs.edit().putStringSet("arcade.reserve", reserve).apply()
        if (!connected) return
        val wanted = Arcade.prefetch(plan, ready, pending.keys, excluded, failures.keys, Random.Default)
        // Protect a re-charted older audio file before a queued cleanup can acquire the cache lock.
        reserve.addAll(wanted.map { it.id })
        store.prefs.edit().putStringSet("arcade.reserve", reserve).apply()
        for (entry in wanted) {
            if (!enabled) break
            request(context, entry)
            pending[entry.id] = now
        }
    }

    companion object {
        fun connected(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = manager.activeNetwork ?: return false
            return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }
}
