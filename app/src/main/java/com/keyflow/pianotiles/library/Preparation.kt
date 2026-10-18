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
 * The app's side of the two preparation workers, one downloading songs and one making their
 * levels: what each should do next, the song the player is waiting for, and how long until a
 * playlist can be played.
 */
object Preparation {
    /** The song the player opened and is waiting for, as playlist and song IDs. */
    @Volatile var waiting: Pair<String, String>? = null
        private set

    /** A song one of the workers is on now. */
    class Current(val song: JSONObject) {
        val songId: String = song.optString("id")
    }

    /** The song being downloaded now. */
    @Volatile var downloading: Current? = null
        internal set

    /** The song whose level is being made now. */
    @Volatile var making: Current? = null
        internal set

    /** An Arcade run is being played, so its next song comes first when none fresh is ready. */
    @Volatile var arcadePlaying = false

    /** A game is on screen, so the worker making levels leaves it most of the phone. */
    @Volatile var playing = false

    private val planner = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "preparation-plan")
    }

    /** Starts the workers when something wanted is missing; checked off the main thread. */
    fun wake(context: Context) {
        val app = context.applicationContext
        planner.execute {
            if (runCatching { nextQuick(app) != null || nextDownload(app) != null || nextLevel(app) != null }.getOrDefault(false)) start(app)
        }
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
     * Starts the workers again when the phone gets a connection, Wi-Fi or a charger, which songs
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
        val files = store.files()
        return PreparationPlan.Inputs(
            OnDeviceLibrary.get(context).readPlaylists(),
            store.prefs.getString("playlistId", null),
            store::completed,
            files::playable,
            ArcadeSongs.history(store),
            waiting,
            arcadePlaying = arcadePlaying,
            downloaded = files::downloaded,
            prepared = files::prepared,
        )
    }

    /** Every song the plan wants kept, ready or not; cleanup leaves these alone. */
    fun wantedIds(context: Context): Set<String> = PreparationPlan.wanted(inputs(context)).map { it.id }.toSet()

    /**
     * The next song to download, or null when none is missing or there is no internet to get it.
     * Download ahead and Download all wait for Wi-Fi or a charger when the player asked for that.
     */
    fun nextDownload(context: Context, skip: Set<String> = emptySet()): PreparationPlan.Item? {
        if (!connected(context)) return null
        val inputs = inputs(context)
        val big = bigDownloadsWaitFor(context) == null
        return PreparationPlan.toDownload(PreparationPlan.wanted(inputs), inputs)
            .firstOrNull { it.id !in skip && (big || !PreparationPlan.big(it.reason)) }
    }

    /**
     * A downloaded song that can't be played yet. A quick level makes it playable in seconds, so
     * nothing waits for the full level, which replaces it later when full levels are on. Songs
     * the plan wants come first, then every other downloaded song, as extras. Download ahead,
     * Download all and the extras wait for a charger when the player asked for that.
     */
    fun nextQuick(context: Context, skip: Set<String> = emptySet()): PreparationPlan.Item? {
        val inputs = inputs(context)
        val big = bigLevelsWaitFor(context) == null
        val wanted = PreparationPlan.wanted(inputs)
        val extras = if (!big) emptySequence() else inputs.playlists.asSequence().flatMap { playlist ->
            val songs = playlist.getJSONArray("songs")
            (0 until songs.length()).asSequence().map {
                PreparationPlan.Item(playlist.optString("id"), songs.getJSONObject(it), PreparationPlan.Reason.ALL)
            }
        }
        return (wanted.asSequence() + extras).firstOrNull {
            it.id !in skip && inputs.downloaded(it.song) && !inputs.ready(it.song) &&
                (big || !PreparationPlan.big(it.reason)) &&
                (it.reason == PreparationPlan.Reason.WAITING || !PreparationPlan.recentlyFailed(it.song, inputs.now))
        }
    }

    /**
     * The next downloaded song to make a full level for, or null when none is waiting. Download
     * ahead and Download all wait for a charger when the player asked for that.
     */
    fun nextLevel(context: Context, skip: Set<String> = emptySet()): PreparationPlan.Item? {
        if (!fullLevels(context)) return null
        val inputs = inputs(context)
        val big = bigLevelsWaitFor(context) == null
        return PreparationPlan.toPrepare(PreparationPlan.wanted(inputs), inputs)
            .firstOrNull { it.id !in skip && (big || !PreparationPlan.big(it.reason)) }
    }

    /**
     * Settings for Download ahead and Download all; the next two levels and Arcade always go
     * ahead. Wi-Fi holds back their downloads, a charger also the making of their levels.
     */
    const val WIFI_ONLY = "downloads.wifiOnly"
    /** Whether the chart model makes full levels to replace the quick ones; on by default. */
    const val FULL_LEVELS = "levels.full"
    const val CHARGING_ONLY = "downloads.chargingOnly"

    fun fullLevels(context: Context) = LibraryStore(context).prefs.getBoolean(FULL_LEVELS, true)

    /** What downloads for Download ahead and Download all wait for, or null when they may run. */
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

    /** What making levels for Download ahead and Download all waits for, or null when it may run. */
    fun bigLevelsWaitFor(context: Context): String? =
        "a charger".takeIf { LibraryStore(context).prefs.getBoolean(CHARGING_ONLY, false) && !charging(context) }

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
        /** Ready in about [ms]; once [downloaded], a quick level can be played straight away. */
        class Waiting(val ms: Long, val downloaded: Boolean = false) : Readiness
        object Offline : Readiness
        object Failed : Readiness
    }

    /** Readiness of every playlist's next level, keyed by playlist ID. */
    fun readiness(context: Context): Map<String, Readiness> {
        val queues = queues(context)
        val inputs = queues.inputs
        val now = System.currentTimeMillis()
        val time = PreparationTime(LibraryStore(context).prefs)
        return inputs.playlists.associate { playlist ->
            val next = PreparationPlan.window(playlist, inputs.completed).firstOrNull()
            playlist.optString("id") to when {
                next == null || inputs.ready(next) -> Readiness.Ready
                PreparationPlan.recentlyFailed(next, now) -> Readiness.Failed
                !inputs.downloaded(next) && !queues.online -> Readiness.Offline
                // Playable once it is downloaded and has its quick level, which comes right after.
                else -> {
                    val downloaded = inputs.downloaded(next)
                    val wait = if (downloaded) PreparationTime.QUICK_MS
                        else queues.schedule.downloaded[next.optString("id")] ?: (time.downloadMs + PreparationTime.QUICK_MS)
                    Readiness.Waiting(wait, downloaded)
                }
            }
        }
    }

    /** A song in one of the queues, as the queue page shows it. */
    class Queued(
        val item: PreparationPlan.Item,
        val playlist: String,
        val title: String,
        val state: QueueState,
        /** It already has a quick level to play while its full level is made. */
        val quick: Boolean = false,
    )

    sealed interface QueueState {
        /** Being worked on now: the stage, the whole preparation's fraction and the time left. */
        class Working(val stage: String, val fraction: Double, val leftMs: Long) : QueueState
        /** Waiting its turn; done in about [doneInMs]. */
        class InLine(val doneInMs: Long) : QueueState
        /** Not taken until something changes, such as Wi-Fi coming back. */
        class Held(val reason: String) : QueueState
    }

    /**
     * The two workers' queues: songs to download, and downloaded songs whose full level is still
     * to make. Each is in the order its worker takes them, with the song in progress first and
     * songs held back last. Locked levels keep their masked titles.
     */
    class Queues(
        val downloads: List<Queued>,
        val levels: List<Queued>,
        /** Whether the chart model makes full levels; without them, quick levels are kept. */
        val fullLevels: Boolean,
        val schedule: PreparationTime.Schedule,
        internal val inputs: PreparationPlan.Inputs,
        internal val online: Boolean,
    )

    fun queues(context: Context): Queues {
        val store = LibraryStore(context)
        val inputs = inputs(context)
        val now = System.currentTimeMillis()
        val online = connected(context)
        val downloadWait = bigDownloadsWaitFor(context)
        val levelWait = bigLevelsWaitFor(context)
        val time = PreparationTime(store.prefs)
        val library = OnDeviceLibrary.get(context)
        val wanted = PreparationPlan.wanted(inputs)
        val playlists = inputs.playlists.associateBy { it.optString("id") }
        // A level is unlocked up to the first one not cleared.
        val unlocked = HashMap<String, Set<String>>()
        fun unlocked(playlistId: String, songId: String) = songId in unlocked.getOrPut(playlistId) {
            val songs = playlists[playlistId]?.optJSONArray("songs") ?: return@getOrPut emptySet()
            val open = (0 until songs.length()).firstOrNull { !inputs.completed(songs.getJSONObject(it)) } ?: songs.length()
            (0..minOf(open, songs.length() - 1)).map { songs.getJSONObject(it).optString("id") }.toSet()
        }
        fun held(item: PreparationPlan.Item, download: Boolean): String? {
            val waitFor = if (download) downloadWait else levelWait
            return when {
                item.reason != PreparationPlan.Reason.WAITING && PreparationPlan.recentlyFailed(item.song, now) ->
                    "Couldn't ${if (download) "download" else "prepare"} · tries again in ${describe(PreparationPlan.retryAt(item.song) - now)}"
                download && !online -> "Waits for an internet connection"
                PreparationPlan.big(item.reason) && waitFor != null -> "Waits for $waitFor"
                else -> null
            }
        }
        fun progress(item: PreparationPlan.Item, current: Current?) =
            library.preparing(item.song)?.takeIf { item.id == current?.songId }

        val download = downloading
        val level = making
        val toDownload = wanted.filter { !inputs.downloaded(it.song) }.sortedByDescending { it.id == download?.songId }
        // With full levels off, quick levels are all there is to make.
        val full = fullLevels(context)
        val toPrepare = if (!full) emptyList() else wanted.filter { !inputs.prepared(it.song) }.sortedByDescending { it.id == level?.songId }
        val downloadHeld = toDownload.associate { it.id to held(it, download = true) }
        val levelHeld = toPrepare.associate { it.id to held(it, download = false) }
        val schedule = PreparationTime.schedule(
            // Each download is followed by its song's quick level on the same worker.
            toDownload.filter { downloadHeld[it.id] == null }.map { item ->
                PreparationTime.Job(item.id, (progress(item, download)?.let { time.downloadLeft(it.second) } ?: time.downloadMs) +
                    PreparationTime.QUICK_MS)
            },
            // A level can be made once its song is downloaded or on its way.
            toPrepare.filter { levelHeld[it.id] == null && (inputs.downloaded(it.song) || downloadHeld[it.id] == null) }.map { item ->
                PreparationTime.Job(item.id, progress(item, level)?.let { time.chartLeft(item.song, it.second) } ?: time.chartMs(item.song))
            },
        )
        fun queued(item: PreparationPlan.Item, state: QueueState): Queued {
            val title = item.song.optString("title").ifBlank { "Untitled song" }
            return Queued(item, playlists[item.playlistId]?.optString("title").orEmpty(),
                SongTitle.display(title, unlocked(item.playlistId, item.id)), state, quick = inputs.ready(item.song))
        }
        val downloads = toDownload.map { item ->
            val working = progress(item, download)
            queued(item, when {
                working != null -> QueueState.Working(working.first, working.second, time.downloadLeft(working.second))
                else -> downloadHeld[item.id]?.let(QueueState::Held) ?: QueueState.InLine(schedule.downloaded[item.id] ?: 0L)
            })
        }
        val levels = toPrepare.filter { inputs.downloaded(it.song) }.map { item ->
            val working = progress(item, level)
            queued(item, when {
                working != null -> QueueState.Working(working.first, working.second, time.chartLeft(item.song, working.second))
                else -> levelHeld[item.id]?.let(QueueState::Held) ?: QueueState.InLine(schedule.prepared[item.id] ?: 0L)
            })
        }
        return Queues(downloads.sortedBy { it.state is QueueState.Held }, levels.sortedBy { it.state is QueueState.Held },
            full, schedule, inputs, online)
    }

    /** "about 3 min" style wording for a wait. */
    fun describe(ms: Long): String = if (ms < 60_000) "under a minute" else "about ${(ms + 30_000) / 60_000} min"

    /** Learns from a finished download. */
    internal fun downloaded(context: Context, ms: Long) = PreparationTime(LibraryStore(context).prefs).learnDownload(ms)

    /** Learns from a finished level of a song [seconds] long. */
    internal fun prepared(context: Context, ms: Long, seconds: Double) = PreparationTime(LibraryStore(context).prefs).learnLevel(ms, seconds)

    fun connected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
