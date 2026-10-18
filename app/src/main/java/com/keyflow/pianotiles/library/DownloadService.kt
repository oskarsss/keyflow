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
import org.json.JSONObject

/**
 * The two workers that prepare songs, in a foreground service that survives leaving the game or
 * locking the phone. One downloads songs, the other makes levels for downloaded ones, so neither
 * waits for the other: levels are made while the next songs download, even without a connection.
 * Each asks [Preparation] for its next song every time, and the service stops when both are done.
 */
class DownloadService : Service() {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastStart = 0
    private lateinit var library: OnDeviceLibrary
    private val downloads = Lane("song-download", Preparation::nextDownload, ::download, online = true)
    private val levels = Lane("song-level", Preparation::nextLevel, ::makeLevel, online = false)
    @Volatile private var downloadText: String? = null
    @Volatile private var downloadFraction = 0.0
    @Volatile private var levelText: String? = null
    @Volatile private var levelFraction = 0.0
    private var shown = 0L

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
        downloads.start()
        levels.start()
        return START_NOT_STICKY
    }

    /**
     * One worker and its queue. It works until [next] has nothing more; a start during its last
     * check runs another pass. A worker that needs to be [online] stops after two lost connections.
     */
    private inner class Lane(
        name: String,
        private val next: (android.content.Context, Set<String>) -> PreparationPlan.Item?,
        private val work: (PreparationPlan.Item) -> Throwable?,
        private val online: Boolean,
    ) {
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, name)
        }
        val running = AtomicBoolean()
        private val again = AtomicBoolean()

        fun start() {
            again.set(true)
            if (running.compareAndSet(false, true)) worker.execute(::drain)
        }

        private fun drain() {
            // Songs that failed in this pass without being marked, so they can't repeat endlessly.
            val skip = HashSet<String>()
            while (again.getAndSet(false)) {
                var failures = 0
                // Two lost connections in a row would fail every download, so the rest wait for a
                // connection. A song that fails on its own is marked and passed over.
                while (failures < 2) {
                    val item = runCatching { next(this@DownloadService, skip) }.getOrNull() ?: break
                    when (val error = work(item)) {
                        null -> failures = 0
                        else -> {
                            skip.add(item.id)
                            if (online && library.connectionLost(error)) failures++ else failures = 0
                        }
                    }
                }
            }
            running.set(false)
            if (again.get() && running.compareAndSet(false, true)) return drain()
            main.post(::stopWhenDone)
        }

        fun shutdown() = worker.shutdown()
    }

    private fun stopWhenDone() {
        if (downloads.running.get() || levels.running.get()) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStart)
    }

    /** Returns why the download failed, or null. A finished download wakes the other worker. */
    private fun download(item: PreparationPlan.Item): Throwable? {
        val songId = item.id
        android.util.Log.i("Keyflow", "Downloading $songId from ${item.playlistId}: ${item.reason}")
        Preparation.downloading = Preparation.Current(item.song)
        return try {
            library.download(item.playlistId, songId) { message, fraction ->
                downloadText = message
                downloadFraction = fraction
                show()
            }
                ?.let { Preparation.downloaded(this, it) }
            levels.start()
            null
        } catch (error: Throwable) {
            android.util.Log.w("Keyflow", "Song download failed", error)
            Preparation.release(songId)
            error
        } finally {
            Preparation.downloading = null
            downloadText = null
        }
    }

    /** Returns why making the level failed, or null; a level that gave way to another counts as fine. */
    private fun makeLevel(item: PreparationPlan.Item): Throwable? {
        val songId = item.id
        android.util.Log.i("Keyflow", "Making the level of $songId from ${item.playlistId}: ${item.reason}")
        Preparation.making = Preparation.Current(item.song)
        var checked = System.currentTimeMillis()
        var stepStarted = SystemClock.elapsedRealtime()
        // Rests aren't part of how long the level takes, which the wait estimates learn from.
        var rested = 0L
        // A song wanted for one reason can become more urgent while its level is made.
        var reason = item.reason
        val store = LibraryStore(this)
        // Between steps of the analysis: rest while a game is played, then give way when the player
        // waits for a different song that is downloaded, a playlist's next two levels need this
        // worker, or Download ahead and Download all must wait for a charger. The plan is read at
        // most every few seconds.
        fun shouldYield(): Boolean {
            if (Preparation.waiting?.second == songId) reason = PreparationPlan.Reason.WAITING
            val resting = SystemClock.elapsedRealtime()
            rest(reason, resting - stepStarted)
            stepStarted = SystemClock.elapsedRealtime()
            rested += stepStarted - resting
            val waiting = Preparation.waiting
            if (waiting != null && waiting.second != songId && store.downloaded(JSONObject().put("id", waiting.second))) return true
            val now = System.currentTimeMillis()
            if (reason == PreparationPlan.Reason.WAITING || now - checked < YIELD_CHECK_MS) return false
            checked = now
            if (PreparationPlan.big(reason) && Preparation.bigLevelsWaitFor(this) != null) return true
            val next = runCatching { Preparation.nextLevel(this) }.getOrNull()
            if (next?.id == songId) reason = next.reason
            return PreparationPlan.outranks(next, PreparationPlan.Item(item.playlistId, item.song, reason))
        }
        return try {
            library.prepare(item.playlistId, songId, shouldYield = ::shouldYield) { message, fraction ->
                levelText = message
                levelFraction = fraction
                show()
            }?.let { Preparation.prepared(this, it - rested, PreparationTime.seconds(item.song)) }
            Preparation.release(songId)
            null
        } catch (_: OnDeviceLibrary.Yielded) {
            null
        } catch (error: Throwable) {
            android.util.Log.w("Keyflow", "Making a level failed", error)
            Preparation.release(songId)
            error
        } finally {
            Preparation.making = null
            levelText = null
        }
    }

    /** Shows both workers' progress, at most once a second. */
    private fun show() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - shown < 1000) return
            shown = now
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
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

    private fun notification(): Notification {
        val message = levelText ?: downloadText
        val fraction = if (levelText != null) levelFraction else downloadFraction
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
                // Making a level shows its bar; the download running beside it is named below.
                if (levelText != null) downloadText?.let(::setSubText)
                if (message != null) setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
            }
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        downloads.shutdown()
        levels.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 15
        private const val YIELD_CHECK_MS = 5_000L
        private const val MAX_REST_MS = 10_000L
    }
}
