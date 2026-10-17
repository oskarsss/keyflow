package com.keyflow.pianotiles.library

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

    private fun playlist(library: OnDeviceLibrary, prefix: String = "songid"): JSONObject = library.createPlaylist(
        JSONObject().put("title", "Mix").put("entries", JSONArray((0..5).map {
            JSONObject().put("id", "$prefix%05d".format(it)).put("title", "Song $it")
        })), Random(4),
    )

    private fun cached(song: JSONObject, age: Int) {
        val audio = store.songFile(song, "mp3")
        audio.writeText("audio")
        assertTrue(audio.setLastModified(now - age * day))
        store.chartFile(song).writeText("{}")
    }

    @Test
    fun cleanupUsesLastPlayAndProtectsPlayingWantedAndFreshlyDownloadedSongs() {
        val library = OnDeviceLibrary(context)
        val playlist = library.setPreload(playlist(library).getString("id"), LibraryStore.MIN_PRELOAD)
        val songs = playlist.getJSONArray("songs")
        val list = (0 until songs.length()).map { songs.getJSONObject(it) }
        for (song in list) { cached(song, 60); song.put("status", "ready") }
        library.save(playlist)
        val stale = list[0]
        store.prefs.edit().putInt("song.${stale.getString("id")}.score", 900)
            .putBoolean("song.${stale.getString("id")}.completed", true).commit()
        library.protectPlayback(list[1].getString("id"))
        // Arcade just played the first song, so it keeps the next three fresh ones instead.
        store.prefs.edit().putString("arcade.history", stale.getString("id")).commit()
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
        // The next level and the locked one after it stay however old they are.
        assertEquals(0, library.pruneUnused(30, now))
        assertTrue(store.offline(list[1]))
        assertTrue(store.offline(list[2]))
    }

    @Test
    fun sharedDownloadsAreRemovedOnceAndBothPlaylistsRemainIntact() {
        val library = OnDeviceLibrary(context)
        val first = playlist(library)
        val second = playlist(library)
        val song = first.getJSONArray("songs").getJSONObject(0)
        cached(song, 40)
        store.prefs.edit().putBoolean("song.${song.getString("id")}.completed", true).commit()
        // Arcade has played it, so nothing wants it kept.
        store.prefs.edit().putString("arcade.history", song.getString("id")).commit()
        store.prefs.edit().putBoolean("smartStorage", false).commit()
        assertEquals(0, library.pruneUnused(30, now))
        assertTrue(store.offline(song))
        store.prefs.edit().putBoolean("smartStorage", true).commit()
        assertEquals(1, library.pruneUnused(30, now))
        assertFalse(store.offline(song))
        assertEquals(6, library.snapshot(first.getString("id"))!!.getJSONArray("songs").length())
        assertEquals(6, library.snapshot(second.getString("id"))!!.getJSONArray("songs").length())
    }

    @Test
    fun aPlaylistCanKeepEverySongOrFreeItsOwnDownloads() {
        val library = OnDeviceLibrary(context)
        val mine = playlist(library, "mysong")
        val other = playlist(library, "others")
        val list = mine.getJSONArray("songs").let { a -> (0 until a.length()).map(a::getJSONObject) }
        // One song is in both playlists.
        val shared = other.getJSONArray("songs").getJSONObject(5).put("id", list[5].getString("id"))
        library.save(other)
        for (song in list) cached(song, 60)
        assertEquals(6 to list.sumOf { store.songFile(it, "mp3").length() + store.chartFile(it).length() }, library.usage(list))

        assertTrue(store.keepsDownloads(library.setKeepDownloads(mine.getString("id"), true)))
        assertEquals(0, library.pruneUnused(30, now))
        library.setKeepDownloads(mine.getString("id"), false)

        // The next level and the locked one after it stay, and so does the shared song.
        assertEquals(3, library.removeDownloads(mine.getString("id")))
        assertEquals(listOf(true, true, false, false, false, true), list.map(store::offline))
        val saved = library.snapshot(mine.getString("id"))!!.getJSONArray("songs")
        assertEquals("pending", saved.getJSONObject(2).getString("status"))
        assertEquals(3, library.usage(list).first)
        assertTrue(library.snapshot(other.getString("id"))!!.getJSONArray("songs").getJSONObject(5).let(store::offline))
        assertTrue(shared.getString("id") == list[5].getString("id"))
    }
}
