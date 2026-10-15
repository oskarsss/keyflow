package com.keyflow.pianotiles

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
            assertTrue(texts(activity).contains("Clear downloaded songs"))
        }
    }
}
