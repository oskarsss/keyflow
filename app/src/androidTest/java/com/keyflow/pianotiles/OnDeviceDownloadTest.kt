package com.keyflow.pianotiles

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceDownloadTest {
    @Test
    fun bundledEngineImportsAndDownloadsPlaylistWithoutCompanion() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("playlistUrl")
        assumeTrue("Optional live network test", !url.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = OnDeviceLibrary.get(context)
        val playlist =
            library.importPlaylist(url!!, "device-import") {
                android.util.Log.i("NativeDownloadTest", it.take(180))
            }
        assertTrue(playlist.getJSONArray("songs").length() > 0)
        LibraryStore(context).save(playlist)
        val first = playlist.getJSONArray("songs").getJSONObject(0)
        library.cacheSong(playlist.getString("id"), first.getString("id")) { message, _ ->
            android.util.Log.i("NativeDownloadTest", message.take(180))
        }
        val store = LibraryStore(context)
        assertTrue(store.offline(first))
        assertTrue(store.songFile(first, "jpg").exists())
        val chart = store.chart(first)
        assertTrue(chart.getJSONArray("notes").length() > 20)
        assertTrue(chart.getDouble("duration") > 10)
        val state = library.snapshot(playlist.getString("id"))!!
        assertEquals("ready", state.getJSONArray("songs").getJSONObject(0).getString("status"))
        val id = playlist.getString("id")
        // Exercise the real foreground queue and its per-playlist limit after leaving the UI.
        val second = playlist.getJSONArray("songs").getJSONObject(1)
        val third = playlist.getJSONArray("songs").getJSONObject(2)
        store.songFile(second, "mp3").delete()
        store.songFile(third, "mp3").delete()
        store.prefs.edit().putInt("preload.$id", 0).apply()
        library.setPreload(id, 0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                store.prefs.edit().putInt("preload.$id", 2).apply()
                library.setPreload(id, 2)
                DownloadService.start(it, id)
                it.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            }
            val deadline = SystemClock.elapsedRealtime() + 120000
            while (!store.offline(second)) {
                check(SystemClock.elapsedRealtime() < deadline) { "Background song download timed out" }
                val current = library.snapshot(id)!!.getJSONArray("songs").getJSONObject(1)
                check(current.optString("status") != "error") { current.optString("error") }
                Thread.sleep(250)
            }
            assertTrue(store.songFile(second, "jpg").isFile)
            assertFalse("The queue must stop at the requested two songs", store.offline(third))
            assertEquals("ready", library.snapshot(id)!!.getJSONArray("songs").getJSONObject(1).getString("status"))
        }
        store.prefs.edit().putInt("preload.$id", 1).apply()
        val saved = library.setPreload(id, 1)
        assertEquals(1, saved.getInt("preloadCount"))
    }
}
