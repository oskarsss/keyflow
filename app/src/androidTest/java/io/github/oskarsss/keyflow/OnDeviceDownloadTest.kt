package io.github.oskarsss.keyflow

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.oskarsss.keyflow.library.LibraryStore
import io.github.oskarsss.keyflow.library.OnDeviceLibrary
import io.github.oskarsss.keyflow.library.Preparation
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
        library.download(playlist.getString("id"), first.getString("id")) { message, _ ->
            android.util.Log.i("NativeDownloadTest", message.take(180))
        }
        library.prepare(playlist.getString("id"), first.getString("id")) { message, _ ->
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
        // Exercise the real worker after leaving the UI, with the smallest Download ahead window.
        val second = playlist.getJSONArray("songs").getJSONObject(1)
        store.audioFile(second).delete()
        library.setPreload(id, LibraryStore.MIN_PRELOAD)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                Preparation.wake(it)
                it.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            }
            // The locked level after the next one is always among the wanted songs.
            val deadline = SystemClock.elapsedRealtime() + 600_000
            while (!store.offline(second)) {
                check(SystemClock.elapsedRealtime() < deadline) { "Background song download timed out" }
                val current = library.snapshot(id)!!.getJSONArray("songs").getJSONObject(1)
                check(current.optString("status") != "error") { current.optString("error") }
                Thread.sleep(250)
            }
            assertTrue(store.songFile(second, "jpg").isFile)
            assertEquals("ready", library.snapshot(id)!!.getJSONArray("songs").getJSONObject(1).getString("status"))
            // The workers stop once nothing wanted is missing, and prepare nothing else.
            while (Preparation.downloading != null || Preparation.making != null ||
                Preparation.nextDownload(context) != null || Preparation.nextLevel(context) != null) {
                check(SystemClock.elapsedRealtime() < deadline) { "The worker did not finish the wanted songs" }
                Thread.sleep(500)
            }
            val wanted = Preparation.wantedIds(context)
            val songs = library.snapshot(id)!!.getJSONArray("songs")
            for (i in 0 until songs.length()) if (store.offline(songs.getJSONObject(i)))
                assertTrue("Only wanted songs are prepared", songs.getJSONObject(i).getString("id") in wanted)
        }
        assertEquals(3, library.setPreload(id, 3).getInt("preloadCount"))
    }
}
