package com.keyflow.pianotiles.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Process
import com.keyflow.pianotiles.arcade.ArcadeSongs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * The app's side of the single preparation worker: what it should do next, the song the player
 * is waiting for, and how long until a playlist can be played.
 */
object Preparation {
    /** The song the player opened and is waiting for, as playlist and song IDs. */
    @Volatile var waiting: Pair<String, String>? = null
        private set

    /** The song being prepared now. */
    class Current(val song: JSONObject) {
        val songId: String = song.optString("id")
        @Volatile var fraction = 0.0
    }

    @Volatile var current: Current? = null
        internal set

    /** An Arcade run is being played, so its next song comes first when none fresh is ready. */
    @Volatile var arcadePlaying = false

    /** A game is on screen, so the worker leaves it most of the phone. */
    @Volatile var playing = false

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

    private val watching = AtomicBoolean()
    @Volatile private var unmetered: Boolean? = null

    /**
     * Starts the worker again when the phone gets a connection, Wi-Fi or a charger, which songs
     * may be waiting for. Works while the app is open; Android stops background starts.
     */
    fun watch(context: Context) {
        val app = context.applicationContext
        if (!watching.compareAndSet(false, true)) return
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = wake(app)

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val now = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                if (unmetered != now) {
                    unmetered = now
                    wake(app)
                }
            }
        })
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = wake(app)
        }, IntentFilter(Intent.ACTION_POWER_CONNECTED))
    }

    private fun start(context: Context) {
        // Starting a foreground service can be refused while the app is in the background.
        runCatching { context.startForegroundService(Intent(context, DownloadService::class.java)) }
    }

    /** One look at the library: playlists parsed only after they change and one folder listing. */
    fun inputs(context: Context): PreparationPlan.Inputs {
        val store = LibraryStore(context)
        return PreparationPlan.Inputs(
            OnDeviceLibrary.get(context).readPlaylists(),
            store.prefs.getString("playlistId", null),
            store::completed,
            store.offlineCheck(),
            ArcadeSongs.history(store),
            waiting,
            arcadePlaying = arcadePlaying,
        )
    }

    /** Every song the plan wants kept, ready or not; cleanup leaves these alone. */
    fun wantedIds(context: Context): Set<String> = PreparationPlan.wanted(inputs(context)).map { it.id }.toSet()

    /**
     * The next song to prepare, or null when nothing is missing or there is no internet to get it.
     * Download ahead and Download all wait for Wi-Fi or a charger when the player asked for that.
     */
    fun next(context: Context, skip: Set<String> = emptySet()): PreparationPlan.Item? {
        val inputs = inputs(context)
        val big = allowsBigDownloads(context)
        val pending = PreparationPlan.pending(PreparationPlan.wanted(inputs), inputs.ready, System.currentTimeMillis())
            .filter { it.id !in skip && (big || !PreparationPlan.big(it.reason)) }
        val first = pending.firstOrNull() ?: return null
        return first.takeIf { connected(context) || it.reason == PreparationPlan.Reason.WAITING }
    }

    /** Settings for Download ahead and Download all; the next two levels and Arcade always download. */
    const val WIFI_ONLY = "downloads.wifiOnly"
    const val CHARGING_ONLY = "downloads.chargingOnly"

    /** Whether Download ahead and Download all may run now, given the player's settings. */
    fun allowsBigDownloads(context: Context) = bigDownloadsWaitFor(context) == null

    /** What Download ahead and Download all are waiting for, or null when they may run. */
    fun bigDownloadsWaitFor(context: Context): String? {
        val prefs = LibraryStore(context).prefs
        val wifi = prefs.getBoolean(WIFI_ONLY, true) && !unmeteredNow(context)
        val charger = prefs.getBoolean(CHARGING_ONLY, false) && !charging(context)
        return when {
            wifi && charger -> "Wi-Fi and a charger"
            wifi -> "Wi-Fi"
            charger -> "a charger"
            else -> null
        }
    }

    private fun unmeteredNow(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    // Plugged in, from the battery's latest broadcast; BatteryManager.isCharging lags behind it.
    private fun charging(context: Context) =
        (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

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
        val pending = PreparationPlan.pending(PreparationPlan.wanted(inputs), inputs.ready, now)
        val online = connected(context)
        val time = PreparationTime(store.prefs)
        val library = OnDeviceLibrary.get(context)
        val working = current
        fun left(song: JSONObject) = if (song.optString("id") == working?.songId) time.remaining(song, working.fraction)
            else time.estimate(song, library.downloaded(song.optString("id")))
        return inputs.playlists.associate { playlist ->
            val next = PreparationPlan.window(playlist, store::completed).firstOrNull()
            playlist.optString("id") to when {
                next == null || inputs.ready(next) -> Readiness.Ready
                PreparationPlan.recentlyFailed(next, now) -> Readiness.Failed
                !online -> Readiness.Offline
                else -> {
                    val ahead = pending.take(pending.indexOfFirst { it.id == next.optString("id") } + 1)
                    // The song in progress finishes first, then the queue runs in order up to this one.
                    val unfinished = working?.takeIf { song -> ahead.none { it.id == song.songId } }
                    Readiness.Waiting(ahead.sumOf { left(it.song) } + (unfinished?.let { left(it.song) } ?: 0L))
                }
            }
        }
    }

    /** A song the worker still has to prepare, as the download queue shows it. */
    class Queued(val item: PreparationPlan.Item, val playlist: String, val title: String, val state: QueueState)

    sealed interface QueueState {
        /** Being prepared now: the stage, the whole preparation's fraction and the time left. */
        class Preparing(val stage: String, val fraction: Double, val leftMs: Long) : QueueState
        /** Waiting its turn; ready in about [readyInMs]. */
        class InLine(val readyInMs: Long) : QueueState
        /** Not taken until something changes, such as Wi-Fi coming back. */
        class Held(val reason: String) : QueueState
    }

    /**
     * Every wanted song that isn't ready yet, in the order the worker takes them, with the song
     * in progress first and songs held back last. Locked levels keep their masked titles.
     */
    fun queue(context: Context): List<Queued> {
        val store = LibraryStore(context)
        val inputs = inputs(context)
        val now = System.currentTimeMillis()
        val online = connected(context)
        val waitFor = bigDownloadsWaitFor(context)
        val time = PreparationTime(store.prefs)
        val library = OnDeviceLibrary.get(context)
        val working = current
        val playlists = inputs.playlists.associateBy { it.optString("id") }
        // A level is unlocked up to the first one not cleared.
        val unlocked = HashMap<String, Set<String>>()
        fun unlocked(playlistId: String, songId: String) = songId in unlocked.getOrPut(playlistId) {
            val songs = playlists[playlistId]?.optJSONArray("songs") ?: return@getOrPut emptySet()
            val open = (0 until songs.length()).firstOrNull { !store.completed(songs.getJSONObject(it)) } ?: songs.length()
            (0..minOf(open, songs.length() - 1)).map { songs.getJSONObject(it).optString("id") }.toSet()
        }
        var clock = 0L
        return PreparationPlan.wanted(inputs).filter { !inputs.ready(it.song) }
            .sortedByDescending { it.id == working?.songId }
            .map { item ->
                val preparing = library.preparing(item.id)?.takeIf { item.id == working?.songId }
                val state = when {
                    preparing != null -> {
                        val left = time.remaining(item.song, preparing.second)
                        clock += left
                        QueueState.Preparing(preparing.first, preparing.second, left)
                    }
                    item.reason != PreparationPlan.Reason.WAITING && PreparationPlan.recentlyFailed(item.song, now) ->
                        QueueState.Held("Couldn't prepare · tries again in ${describe(PreparationPlan.retryAt(item.song) - now)}")
                    !online && item.reason != PreparationPlan.Reason.WAITING -> QueueState.Held("Waits for an internet connection")
                    PreparationPlan.big(item.reason) && waitFor != null -> QueueState.Held("Waits for $waitFor")
                    else -> {
                        clock += time.estimate(item.song, library.downloaded(item.id))
                        QueueState.InLine(clock)
                    }
                }
                val title = item.song.optString("title").ifBlank { "Untitled song" }
                Queued(item, playlists[item.playlistId]?.optString("title").orEmpty(),
                    SongTitle.display(title, unlocked(item.playlistId, item.id)), state)
            }
            .sortedBy { it.state is QueueState.Held }
    }

    /** "about 3 min" style wording for a wait. */
    fun describe(ms: Long): String = if (ms < 60_000) "under a minute" else "about ${(ms + 30_000) / 60_000} min"

    internal fun finished(context: Context, timing: OnDeviceLibrary.Timing) = PreparationTime(LibraryStore(context).prefs).learn(timing)

    fun connected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
