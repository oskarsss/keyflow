package com.keyflow.pianotiles

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** A visible on-device queue survives leaving the game or locking the phone. */
class DownloadService : Service() {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "song-preparation")
    }
    private val planner = Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "preparation-requests")
    }
    private val planning = AtomicInteger()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val queued = mutableMapOf<String, Boolean>()
    private val ranks = mutableMapOf<String, Int>()

    /** Queue order; a song the player is waiting for jumps to the front. */
    private val order = ArrayDeque<String>()
    private val pending = AtomicInteger()
    private lateinit var library: OnDeviceLibrary
    private lateinit var store: LibraryStore

    override fun onCreate() {
        super.onCreate()
        library = OnDeviceLibrary.get(this)
        store = LibraryStore(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Song downloads", NotificationManager.IMPORTANCE_LOW),
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification())
        planning.incrementAndGet()
        planner.execute {
            try { runCatching { enqueue(intent) }.onFailure {
                android.util.Log.w("Keyflow", "Song preparation request failed", it)
            } } finally {
                planning.decrementAndGet()
                main.post(::stopIfIdle)
            }
        }
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (planning.get() == 0 && pending.get() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun enqueue(intent: Intent?) {
        val id = intent?.getStringExtra("playlistId")
        val playlist = id?.let(library::snapshot)
        if (playlist == null) return
        val selected = intent.getStringExtra("songId")
        val rank = when {
            selected == null -> 0
            intent.getBooleanExtra("background", false) -> 1
            else -> 2
        }
        val all = intent.getBooleanExtra("all", false)
        val songs = playlist.getJSONArray("songs")
        val byId = (0 until songs.length()).map { songs.getJSONObject(it) }.associateBy { it.getString("id") }
        val fromNext = (0 until songs.length()).map { songs.getJSONObject(it) }.let { order ->
            val from = order.indexOfFirst { !store.completed(it) }.coerceAtLeast(0)
            order.drop(from) + order.take(from)
        }
        val ids =
            when {
                selected != null -> listOf(selected)
                // The whole playlist, starting from the next level to play.
                all -> fromNext.map { it.getString("id") }
                // The next levels, then every downloaded song whose chart is from an older generator.
                else -> (store.upcoming(playlist) + fromNext.filter(::rechart)).map { it.getString("id") }.distinct()
            }
        val requestedNow = selected != null || all
        val online = ArcadePreparation.connected(this)
        for (songId in ids) {
            val song = byId[songId] ?: continue
            if (store.offline(song)) continue
            if (!requestedNow && !online && !rechart(song)) continue
            val generation = library.cacheGeneration
            val key = "$generation:$id:$songId"
            val added = synchronized(queued) {
                if (queued.containsKey(key)) {
                    if (requestedNow) queued[key] = true
                    if (rank > (ranks[key] ?: 0)) {
                        ranks[key] = rank
                        if (order.remove(key)) insert(key)
                    }
                    false
                } else {
                    queued[key] = requestedNow
                    ranks[key] = rank
                    insert(key)
                    true
                }
            }
            if (!added) continue
            pending.incrementAndGet()
            executor.execute(::work)
        }
    }

    /** Charts for audio already on the phone need no connection, only a newer chart. */
    private fun rechart(song: org.json.JSONObject) = store.songFile(song, "mp3").isFile && !store.offline(song)

    private fun insert(key: String) {
        val rank = ranks[key] ?: 0
        val before = order.indexOfFirst { (ranks[it] ?: 0) < rank }
        if (before < 0) order.addLast(key) else order.add(before, key)
    }

    /** Prepares the song at the front of the queue; there is one call per queued song. */
    private fun work() {
        val next = synchronized(queued) { order.removeFirstOrNull() }
        var requeued = false
        if (next != null) runCatching {
            val (generation, playlistId, songId) = next.split(":", limit = 3)
            val latest = library.snapshot(playlistId)
            val song = latest?.getJSONArray("songs")?.let { songs ->
                (0 until songs.length()).map { songs.getJSONObject(it) }.firstOrNull { it.getString("id") == songId }
            }
            val (requested, rank) = synchronized(queued) { (queued[next] == true) to (ranks[next] ?: 0) }
            if (requested || latest != null && song != null &&
                (store.upcoming(latest).any { it.getString("id") == songId } || rechart(song))) {
                var shown = 0L
                library.cacheSong(
                    playlistId, songId, generation.toLong(),
                    // Give way when the player is waiting for a different song.
                    shouldYield = { rank < WAITING && synchronized(queued) { order.firstOrNull()?.let { ranks[it] } == WAITING } },
                ) { message, fraction ->
                    val now = System.currentTimeMillis()
                    if (now - shown > 1000) {
                        shown = now
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION, notification(message, fraction, pending.get() - 1))
                    }
                }
            }
        }.onFailure { error ->
            if (error is OnDeviceLibrary.Yielded) synchronized(queued) {
                insert(next!!)
                requeued = true
            }
        }
        if (requeued) {
            executor.execute(::work)
            return
        }
        synchronized(queued) { queued.remove(next); ranks.remove(next) }
        pending.decrementAndGet()
        main.post(::stopIfIdle)
    }

    private fun notification(message: String? = null, fraction: Double = 0.0, more: Int = 0): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Keyflow · Preparing music")
            .setContentText(message ?: "Getting your next songs ready")
            .apply {
                if (message != null) setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
                if (more > 0) setSubText("$more more queued")
            }
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        planner.shutdown()
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 15
        /** Queue rank of a song the player opened and is waiting for. */
        private const val WAITING = 2

        fun start(context: Context, playlistId: String, songId: String? = null, all: Boolean = false, background: Boolean = false) {
            context.startForegroundService(
                Intent(context, DownloadService::class.java)
                    .putExtra("playlistId", playlistId)
                    .putExtra("all", all)
                    .putExtra("background", background)
                    .apply { if (songId != null) putExtra("songId", songId) },
            )
        }
    }
}
