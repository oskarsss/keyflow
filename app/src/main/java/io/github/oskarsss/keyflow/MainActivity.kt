package io.github.oskarsss.keyflow

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import io.github.oskarsss.keyflow.arcade.Arcade
import io.github.oskarsss.keyflow.arcade.ArcadeSongs
import io.github.oskarsss.keyflow.game.GameView
import io.github.oskarsss.keyflow.library.Covers
import io.github.oskarsss.keyflow.library.LibraryStore
import io.github.oskarsss.keyflow.library.OnDeviceLibrary
import io.github.oskarsss.keyflow.library.Preparation
import io.github.oskarsss.keyflow.ui.Palette
import io.github.oskarsss.keyflow.ui.Ui
import java.util.concurrent.Executors
import org.json.JSONObject

internal enum class Screen { WELCOME, IMPORT, WARMUP, HOME, PLAYLISTS, PLAYLIST, SETTINGS, QUEUE, ARCADE, LOADING, GAME, RESULTS }

/** A slice of a song played inside an arcade run. */
internal class Stage(val startMs: Int, val endMs: Int)

/**
 * A journey row whose subtitle, trailing status and [mark] (quick level, or not downloaded)
 * update while songs are prepared.
 */
internal class SongRow(val index: Int, val subtitle: TextView, val trailing: FrameLayout, val mark: ImageView)

/** What a journey node's badge says about its song; a song with its full level has none. */
internal enum class SongMark { QUICK, MISSING }

/**
 * The single activity. It owns the app's state and lifecycle; each screen is built by
 * extension functions in its own file.
 */
class MainActivity : Activity() {
    internal val handler = Handler(Looper.getMainLooper())
    internal val io = Executors.newSingleThreadExecutor()
    internal lateinit var store: LibraryStore
    internal lateinit var ui: Ui
    internal lateinit var covers: Covers
    internal var screen = Screen.HOME
    internal var scroll: ScrollView? = null
    internal var pendingUrl = ""
    internal var operation = 0
    internal var activeImport: String? = null
    internal var importReview: AlertDialog? = null
    internal var foreground = true
    internal var polling = false
    internal var player: MediaPlayer? = null
    internal var game: GameView? = null
        set(value) {
            field = value
            Preparation.playing = value != null && foreground
        }
    internal var pauseOverlay: View? = null
    internal var currentSong: JSONObject? = null
    internal var currentIndex = 0
    internal var run: Arcade.Run? = null
        set(value) {
            field = value
            Preparation.arcadePlaying = value != null
        }
    internal val random = kotlin.random.Random(System.nanoTime())
    internal val arcadeSongs by lazy { ArcadeSongs(this) }
    internal val maintenance = Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "song-cleanup")
    }
    internal var protectedSongId: String? = null
    /** Shown on the next results screen when that run moved the timing offset. */
    internal var timingNote: String? = null
    /** Where Back leads from a playlist's settings: the playlists list or Settings. */
    internal var playlistSettingsFrom = Screen.PLAYLISTS
    internal val cleanupScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    // The journey rows on the home screen, refreshed while songs are prepared.
    internal val rows = HashMap<String, SongRow>()
    // What the journey rows show, so polling can skip unchanged playlists and rows.
    internal var shownRevision = -1L
    internal var shownSongs: List<String> = emptyList()
    // Every level the journey was built for; only the ones near the screen have rows.
    internal var journeyIds: List<String> = emptyList()
    /** The next level's button and the line saying when it will be ready, while it isn't. */
    internal var hero: Pair<View, TextView>? = null
    internal var heroCheckedAt = 0L
    /** The next level's quick-level badge and its song's id. */
    internal var heroQuick: Pair<String, View>? = null
    // Songs that aren't downloaded, or play with a quick level while their full level is made;
    // polling keeps it current.
    internal var songMarks: Map<String, SongMark> = emptyMap()
    internal var polls = 0

    internal val library: OnDeviceLibrary
        get() = OnDeviceLibrary.get(this)

    internal val isOriginals
        get() = store.playlist?.optString("id") == LibraryStore.ORIGINALS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LibraryStore(this)
        ui = Ui(this)
        covers = Covers(store)
        Preparation.watch(this)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        if (intent.action == Intent.ACTION_SEND) {
            pendingUrl = extractPlaylist(intent.getStringExtra(Intent.EXTRA_TEXT))
            showAddPlaylist()
        } else if (store.playlist == null) showAddPlaylist() else showHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) {
            stopGame()
            cancelImport()
            operation++
            pendingUrl = extractPlaylist(intent.getStringExtra(Intent.EXTRA_TEXT))
            showAddPlaylist()
        }
    }

    internal fun extractPlaylist(value: String?): String {
        val text = value?.trim() ?: return ""
        return Regex("https://(?:music\\.|www\\.)?youtube\\.com/[^\\s]+").find(text)?.value ?: text
    }

    internal fun dp(value: Int) = ui.dp(value)

    // ---------------------------------------------------------------- lifecycle

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.GAME -> if (pauseOverlay != null) hidePause() else showPause()
            Screen.HOME -> @Suppress("DEPRECATION") super.onBackPressed()
            Screen.PLAYLIST -> leavePlaylistSettings()
            Screen.QUEUE -> showSettings()
            Screen.IMPORT -> {
                operation++
                cancelImport()
                showAddPlaylist()
            }
            else ->
                if (store.playlist == null) @Suppress("DEPRECATION") super.onBackPressed()
                else {
                    operation++
                    stopGame()
                    showHome()
                }
        }
    }

    override fun onPause() {
        super.onPause()
        foreground = false
        Preparation.playing = false
        if (game != null) showPause()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        Preparation.playing = game != null
        scheduleCleanup()
        Preparation.wake(this)
        if (game != null) showPause() else if (screen == Screen.HOME) startPolling()
    }

    override fun onDestroy() {
        operation++
        cancelImport()
        stopGame()
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        maintenance.shutdownNow()
        covers.shutdown()
        super.onDestroy()
    }

    internal fun showError(error: Throwable) {
        if (isDestroyed) return
        val message =
            if (
                error is java.net.ConnectException ||
                    error is java.net.SocketTimeoutException ||
                    error is java.net.UnknownHostException
            )
                "Couldn't connect. Check your internet connection and try again."
            else error.message ?: "Something went wrong. Please try again."
        AlertDialog.Builder(this)
            .setTitle("Something went wrong")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

}
