package com.keyflow.pianotiles.library

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import com.keyflow.pianotiles.arcade.ArcadeSongs
import java.util.concurrent.Executors

/**
 * The app's side of the single preparation worker: what it should do next, the song the player
 * is waiting for, and how long until a playlist can be played.
 */
object Preparation {
    /** The song the player opened and is waiting for, as playlist and song IDs. */
    @Volatile var waiting: Pair<String, String>? = null
        private set

    /** The song being prepared now. */
    class Current(val songId: String, val startedAt: Long) {
        @Volatile var fraction = 0.0
    }

    @Volatile var current: Current? = null
        internal set

    /** An Arcade run is being played, so its next song comes first when none fresh is ready. */
    @Volatile var arcadePlaying = false

    private val planner = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "preparation-plan")
    }

    /** Starts the worker when something wanted is missing; checked off the main thread. */
    fun wake(context: Context) {
        val app = context.applicationContext
        planner.execute { if (runCatching { next(app) != null }.getOrDefault(false)) start(app) }
    }

    /** The player opened [songId] and waits for it: it goes first, and other songs give way. */
    fun request(context: Context, playlistId: String, songId: String) {
        waiting = playlistId to songId
        start(context.applicationContext)
    }

    /** The player stopped waiting for [songId]; it keeps its place among the other wanted songs. */
    fun release(songId: String) {
        if (waiting?.second == songId) waiting = null
    }

    private fun start(context: Context) {
        // Starting a foreground service can be refused while the app is in the background.
        runCatching { context.startForegroundService(Intent(context, DownloadService::class.java)) }
    }

    fun inputs(context: Context): PreparationPlan.Inputs {
        val store = LibraryStore(context)
        return PreparationPlan.Inputs(
            OnDeviceLibrary.get(context).savedPlaylists(),
            store.prefs.getString("playlistId", null),
            store::completed,
            store::offline,
            ArcadeSongs.history(store),
            waiting,
            arcadePlaying = arcadePlaying,
        )
    }

    /** Every song the plan wants kept, ready or not; cleanup leaves these alone. */
    fun wantedIds(context: Context): Set<String> = PreparationPlan.wanted(inputs(context)).map { it.id }.toSet()

    /** The next song to prepare, or null when nothing is missing or there is no internet to get it. */
    fun next(context: Context, skip: Set<String> = emptySet()): PreparationPlan.Item? {
        val inputs = inputs(context)
        val store = LibraryStore(context)
        val pending = PreparationPlan.pending(PreparationPlan.wanted(inputs), store::offline, System.currentTimeMillis())
            .filter { it.id !in skip }
        val first = pending.firstOrNull() ?: return null
        return first.takeIf { connected(context) || it.reason == PreparationPlan.Reason.WAITING }
    }

    /** Whether a playlist's next level can be played, and if not, when it will be. */
    sealed interface Readiness {
        object Ready : Readiness
        class Waiting(val ms: Long) : Readiness
        object Offline : Readiness
        object Failed : Readiness
    }

    /** Readiness of every imported playlist, by ID, from one look at the plan. */
    fun readiness(context: Context): Map<String, Readiness> {
        val store = LibraryStore(context)
        val inputs = inputs(context)
        val now = System.currentTimeMillis()
        val pending = PreparationPlan.pending(PreparationPlan.wanted(inputs), store::offline, now)
        val online = connected(context)
        val average = averageMs(context)
        val working = current
        fun left(id: String) = if (id == working?.songId) ((1 - working.fraction) * average).toLong() else average
        return inputs.playlists.associate { playlist ->
            val next = PreparationPlan.window(playlist, store::completed).firstOrNull()
            playlist.optString("id") to when {
                next == null || store.offline(next) -> Readiness.Ready
                PreparationPlan.recentlyFailed(next, now) -> Readiness.Failed
                !online -> Readiness.Offline
                else -> {
                    val ahead = pending.take(pending.indexOfFirst { it.id == next.optString("id") } + 1)
                    // The song in progress finishes first, then the queue runs in order up to this one.
                    val unfinished = working?.songId?.takeIf { id -> ahead.none { it.id == id } }
                    Readiness.Waiting(ahead.sumOf { left(it.id) } + (unfinished?.let(::left) ?: 0L))
                }
            }
        }
    }

    /** "about 3 min" style wording for a wait. */
    fun describe(ms: Long): String = if (ms < 60_000) "under a minute" else "about ${(ms + 30_000) / 60_000} min"

    /** How long one song usually takes on this phone, learned from finished ones. */
    fun averageMs(context: Context): Long = LibraryStore(context).prefs.getLong(AVERAGE_KEY, DEFAULT_MS)

    internal fun finished(context: Context, ms: Long) {
        val prefs = LibraryStore(context).prefs
        val average = prefs.getLong(AVERAGE_KEY, DEFAULT_MS)
        prefs.edit().putLong(AVERAGE_KEY, (average * .7 + ms.coerceIn(5_000, 20 * 60_000) * .3).toLong()).apply()
    }

    fun connected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private const val AVERAGE_KEY = "preparation.averageMs"
    private const val DEFAULT_MS = 75_000L
}
