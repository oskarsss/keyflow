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
        assertEquals(Reason.NEXT, Preparation.nextDownload(context)?.reason)
        assertNull(Preparation.nextLevel(context))
        val small = PreparationPlan.wanted(Preparation.inputs(context)).filterNot { PreparationPlan.big(it.reason) }
        for (item in small) store.audioFile(item.song).writeText("audio")
        // Downloaded songs get their levels made on any connection.
        assertEquals(small.first().id, Preparation.nextLevel(context)?.id)
        for (item in small) store.chartFile(item.song).writeText("{}")
        assertEquals("Wi-Fi", Preparation.bigDownloadsWaitFor(context))
        assertNull(Preparation.nextDownload(context))
        network(unmetered = true)
        val ahead = Preparation.nextDownload(context)!!
        assertEquals(Reason.AHEAD, ahead.reason)

        store.prefs.edit().putBoolean(Preparation.CHARGING_ONLY, true).commit()
        assertEquals("a charger", Preparation.bigDownloadsWaitFor(context))
        assertNull(Preparation.nextDownload(context))
        // A charger also holds back making their levels; Wi-Fi doesn't.
        store.audioFile(ahead.song).writeText("audio")
        assertEquals("a charger", Preparation.bigLevelsWaitFor(context))
        assertNull(Preparation.nextLevel(context))
        charging(true)
        assertEquals(Reason.AHEAD, Preparation.nextDownload(context)?.reason)
        assertEquals(ahead.id, Preparation.nextLevel(context)?.id)

        store.prefs.edit().putBoolean(Preparation.CHARGING_ONLY, false).putBoolean(Preparation.WIFI_ONLY, false).commit()
        network(unmetered = false)
        charging(false)
        assertNull(Preparation.bigDownloadsWaitFor(context))
        assertEquals(Reason.AHEAD, Preparation.nextDownload(context)?.reason)
        assertEquals(ahead.id, Preparation.nextLevel(context)?.id)
    }

    @Test
    fun theQueuesListSongsToDownloadAndLevelsToMakeInOrderWithTheirWaitAndMaskLockedTitles() {
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
        // The locked level is downloaded, with a quick level to play meanwhile.
        val locked = songs.getJSONObject(1)
        store.audioFile(locked).writeText("audio")
        store.quickChartFile(locked).writeText("{}")
        val queues = Preparation.queues(context)
        val wanted = PreparationPlan.wanted(Preparation.inputs(context)).map { it.id }
        assertEquals(wanted.toSet() - locked.getString("id"), queues.downloads.map { it.item.id }.toSet())
        assertEquals(listOf(locked.getString("id")), queues.levels.map { it.item.id })
        // Songs in line keep the worker's order; the ones held back come last.
        val held = queues.downloads.dropWhile { it.state !is Preparation.QueueState.Held }
        assertTrue(held.all { it.state is Preparation.QueueState.Held })
        assertEquals(wanted.filter { id -> queues.downloads.any { it.item.id == id && it.state !is Preparation.QueueState.Held } },
            queues.downloads.take(queues.downloads.size - held.size).map { it.item.id })
        val first = queues.downloads.first { it.item.id == next.getString("id") }
        assertEquals("Mix", first.playlist)
        assertEquals(next.getString("title"), first.title)
        assertTrue((first.state as Preparation.QueueState.Held).reason.startsWith("Couldn't download"))
        // The level after next is still locked, so its title stays masked.
        val level = queues.levels.single()
        assertNotEquals(locked.getString("title"), level.title)
        assertTrue(level.quick)
        // Downloads follow one another; Download ahead waits for Wi-Fi.
        val waits = queues.downloads.mapNotNull { (it.state as? Preparation.QueueState.InLine)?.doneInMs }
        assertTrue(waits.isNotEmpty())
        assertEquals(waits.sorted(), waits)
        assertTrue(queues.downloads.filter { it.item.reason == Reason.AHEAD }
            .all { (it.state as Preparation.QueueState.Held).reason == "Waits for Wi-Fi" })
        // The quick level counts as ready to play, and a full level takes the song out of the queues.
        assertEquals(Preparation.Readiness.Failed, Preparation.readiness(context)[playlist.getString("id")])
        store.chartFile(locked).writeText("{}")
        assertTrue(Preparation.queues(context).levels.isEmpty())
    }
}
