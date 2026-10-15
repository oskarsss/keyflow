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

    override fun onCreate() {
        super.onCreate()
        library = OnDeviceLibrary.get(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Song downloads", NotificationManager.IMPORTANCE_LOW),
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(15, notification())
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
        val store = LibraryStore(this)
        val ids =
            when {
                selected != null -> listOf(selected)
                // The whole playlist, starting from the next level to play.
                all -> {
                    val order = (0 until songs.length()).map { songs.getJSONObject(it) }
                    val from = order.indexOfFirst { !store.completed(it) }.coerceAtLeast(0)
                    (order.drop(from) + order.take(from)).map { it.getString("id") }
                }
                else -> store.upcoming(playlist).map { it.getString("id") }
            }
        val requestedNow = selected != null || all
        for (songId in ids) {
            val song = byId[songId] ?: continue
            if (store.offline(song)) continue
            val generation = library.cacheGeneration
            val key = "$generation:$id:$songId"
            val added = synchronized(queued) {
                fun insert() {
                    val before = order.indexOfFirst { (ranks[it] ?: 0) < rank }
                    if (before < 0) order.addLast(key) else order.add(before, key)
                }
                if (queued.containsKey(key)) {
                    if (requestedNow) queued[key] = true
                    if (rank > (ranks[key] ?: 0)) {
                        ranks[key] = rank
                        if (order.remove(key)) insert()
                    }
                    false
                } else {
                    queued[key] = requestedNow
                    ranks[key] = rank
                    insert()
                    true
                }
            }
            if (!added) continue
            pending.incrementAndGet()
            executor.execute {
                val next = synchronized(queued) { order.removeFirstOrNull() }
                if (next != null) runCatching {
                    val (generation, playlistId, songId) = next.split(":", limit = 3)
                    val latest = library.snapshot(playlistId)
                    val requested = synchronized(queued) { queued[next] == true }
                    if (requested || latest != null && store.upcoming(latest).any { it.getString("id") == songId })
                        library.cacheSong(playlistId, songId, generation.toLong()) {}
                }
                synchronized(queued) { queued.remove(next); ranks.remove(next) }
                pending.decrementAndGet()
                main.post(::stopIfIdle)
            }
        }
    }

    private fun notification(): Notification {
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
            .setContentText("Getting your next songs ready")
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
