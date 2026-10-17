package com.keyflow.pianotiles.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
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
            // Two lost connections in a row would fail every song, so the rest wait for a
            // connection. A song that fails on its own is marked and passed over.
            while (failures < 2) {
                val item = runCatching { Preparation.next(this, skip) }.getOrNull() ?: break
                when (val error = prepare(item)) {
                    null -> failures = 0
                    else -> {
                        skip.add(item.id)
                        if (library.connectionLost(error)) failures++ else failures = 0
                    }
                }
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

    /** Returns why the song failed, or null; a song that gave way to another counts as fine. */
    private fun prepare(item: PreparationPlan.Item): Throwable? {
        val songId = item.id
        val current = Preparation.Current(item.song)
        android.util.Log.i("Keyflow", "Preparing $songId from ${item.playlistId}: ${item.reason}")
        Preparation.current = current
        var shown = 0L
        var checked = System.currentTimeMillis()
        var stepStarted = SystemClock.elapsedRealtime()
        // A song wanted for one reason can become more urgent while it is prepared.
        var reason = item.reason
        // Between steps of the analysis: rest while a game is played, then give way when the player
        // waits for a different song, a playlist's next two levels need this worker, or Download
        // ahead and Download all must wait for Wi-Fi or a charger. The plan is read at most every
        // few seconds.
        fun shouldYield(): Boolean {
            if (Preparation.waiting?.second == songId) reason = PreparationPlan.Reason.WAITING
            rest(reason, SystemClock.elapsedRealtime() - stepStarted)
            stepStarted = SystemClock.elapsedRealtime()
            if (Preparation.waiting.let { it != null && it.second != songId }) return true
            val now = System.currentTimeMillis()
            if (reason == PreparationPlan.Reason.WAITING || now - checked < YIELD_CHECK_MS) return false
            checked = now
            if (PreparationPlan.big(reason) && !Preparation.allowsBigDownloads(this)) return true
            val next = runCatching { Preparation.next(this) }.getOrNull()
            if (next?.id == songId) reason = next.reason
            return PreparationPlan.outranks(next, PreparationPlan.Item(item.playlistId, item.song, reason))
        }
        return try {
            val timing = library.cacheSong(item.playlistId, songId, shouldYield = ::shouldYield) { message, fraction ->
                current.fraction = fraction
                val now = System.currentTimeMillis()
                if (now - shown > 1000) {
                    shown = now
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message, fraction))
                }
            }
            if (timing != null) Preparation.finished(this, timing)
            Preparation.release(songId)
            null
        } catch (_: OnDeviceLibrary.Yielded) {
            null
        } catch (error: Throwable) {
            android.util.Log.w("Keyflow", "Song preparation failed", error)
            Preparation.release(songId)
            error
        } finally {
            Preparation.current = null
        }
    }

    /**
     * While a game is on screen, rests as long as the last step of the analysis took, so the
     * game has the phone to itself half the time. A song a game or the player is waiting for
     * isn't held back.
     */
    private fun rest(reason: PreparationPlan.Reason, worked: Long) {
        if (reason == PreparationPlan.Reason.WAITING || reason == PreparationPlan.Reason.ARCADE_NEXT) return
        val until = SystemClock.elapsedRealtime() + worked.coerceAtMost(MAX_REST_MS)
        while (Preparation.playing && Preparation.waiting == null && SystemClock.elapsedRealtime() < until)
            Thread.sleep(100)
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
        private const val MAX_REST_MS = 10_000L
    }
}
