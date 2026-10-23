package io.github.oskarsss.keyflow

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.oskarsss.keyflow.arcade.Arcade
import io.github.oskarsss.keyflow.chart.MusicFeatures
import io.github.oskarsss.keyflow.game.GameSession
import io.github.oskarsss.keyflow.game.GameView
import io.github.oskarsss.keyflow.library.LibraryStore
import io.github.oskarsss.keyflow.library.OnDeviceLibrary
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Plays through the real UI with touchscreen events and the real audio clock. */
@RunWith(AndroidJUnit4::class)
class GamePlayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext

    private lateinit var activity: Activity

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup)
            for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitFor(timeoutMs: Long = 10000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < deadline) { "UI condition timed out" }
            Thread.sleep(10)
        }
    }

    private fun clickText(value: String) {
        val view =
            onMain {
                views(activity.window.decorView).filterIsInstance<TextView>().firstOrNull {
                    it.text.toString() == value
                }
            } ?: error("Missing control: $value")
        onMain { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true) }
        instrumentation.waitForIdleSync()
        val coordinates = onMain {
            IntArray(2)
                .also { view.getLocationOnScreen(it) }
                .let { (it[0] + view.width / 2f) to (it[1] + view.height / 2f) }
        }
        tap(coordinates.first, coordinates.second)
    }

    private fun tap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).also {
            instrumentation.sendPointerSync(it)
            it.recycle()
        }
        MotionEvent.obtain(now, now + 8, MotionEvent.ACTION_UP, x, y, 0).also {
            instrumentation.sendPointerSync(it)
            it.recycle()
        }
    }

    private fun tapLane(view: GameView, lane: Int) {
        // Dispatch real MotionEvents on the app's UI thread. OS-level injection on
        // a shared emulator can stall beyond a rhythm game's 180 ms hit window.
        onMain { dispatchLane(view, lane) }
    }

    private fun dispatchLane(view: GameView, lane: Int) {
        val now = SystemClock.uptimeMillis()
        val x = (lane + .5f) * view.width / 4
        val y = view.height - 118 * context.resources.displayMetrics.density
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(now, now, action, x, y, 0).also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
    }

    private fun dispatchGesture(view: GameView, position: Float, action: Int) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(
                now,
                now,
                action,
                position * view.width / 4,
                view.height - 118 * context.resources.displayMetrics.density,
                0,
            )
            .also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                view.dispatchTouchEvent(it)
                it.recycle()
            }
    }

    private fun screenshot(name: String, settleMs: Long = 0) {
        if (settleMs > 0) {
            instrumentation.waitForIdleSync()
            Thread.sleep(settleMs)
        }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val folder = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun completeDemoWithTouchInputAndUnlockNextSong() {
        val store = LibraryStore(context)
        store.save(store.demo())
        val reset = store.prefs.edit().putFloat("speed", 1f).putInt("timingOffset", 0)
        // Every built-in level starts locked again, so repeated runs see the same progress.
        for (id in listOf("first-light", "violet-hour", "afterglow"))
            for (key in listOf("completed", "stars", "score")) reset.remove("song.$id.$key")
        reset.apply()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity = it }
            waitFor {
                onMain {
                    views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "Play"
                    }
                }
            }
            screenshot("demo-library")
            clickText("Play")
            var game: GameView? = null
            waitFor {
                game = onMain {
                    views(activity.window.decorView).filterIsInstance<GameView>().firstOrNull()
                }
                game != null
            }
            val view = game!!
            val player = view.clock
            val density = context.resources.displayMetrics.density
            for ((index, note) in view.session.notes.withIndex()) {
                if (note.kind == GameSession.AVOID) continue
                waitFor {
                    onMain {
                        if (
                            player.isPlaying && player.positionMs / 1000.0 >= note.time - .040
                        ) {
                            dispatchLane(view, note.lane)
                            true
                        } else false
                    }
                }
                assertEquals(
                    "Note $index: expected touchscreen hit at ${note.time}s; now=${player.positionMs / 1000.0}, judgement=${note.judgement}, misses=${view.session.misses}",
                    view.session.notes.take(index + 1).count { it.kind != GameSession.AVOID },
                    view.session.hits,
                )
                if (index == 12) {
                    val position = onMain { IntArray(2).also { view.getLocationOnScreen(it) } }
                    // Exercise the visible pause and resume controls mid-song.
                    tap(position[0] + view.width - 24 * density, position[1] + 30 * density)
                    waitFor { onMain { !player.isPlaying } }
                    screenshot("demo-gameplay")
                    val pausedTime = onMain { player.positionMs }
                    Thread.sleep(250)
                    assertEquals(pausedTime, onMain { player.positionMs })
                    // The pause menu is an in-game overlay; resuming runs a short countdown.
                    clickText("Resume")
                }
            }
            waitFor(6000) {
                onMain {
                    views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "Level cleared"
                    }
                }
            }
            screenshot("demo-results", 800)
            assertTrue(view.session.passed())
            assertEquals(48, view.session.hits)
            assertTrue(view.session.notes.any { it.kind == GameSession.GOLD })
            assertEquals(0, view.session.misses)
            assertEquals(3, view.session.stars())
            assertTrue(store.completed(store.songs().getJSONObject(0)))
            assertTrue(store.unlocked(1))
            clickText("Levels")
            waitFor {
                onMain {
                    views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "1 of 3 cleared"
                    }
                }
            }
            scenario.recreate()
            scenario.onActivity { activity = it }
            waitFor {
                onMain {
                    views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "1 of 3 cleared"
                    }
                }
            }
            screenshot("demo-progress-saved")
            // Play the newly unlocked second level through its hold and slide patterns.
            clickText("Play")
            var gestureView: GameView? = null
            waitFor {
                gestureView = onMain {
                    views(activity.window.decorView).filterIsInstance<GameView>().firstOrNull()
                }
                gestureView != null
            }
            val flow = gestureView!!
            val flowPlayer = flow.clock
            assertTrue(flow.session.notes.any { it.kind == "hold" })
            assertTrue(flow.session.notes.any { it.kind == "slide" })
            var capturedHold = false
            for ((index, note) in flow.session.notes.withIndex()) {
                if (note.kind == GameSession.AVOID) continue
                waitFor {
                    onMain {
                        if (
                            flowPlayer.isPlaying &&
                                flowPlayer.positionMs / 1000.0 >= note.time - .040
                        ) {
                            if (note.kind == "tap") dispatchLane(flow, note.lane)
                            else dispatchGesture(flow, note.lane + .5f, MotionEvent.ACTION_DOWN)
                            true
                        } else false
                    }
                }
                if (note.kind != "tap") {
                    if (note.kind == "hold" && !capturedHold) {
                        screenshot("hold-gesture")
                        capturedHold = true
                    }
                    waitFor {
                        onMain {
                            val time = flowPlayer.positionMs / 1000.0
                            if (time >= note.time + note.duration - .025) {
                                dispatchGesture(flow, note.laneAt(time), MotionEvent.ACTION_UP)
                                true
                            } else {
                                dispatchGesture(flow, note.laneAt(time), MotionEvent.ACTION_MOVE)
                                false
                            }
                        }
                    }
                }
                assertEquals(
                    "Gesture $index (${note.kind}) at ${note.time}; now=${flowPlayer.positionMs / 1000.0}, judgement=${note.judgement}, misses=${flow.session.misses}",
                    flow.session.notes.take(index + 1).count { it.kind != GameSession.AVOID },
                    flow.session.hits,
                )
            }
            waitFor(6000) {
                onMain {
                    views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "Level cleared"
                    }
                }
            }
            assertEquals(0, flow.session.misses)
            assertEquals(3, flow.session.stars())
            assertTrue(store.unlocked(2))
            screenshot("gesture-level-complete", 800)
        }
    }

    @Test
    fun nativeAnalysisStillWorksAtBackgroundPriority() {
        val audio = File(context.cacheDir, "priority-audio-test.wav")
        try {
            context.assets.open("demo/first-light.wav").use { input -> audio.outputStream().use(input::copyTo) }
            val runtime = OnDeviceLibrary.get(context).runtime
            val features = runtime.audioSamples(audio, "priority-audio-test", MusicFeatures.RATE, MusicFeatures::readPcm)
            assertTrue(features.duration > 30)
            assertTrue(features.frames > 0)
            val version = runtime.execute(
                io.github.oskarsss.keyflow.downloader.YTDLRequest("https://www.youtube.com").addOption("--version"),
                "priority-python-test", 30,
            ).out.trim()
            assertTrue("Downloader did not start: $version", version.firstOrNull()?.isDigit() == true)
        } finally { audio.delete() }
    }

    @Test
    fun bothArcadeModesAdvanceUsingCachedMusicWithoutInternet() {
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
            .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() } }
        val wifi = shell("settings get global wifi_on") == "1"
        val data = shell("settings get global mobile_data") == "1"
        val root = File(context.filesDir, "playlists")
        val backup = File(context.cacheDir, "arcade-index-backup-${System.nanoTime()}")
        val store = LibraryStore(context)
        val previousSelection = store.prefs.getString("playlistId", null)
        val library = OnDeviceLibrary.get(context)
        if (root.exists()) check(root.renameTo(backup))
        root.mkdirs()
        val song = org.json.JSONObject().put("id", "testArc0001").put("title", "Arcade offline test")
            .put("artist", "Keyflow").put("duration", 8).put("status", "ready")
        try {
            shell("svc wifi disable")
            shell("svc data disable")
            // MediaPlayer sniffs the WAV container; the cached-song path is intentionally .mp3.
            context.assets.open("demo/first-light.wav").use { input ->
                store.audioFile(song).outputStream().use(input::copyTo)
            }
            val notes = org.json.JSONArray((0..3).map {
                org.json.JSONObject().put("time", 1.5 + it * .5).put("lane", it)
            })
            store.chartFile(song).writeText(org.json.JSONObject().put("duration", 8).put("notes", notes).toString())
            val playlist = org.json.JSONObject().put("id", "offline-arcade-test").put("title", "Offline arcade test")
                .put("songs", org.json.JSONArray().put(song))
            library.save(playlist)
            store.save(playlist)
            for (mode in Arcade.Mode.values()) {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity = it }
                    waitFor { onMain { views(activity.window.decorView).any { it.contentDescription == mode.title } } }
                    onMain { views(activity.window.decorView).first { it.contentDescription == mode.title }.performClick() }
                    clickText("Start")
                    var prior: GameView? = null
                    repeat(2) { stage ->
                        var current: GameView? = null
                        waitFor(12_000) {
                            current = onMain { views(activity.window.decorView).filterIsInstance<GameView>().firstOrNull() }
                            current != null && current !== prior
                        }
                        val view = current!!
                        assertEquals(4, view.session.notes.map { it.time }.distinct().size)
                        assertTrue(view.session.notes.all { it.time in 1.5..3.0 })
                        for (note in view.session.notes) {
                            if (note.kind == GameSession.AVOID) continue
                            waitFor { onMain {
                                if (view.clock.isPlaying && view.clock.positionMs / 1000.0 >= note.time - .04) {
                                    dispatchLane(view, note.lane)
                                    true
                                } else false
                            } }
                        }
                        assertEquals(0, view.session.misses)
                        assertEquals(view.session.scored, view.session.hits)
                        prior = view
                        if (stage == 1) screenshot("offline-${mode.key}-arcade")
                    }
                    onMain { activity.onBackPressed() }
                    clickText("End run")
                }
            }
        } finally {
            if (wifi) shell("svc wifi enable")
            if (data) shell("svc data enable")
            root.deleteRecursively()
            if (backup.exists()) check(backup.renameTo(root)) else root.mkdirs()
            store.audioFile(song).delete()
            store.chartFile(song).delete()
            store.prefs.edit().putString("playlistId", previousSelection).apply()
        }
    }

    @Test
    fun playCachedPlaylistTrackAndExerciseFailure() {
        val arguments = InstrumentationRegistry.getArguments()
        val playlistUrl = arguments.getString("playlistUrl")
        assumeTrue("Optional on-device playlist test", !playlistUrl.isNullOrBlank())
        val library = OnDeviceLibrary.get(context)
        val playlist = library.importPlaylist(playlistUrl!!, "playtest-import") {}
        library.setPreload(playlist.getString("id"), LibraryStore.MIN_PRELOAD)
        val store = LibraryStore(context)
        store.save(playlist)
        val first = playlist.getJSONArray("songs").getJSONObject(0)
        library.download(playlist.getString("id"), first.getString("id")) { _, _ -> }
        library.prepare(playlist.getString("id"), first.getString("id")) { _, _ -> }
        store.prefs.edit().putFloat("speed", 1f).putInt("timingOffset", 0).apply()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity = it }
            waitFor(60000) { store.offline(store.songs().getJSONObject(0)) }
            screenshot("playlist-library")
            clickText("Play")
            var current: GameView? = null
            waitFor {
                current = onMain {
                    views(activity.window.decorView).filterIsInstance<GameView>().firstOrNull()
                }
                current != null
            }
            val view = current!!
            val player = view.clock
            for ((index, note) in view.session.notes.take(40).withIndex()) {
                if (note.kind == GameSession.AVOID) continue
                waitFor {
                    onMain {
                        if (
                            player.isPlaying && player.positionMs / 1000.0 >= note.time - .040
                        ) {
                            if (note.kind == "tap") dispatchLane(view, note.lane)
                            else dispatchGesture(view, note.lane + .5f, MotionEvent.ACTION_DOWN)
                            true
                        } else false
                    }
                }
                if (note.kind != "tap")
                    waitFor {
                        onMain {
                            val time = player.positionMs / 1000.0
                            if (time >= note.time + note.duration - .025) {
                                dispatchGesture(view, note.laneAt(time), MotionEvent.ACTION_UP)
                                true
                            } else {
                                dispatchGesture(view, note.laneAt(time), MotionEvent.ACTION_MOVE)
                                false
                            }
                        }
                    }
                assertEquals(
                    "Live note $index",
                    view.session.notes.take(index + 1).count { it.kind != GameSession.AVOID },
                    view.session.hits,
                )
            }
            screenshot("playlist-gameplay")
            // Eight rapid wrong-lane taps exercise the actual failure screen.
            repeat(8) { tapLane(view, (view.session.notes[40].lane + 1) % 4) }
            waitFor {
                onMain {
                    views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "Out of lives"
                    }
                }
            }
            assertTrue(view.session.failed())
            assertEquals(0, view.session.stars())
            screenshot("playlist-failure", 800)
            clickText("Levels")
            waitFor {
                onMain {
                    views(activity.window.decorView).any {
                        it.contentDescription == "Switch playlist"
                    }
                }
            }
            screenshot("playlist-album-covers")
        }
    }
}
