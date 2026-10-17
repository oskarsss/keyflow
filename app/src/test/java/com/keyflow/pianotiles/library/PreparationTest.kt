package com.keyflow.pianotiles.library

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.keyflow.pianotiles.library.PreparationPlan.Reason
import java.io.File
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreparationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = LibraryStore(context)

    @Before
    fun reset() {
        store.prefs.edit().clear().commit()
        File(context.filesDir, "playlists").deleteRecursively()
        File(context.filesDir, "songs").deleteRecursively()
    }

    private fun network(unmetered: Boolean) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (unmetered) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
    }

    @Suppress("DEPRECATION")
    private fun charging(on: Boolean) = context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
        .putExtra(BatteryManager.EXTRA_PLUGGED, if (on) BatteryManager.BATTERY_PLUGGED_AC else 0))

    @Test
    fun downloadAheadWaitsForWifiOrAChargerWhenAskedButTheNextLevelsDoNot() {
        val library = OnDeviceLibrary.get(context)
        val raw = JSONObject().put("title", "Mix").put("entries", JSONArray((0 until 10).map {
            JSONObject().put("id", "songid%05d".format(it)).put("title", "Song $it")
        }))
        store.save(library.createPlaylist(raw, Random(1)))
        network(unmetered = false)
        charging(false)
        // On mobile data the next level still comes first, as Wi-Fi only is the default.
        assertEquals(Reason.NEXT, Preparation.next(context)?.reason)
        for (item in PreparationPlan.wanted(Preparation.inputs(context)).filterNot { PreparationPlan.big(it.reason) }) {
            store.songFile(item.song, "mp3").writeText("audio")
            store.chartFile(item.song).writeText("{}")
        }
        assertEquals("Wi-Fi", Preparation.bigDownloadsWaitFor(context))
        assertNull(Preparation.next(context))
        network(unmetered = true)
        assertEquals(Reason.AHEAD, Preparation.next(context)?.reason)

        store.prefs.edit().putBoolean(Preparation.CHARGING_ONLY, true).commit()
        assertEquals("a charger", Preparation.bigDownloadsWaitFor(context))
        assertNull(Preparation.next(context))
        charging(true)
        assertEquals(Reason.AHEAD, Preparation.next(context)?.reason)

        store.prefs.edit().putBoolean(Preparation.CHARGING_ONLY, false).putBoolean(Preparation.WIFI_ONLY, false).commit()
        network(unmetered = false)
        charging(false)
        assertNull(Preparation.bigDownloadsWaitFor(context))
        assertEquals(Reason.AHEAD, Preparation.next(context)?.reason)
    }

    @Test
    fun theQueueListsMissingSongsInOrderWithTheirWaitAndMasksLockedTitles() {
        val library = OnDeviceLibrary.get(context)
        val raw = JSONObject().put("title", "Mix").put("entries", JSONArray((0 until 10).map {
            JSONObject().put("id", "songid%05d".format(it)).put("title", "Secret song $it").put("duration", 200)
        }))
        val playlist = library.createPlaylist(raw, Random(2))
        store.save(playlist)
        network(unmetered = false)
        val songs = playlist.getJSONArray("songs")
        // The next level failed a moment ago.
        val next = songs.getJSONObject(0)
        next.put("status", "error").put("failedAt", System.currentTimeMillis())
        library.save(playlist)
        val queue = Preparation.queue(context)
        val wanted = PreparationPlan.wanted(Preparation.inputs(context)).map { it.id }
        assertEquals(wanted.toSet(), queue.map { it.item.id }.toSet())
        // Songs in line keep the worker's order; the ones held back come last.
        val held = queue.dropWhile { it.state !is Preparation.QueueState.Held }
        assertTrue(held.all { it.state is Preparation.QueueState.Held })
        assertEquals(wanted.filter { id -> queue.none { it.item.id == id && it.state is Preparation.QueueState.Held } },
            queue.take(queue.size - held.size).map { it.item.id })
        val first = queue.first { it.item.id == next.getString("id") }
        assertEquals("Mix", first.playlist)
        assertEquals(next.getString("title"), first.title)
        assertTrue((first.state as Preparation.QueueState.Held).reason.startsWith("Couldn't prepare"))
        // The level after next is still locked, so its title stays masked.
        val locked = queue.first { it.item.reason == Reason.LOCKED }
        assertNotEquals(songs.getJSONObject(1).getString("title"), locked.title)
        // Songs in line are ready one after another; Download ahead waits for Wi-Fi.
        val waits = queue.mapNotNull { (it.state as? Preparation.QueueState.InLine)?.readyInMs }
        assertTrue(waits.isNotEmpty())
        assertEquals(waits.sorted(), waits)
        assertTrue(queue.filter { it.item.reason == Reason.AHEAD }
            .all { (it.state as Preparation.QueueState.Held).reason == "Waits for Wi-Fi" })
        // Songs on the phone leave the queue.
        store.songFile(locked.item.song, "mp3").writeText("audio")
        store.chartFile(locked.item.song).writeText("{}")
        assertTrue(Preparation.queue(context).none { it.item.id == locked.item.id })
    }
}
