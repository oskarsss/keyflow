package com.keyflow.pianotiles

import android.app.Activity
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    private fun screenshot(name: String) {
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
        store.prefs
            .edit()
            .putFloat("speed", 1f)
            .putInt("timingOffset", 0)
            .remove("song.first-light.completed")
            .remove("song.first-light.stars")
            .remove("song.first-light.score")
            .apply()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity = it }
            waitFor {
                onMain {
                    views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "Play level  →"
                    }
                }
            }
            screenshot("demo-library")
            clickText("Play level  →")
            var game: GameView? = null
            waitFor {
                game = onMain {
                    views(activity.window.decorView).filterIsInstance<GameView>().firstOrNull()
                }
                game != null
            }
            val view = game!!
            val player =
                GameView::class
                    .java
                    .getDeclaredField("player")
                    .apply { isAccessible = true }
                    .get(view) as MediaPlayer
            val density = context.resources.displayMetrics.density
            for ((index, note) in view.session.notes.withIndex()) {
                waitFor {
                    onMain {
                        if (
                            player.isPlaying && player.currentPosition / 1000.0 >= note.time - .040
                        ) {
                            dispatchLane(view, note.lane)
                            true
                        } else false
                    }
                }
                assertEquals(
                    "Note $index: expected touchscreen hit at ${note.time}s; misses=${view.session.misses}",
                    index + 1,
                    view.session.hits,
                )
                if (index == 12) {
                    val position = onMain { IntArray(2).also { view.getLocationOnScreen(it) } }
                    // Exercise the visible pause and resume controls mid-song.
                    tap(position[0] + view.width - 24 * density, position[1] + 30 * density)
                    waitFor { onMain { !player.isPlaying } }
                    screenshot("demo-gameplay")
                    val pausedTime = onMain { player.currentPosition }
                    Thread.sleep(250)
                    assertEquals(pausedTime, onMain { player.currentPosition })
                    // Dialog is a separate window; read its visible button coordinates.
                    val dialog =
                        MainActivity::class
                            .java
                            .getDeclaredField("pauseDialog")
                            .apply { isAccessible = true }
                            .get(activity) as android.app.AlertDialog
                    val resume = onMain {
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    }
                    val xy = onMain { IntArray(2).also { resume.getLocationOnScreen(it) } }
                    tap(xy[0] + resume.width / 2f, xy[1] + resume.height / 2f)
                }
            }
            waitFor(6000) {
                onMain {
                    views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "LEVEL COMPLETE"
                    }
                }
            }
            screenshot("demo-results")
            assertTrue(view.session.passed())
            assertEquals(48, view.session.hits)
            assertEquals(0, view.session.misses)
            assertEquals(3, view.session.stars())
            assertTrue(store.completed(store.songs().getJSONObject(0)))
            assertTrue(store.unlocked(1))
            clickText("Back to my journey")
            waitFor {
                onMain {
                    views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "33%"
                    }
                }
            }
            scenario.recreate()
            scenario.onActivity { activity = it }
            waitFor {
                onMain {
                    views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "33%"
                    }
                }
            }
            screenshot("demo-progress-saved")
            // Play the newly unlocked second level through its hold and slide patterns.
            clickText("Play level  →")
            var gestureView: GameView? = null
            waitFor {
                gestureView = onMain {
                    views(activity.window.decorView).filterIsInstance<GameView>().firstOrNull()
                }
                gestureView != null
            }
            val flow = gestureView!!
            val flowPlayer =
                GameView::class
                    .java
                    .getDeclaredField("player")
                    .apply { isAccessible = true }
                    .get(flow) as MediaPlayer
            assertTrue(flow.session.notes.any { it.kind == "hold" })
            assertTrue(flow.session.notes.any { it.kind == "slide" })
            var capturedHold = false
            for ((index, note) in flow.session.notes.withIndex()) {
                waitFor {
                    onMain {
                        if (
                            flowPlayer.isPlaying &&
                                flowPlayer.currentPosition / 1000.0 >= note.time - .040
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
                            val time = flowPlayer.currentPosition / 1000.0
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
                assertEquals("Gesture $index (${note.kind})", index + 1, flow.session.hits)
            }
            waitFor(6000) {
                onMain {
                    views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "LEVEL COMPLETE"
                    }
                }
            }
            assertEquals(0, flow.session.misses)
            assertEquals(3, flow.session.stars())
            assertTrue(store.unlocked(2))
            screenshot("gesture-level-complete")
        }
    }

    @Test
    fun playCachedPlaylistTrackAndExerciseFailure() {
        val arguments = InstrumentationRegistry.getArguments()
        val token = arguments.getString("companionToken")
        val playlistId = arguments.getString("playlistId")
        assumeTrue(
            "Optional live companion test",
            !token.isNullOrBlank() && !playlistId.isNullOrBlank(),
        )
        val address = arguments.getString("companionAddress") ?: "http://10.0.2.2:8766"
        val client = CompanionClient(address, token!!)
        val store = LibraryStore(context)
        val playlist = client.request("/api/playlists/$playlistId")
        store.save(playlist)
        store.prefs
            .edit()
            .putString("server", address)
            .putString("token", token)
            .putFloat("speed", 1f)
            .putInt("timingOffset", 0)
            .apply()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity = it }
            waitFor(45000) { store.offline(store.songs().getJSONObject(0)) }
            screenshot("playlist-library")
            clickText("Play level  →")
            var current: GameView? = null
            waitFor {
                current = onMain {
                    views(activity.window.decorView).filterIsInstance<GameView>().firstOrNull()
                }
                current != null
            }
            val view = current!!
            val player =
                GameView::class
                    .java
                    .getDeclaredField("player")
                    .apply { isAccessible = true }
                    .get(view) as MediaPlayer
            for ((index, note) in view.session.notes.take(40).withIndex()) {
                waitFor {
                    onMain {
                        if (
                            player.isPlaying && player.currentPosition / 1000.0 >= note.time - .040
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
                            val time = player.currentPosition / 1000.0
                            if (time >= note.time + note.duration - .025) {
                                dispatchGesture(view, note.laneAt(time), MotionEvent.ACTION_UP)
                                true
                            } else {
                                dispatchGesture(view, note.laneAt(time), MotionEvent.ACTION_MOVE)
                                false
                            }
                        }
                    }
                assertEquals("Live note $index", index + 1, view.session.hits)
            }
            screenshot("playlist-gameplay")
            // Eight rapid wrong-lane taps exercise the actual failure screen.
            repeat(8) { tapLane(view, (view.session.notes[40].lane + 1) % 4) }
            waitFor {
                onMain {
                    views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "KEEP THE RHYTHM"
                    }
                }
            }
            assertTrue(view.session.failed())
            assertEquals(0, view.session.stars())
            screenshot("playlist-failure")
            clickText("Back to my journey")
            waitFor {
                onMain {
                    views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "YOUR MIX"
                    }
                }
            }
            screenshot("playlist-album-covers")
        }
    }
}
