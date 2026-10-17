package com.keyflow.pianotiles.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.keyflow.pianotiles.MainActivity
import com.keyflow.pianotiles.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one worker that prepares songs, one at a time, in a foreground service that survives
 * leaving the game or locking the phone. It asks [Preparation] for the next song each time, and
 * stops when nothing wanted is missing.
 */
class DownloadService : Service() {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "song-preparation")
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val running = AtomicBoolean()
    private val again = AtomicBoolean()
    private var lastStart = 0
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
        startForeground(NOTIFICATION, notification())
        lastStart = startId
        again.set(true)
        if (running.compareAndSet(false, true)) worker.execute(::drain)
        return START_NOT_STICKY
    }

    /** Prepares wanted songs until none is missing; a start during the last check runs another pass. */
    private fun drain() {
        // Songs that failed in this pass without being marked, so they can't repeat endlessly.
        val skip = HashSet<String>()
        while (again.getAndSet(false)) {
            var failures = 0
            // Two failures in a row usually mean the connection, not the songs, so the rest wait.
            while (failures < 2) {
                val item = runCatching { Preparation.next(this, skip) }.getOrNull() ?: break
                if (prepare(item)) failures = 0 else { skip.add(item.id); failures++ }
            }
        }
        running.set(false)
        if (again.get() && running.compareAndSet(false, true)) return drain()
        main.post {
            if (!running.get()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(lastStart)
            }
        }
    }

    /** Returns false when the song failed; a song that gave way to another counts as fine. */
    private fun prepare(item: PreparationPlan.Item): Boolean {
        val songId = item.id
        val started = System.currentTimeMillis()
        val current = Preparation.Current(songId, started)
        android.util.Log.i("Keyflow", "Preparing $songId from ${item.playlistId}: ${item.reason}")
        Preparation.current = current
        var shown = 0L
        var checked = started
        // Give way when the player waits for a different song, or a playlist's next two levels need
        // this worker; the plan is read at most every few seconds.
        fun shouldYield(): Boolean {
            if (Preparation.waiting.let { it != null && it.second != songId }) return true
            val now = System.currentTimeMillis()
            if (item.reason == PreparationPlan.Reason.WAITING || now - checked < YIELD_CHECK_MS) return false
            checked = now
            return PreparationPlan.outranks(runCatching { Preparation.next(this) }.getOrNull(), item)
        }
        return try {
            library.cacheSong(item.playlistId, songId, shouldYield = ::shouldYield) { message, fraction ->
                current.fraction = fraction
                val now = System.currentTimeMillis()
                if (now - shown > 1000) {
                    shown = now
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message, fraction))
                }
            }
            if (LibraryStore(this).offline(item.song)) Preparation.finished(this, System.currentTimeMillis() - started)
            Preparation.release(songId)
            true
        } catch (_: OnDeviceLibrary.Yielded) {
            true
        } catch (error: Throwable) {
            android.util.Log.w("Keyflow", "Song preparation failed", error)
            Preparation.release(songId)
            false
        } finally {
            Preparation.current = null
        }
    }

    private fun notification(message: String? = null, fraction: Double = 0.0): Notification {
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
            .apply { if (message != null) setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false) }
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 15
        private const val YIELD_CHECK_MS = 5_000L
    }
}
