package com.keyflow.pianotiles

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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StorageManagementTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = LibraryStore(context)
    private val day = 86_400_000L
    private val now = System.currentTimeMillis()

    @Before
    fun reset() {
        store.prefs.edit().clear().commit()
        File(context.filesDir, "playlists").deleteRecursively()
        File(context.filesDir, "songs").deleteRecursively()
    }

    private fun playlist(library: OnDeviceLibrary): JSONObject = library.createPlaylist(
        JSONObject().put("title", "Mix").put("entries", JSONArray((0..5).map {
            JSONObject().put("id", "songid%05d".format(it)).put("title", "Song $it")
        })), Random(4),
    )

    private fun cached(song: JSONObject, age: Int) {
        val audio = store.songFile(song, "mp3")
        audio.writeText("audio")
        assertTrue(audio.setLastModified(now - age * day))
        store.chartFile(song).writeText("{}")
    }

    @Test
    fun cleanupUsesLastPlayAndProtectsPlayingUpcomingAndFreshlyDownloadedSongs() {
        val library = OnDeviceLibrary(context)
        val playlist = playlist(library)
        val songs = playlist.getJSONArray("songs")
        val list = (0 until songs.length()).map { songs.getJSONObject(it) }
        for (song in list) { cached(song, 60); song.put("status", "ready") }
        library.save(playlist)
        val stale = list[0]
        store.prefs.edit().putInt("song.${stale.getString("id")}.score", 900).commit()
        library.protectPlayback(list[1].getString("id"))
        store.prefs.edit().putStringSet("arcade.reserve", setOf(list[2].getString("id"))).commit()
        store.markPlayed(list[3], now - day)
        cached(list[4], 1)
        store.markPlayed(list[4], now - 90 * day)
        assertEquals(2, library.pruneUnused(30, now))
        assertFalse(store.offline(stale))
        assertFalse(store.offline(list[5]))
        for (i in 1..4) assertTrue(store.offline(list[i]))
        assertEquals(900, store.score(stale))
        val saved = library.snapshot(playlist.getString("id"))!!.getJSONArray("songs")
        assertEquals(6, saved.length())
        assertEquals("pending", saved.getJSONObject(0).getString("status"))
        library.releasePlayback(list[1].getString("id"))
        assertEquals(1, library.pruneUnused(30, now))
    }

    @Test
    fun sharedDownloadsAreRemovedOnceAndBothPlaylistsRemainIntact() {
        val library = OnDeviceLibrary(context)
        val first = playlist(library)
        val second = playlist(library)
        val song = first.getJSONArray("songs").getJSONObject(0)
        cached(song, 40)
        store.prefs.edit().putBoolean("smartStorage", false).commit()
        assertEquals(0, library.pruneUnused(30, now))
        assertTrue(store.offline(song))
        store.prefs.edit().putBoolean("smartStorage", true).commit()
        assertEquals(1, library.pruneUnused(30, now))
        assertFalse(store.offline(song))
        assertEquals(6, library.snapshot(first.getString("id"))!!.getJSONArray("songs").length())
        assertEquals(6, library.snapshot(second.getString("id"))!!.getJSONArray("songs").length())
    }
}
