package com.keyflow.pianotiles.library

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.keyflow.pianotiles.MainActivity
import com.keyflow.pianotiles.game.GameSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaylistLibraryTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    private fun choose(activity: MainActivity, id: String) {
        views(activity.window.decorView).first { it.contentDescription == "Switch playlist" }
            .performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val title = OnDeviceLibrary.get(context).snapshot(id)!!.getString("title")
        views(activity.window.decorView).first { it.contentDescription == "Open playlist $title" }
            .performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun savedPlaylistsSupportSwitchingRestartAndLockedTitleMasking() {
        val prefs = context.getSharedPreferences("keyflow", 0)
        prefs.edit().clear().commit()
        val original = LibraryStore(context).demo()
        val first = JSONObject(original.toString()).put("id", "morning-mix")
            .put("title", "Morning mix")
        val library = OnDeviceLibrary.get(context)
        library.save(first)
        LibraryStore(context).save(first)
        prefs.edit().putBoolean("song.first-light.completed", true)
            .putInt("song.first-light.score", 9876)
            .putInt("song.first-light.stars", 3).commit()
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            assertTrue(LibraryStore(context).unlocked(1))
            controller.pause().stop()
        }
        val second = JSONObject().put("id", "evening-mix").put("title", "Evening mix")
            .put("songs", JSONArray().put(
                JSONObject().put("id", "another-song").put("title", "Another song")
                    .put("artist", "Test artist").put("demo", true),
            ))
        library.save(second)
        LibraryStore(context).save(second)
        assertEquals(0, LibraryStore(context).completedCount())
        assertTrue(library.savedPlaylists().any { it.getString("id") == "morning-mix" })

        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            choose(controller.get(), "morning-mix")
            val restored = LibraryStore(context)
            assertEquals("morning-mix", restored.playlist!!.getString("id"))
            assertEquals(1, restored.completedCount())
            assertEquals(9876, restored.score(restored.songs().getJSONObject(0)))
            assertEquals(3, restored.stars(restored.songs().getJSONObject(0)))
            assertTrue(restored.unlocked(1))
            assertFalse(restored.unlocked(2))
            val nodes = views(controller.get().window.decorView).toList()
            val titles = nodes.filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(titles.contains("1 of 3 cleared"))
            assertTrue(titles.contains("Violet Hour"))
            assertFalse(titles.contains("Afterglow"))
            assertTrue(titles.contains("After***"))
            assertTrue(nodes.any { it.contentDescription == "Blurred locked album cover for level 3" })
            assertFalse(nodes.any { it.contentDescription?.contains("Afterglow") == true })
            controller.pause().stop()
        }
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            assertEquals("morning-mix", LibraryStore(context).playlist!!.getString("id"))
            assertEquals(1, LibraryStore(context).completedCount())
            choose(controller.get(), "evening-mix")
            assertEquals(0, LibraryStore(context).completedCount())
            choose(controller.get(), "morning-mix")
            assertEquals(1, LibraryStore(context).completedCount())
            val progress = LibraryStore(context)
            val session = GameSession(listOf(GameSession.Note(1.0, 0)))
            session.tap(0, 1.0)
            progress.record(progress.songs().getJSONObject(1), session)
            choose(controller.get(), "morning-mix")
            assertTrue(LibraryStore(context).unlocked(2))
            assertTrue(views(controller.get().window.decorView).filterIsInstance<TextView>()
                .any { it.text.toString() == "Afterglow" })
            controller.pause().stop()
        }
    }
}
