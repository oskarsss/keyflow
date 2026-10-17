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
}
