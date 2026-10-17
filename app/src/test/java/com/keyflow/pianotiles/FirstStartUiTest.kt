package com.keyflow.pianotiles

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.keyflow.pianotiles.library.LibraryStore
import com.keyflow.pianotiles.library.OnDeviceLibrary
import java.io.File
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FirstStartUiTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun freshInstall() {
        LibraryStore(context).prefs.edit().clear().commit()
        File(context.filesDir, "playlists").deleteRecursively()
    }

    private fun texts(view: View): List<TextView> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else listOfNotNull(view as? TextView)

    private fun MainActivity.texts() = texts(window.decorView).map { it.text.toString() }

    private fun MainActivity.tap(label: String) {
        var view: View? = texts(window.decorView).first { it.text.toString() == label }
        while (view != null && !view.isClickable) view = view.parent as? View
        view!!.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun theFirstStartAsksForAPlaylistThenOffersTheBuiltInSongsWhileItGetsReady() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertEquals(Screen.WELCOME, activity.screen)
            assertTrue("Welcome to Keyflow" in activity.texts())
            // The built-in songs can't be chosen before a playlist is added.
            assertFalse(activity.texts().any { it.startsWith("Play the built-in") || it.startsWith("Warm up") })

            val raw = JSONObject().put("title", "Road trip").put("entries", JSONArray((0 until 5).map {
                JSONObject().put("id", "songid%05d".format(it)).put("title", "Song $it")
            }))
            val playlist = OnDeviceLibrary.get(activity).createPlaylist(raw, Random(1))
            activity.showWarmUp(playlist)
            assertEquals(Screen.WARMUP, activity.screen)
            assertTrue("Getting “Road trip” ready" in activity.texts())
            // A restart now goes home instead of back to the welcome.
            assertEquals(playlist.getString("id"), LibraryStore(context).playlist?.getString("id"))

            activity.tap("Warm up with The first notes")
            assertEquals(Screen.HOME, activity.screen)
            assertTrue(activity.isOriginals)
        }
    }
}
