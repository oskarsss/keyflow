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
    private val executor = Executors.newSingleThreadExecutor()
    private val queued = mutableMapOf<String, Boolean>()
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
        startForeground(15, notification("Preparing your playlist…"))
        val id = intent?.getStringExtra("playlistId")
        val playlist = id?.let(library::snapshot)
        if (playlist == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val selected = intent.getStringExtra("songId")
        val songs = playlist.getJSONArray("songs")
        val store = LibraryStore(this)
        val ids =
            if (selected != null) listOf(selected)
            else store.upcoming(playlist).map { it.getString("id") }
        for (songId in ids) {
            val song =
                (0 until songs.length())
                    .map { songs.getJSONObject(it) }
                    .firstOrNull { it.getString("id") == songId } ?: continue
            if (store.offline(song)) continue
            val generation = library.cacheGeneration
            val key = "$generation:$id:$songId"
            val added = synchronized(queued) {
                if (queued.containsKey(key)) {
                    // A tap must promote an automatic request even if the preload limit changed.
                    if (selected != null) queued[key] = true
                    false
                } else {
                    queued[key] = selected != null
                    true
                }
            }
            if (!added) continue
            pending.incrementAndGet()
            executor.execute {
                runCatching {
                    val latest = library.snapshot(id)
                    val requested = synchronized(queued) { queued[key] == true }
                    if (requested || latest != null && store.upcoming(latest).any { it.getString("id") == songId })
                        library.cacheSong(id, songId, generation, ::updateNotification)
                }
                synchronized(queued) { queued.remove(key) }
                if (pending.decrementAndGet() == 0) android.os.Handler(mainLooper).post {
                    if (pending.get() == 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
        }
        if (pending.get() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun notification(message: String): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Keyflow · Saving your music")
            .setContentText(message.take(180))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(15, notification(message))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"

        fun start(context: Context, playlistId: String, songId: String? = null) {
            context.startForegroundService(
                Intent(context, DownloadService::class.java)
                    .putExtra("playlistId", playlistId)
                    .apply { if (songId != null) putExtra("songId", songId) },
            )
        }
    }
}
