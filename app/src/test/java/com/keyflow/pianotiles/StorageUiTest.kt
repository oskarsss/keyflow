package com.keyflow.pianotiles

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.keyflow.pianotiles.library.LibraryStore
import com.keyflow.pianotiles.library.OnDeviceLibrary
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
class StorageUiTest {
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    private fun texts(activity: MainActivity) = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun noStorageDetails(activity: MainActivity) {
        val details = texts(activity).filter { Regex("download|offline|cached", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        assertTrue("Storage details leaked into the play flow: $details", details.isEmpty())
    }

    @Test
    fun homeArcadeAndPlaylistScreensHideStorageWhileSettingsExposeItsControls() {
        val store = LibraryStore(RuntimeEnvironment.getApplication())
        store.prefs.edit().clear().commit()
        store.save(store.demo())
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            noStorageDetails(activity)
            views(activity.window.decorView).first { it.contentDescription == "Endless" }.performClick()
            noStorageDetails(activity)
            activity.onBackPressed()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            views(activity.window.decorView).first { it.contentDescription == "Switch playlist" }.performClick()
            noStorageDetails(activity)
            activity.onBackPressed()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            views(activity.window.decorView).first { it.contentDescription == "Settings" }.performClick()
            assertTrue(texts(activity).contains("Smart storage management"))
            assertTrue(texts(activity).contains("Remove unused songs after"))
            assertTrue(texts(activity).contains("Make full levels"))
            assertTrue(texts(activity).contains("Clear ALL downloaded songs"))
        }
    }

    @Test
    fun settingsShowTotalsAndEachPlaylistKeepsItsOwnDownloadSettings() {
        val context = RuntimeEnvironment.getApplication()
        val store = LibraryStore(context)
        store.prefs.edit().clear().commit()
        java.io.File(context.filesDir, "playlists").deleteRecursively()
        val library = OnDeviceLibrary.get(context)
        val playlist = library.createPlaylist(
            org.json.JSONObject().put("title", "Road trip").put("entries", org.json.JSONArray((0..3).map {
                org.json.JSONObject().put("id", "roadtrip%03d".format(it)).put("title", "Song $it")
            })), kotlin.random.Random(1),
        )
        store.save(playlist)
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            views(activity.window.decorView).first { it.contentDescription == "Settings" }.performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val deadline = System.currentTimeMillis() + 5000
            while (views(activity.window.decorView).none { it.contentDescription == "Download settings for Road trip" }) {
                check(System.currentTimeMillis() < deadline) { "Playlist totals never appeared" }
                Thread.sleep(20)
                shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            assertTrue(texts(activity).any { it.startsWith("0 of 4 songs downloaded") })
            assertTrue(texts(activity).contains("0 of 4 songs · 0 B"))
            assertFalse(texts(activity).contains("Download ahead"))
            views(activity.window.decorView).first { it.contentDescription == "Download settings for Road trip" }.performClick()
            for (label in listOf("Download ahead", "Keep downloaded songs", "Download all songs", "Remove downloaded songs", "Rename", "Delete playlist"))
                assertTrue(label, texts(activity).contains(label))
            activity.onBackPressed()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(texts(activity).contains("Smart storage management"))
        }
    }
}
