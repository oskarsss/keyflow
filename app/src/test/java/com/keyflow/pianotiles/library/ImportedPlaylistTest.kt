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
class ImportedPlaylistTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = LibraryStore(context)

    @Before
    fun resetLibrary() {
        store.prefs.edit().clear().commit()
        File(context.filesDir, "playlists").deleteRecursively()
        File(context.filesDir, "songs").deleteRecursively()
    }

    private fun raw(): JSONObject = JSONObject().put("title", "My mix")
        .put("webpage_url", "https://music.youtube.com/playlist?list=PLA2wtd_DK6uw")
        .put("entries", JSONArray((0 until 8).map {
            JSONObject().put("id", "songid%05d".format(it)).put("title", "Song $it")
                .put("uploader", "Artist").put("duration", 120)
        }))

    private fun ids(playlist: JSONObject): List<String> {
        val songs = playlist.getJSONArray("songs")
        return (0 until songs.length()).map { songs.getJSONObject(it).getString("id") }
    }

    @Test
    fun importsAllSongsBeyondTwoHundredAndCountsOnlyUniquePlayableEntries() {
        val library = OnDeviceLibrary(context)
        val entries = JSONArray((0 until 350).map {
            JSONObject().put("id", "songid%05d".format(it)).put("title", "Song $it")
        }).put(JSONObject.NULL).put(JSONObject().put("id", "invalid"))
            .put(JSONObject().put("id", "songid00000"))
        val source = JSONObject().put("title", "Large mix").put("entries", entries)
        assertEquals(350, library.importSongCount(source))
        assertTrue(library.savedPlaylists().isEmpty())
        val playlist = library.createPlaylist(source, Random(8))
        assertEquals(350, ids(playlist).size)
        assertEquals((0 until 350).map { "songid%05d".format(it) }.toSet(), ids(playlist).toSet())
        assertEquals(350, ids(OnDeviceLibrary(context).snapshot(playlist.getString("id"))!!).size)
    }

    @Test
    fun importsAreIndependentShuffledSnapshotsAndKeepTheirOrderAfterRestart() {
        val library = OnDeviceLibrary(context)
        val source = raw()
        source.getJSONArray("entries").put(JSONObject.NULL)
            .put(JSONObject().put("id", "invalid"))
            .put(JSONObject().put("id", "songid00000").put("title", "Duplicate"))
        val first = library.createPlaylist(source, Random(17))
        val firstId = first.getString("id")
        val order = ids(first)
        val sourceOrder = (0 until 8).map { "songid%05d".format(it) }
        assertEquals(sourceOrder.toSet(), order.toSet())
        assertEquals(8, order.size)
        assertNotEquals(sourceOrder, order)
        assertFalse(first.has("url"))
        assertFalse(first.has("webpage_url"))
        library.setPreload(firstId, 3)
        store.prefs.edit().putBoolean("song.${order.first()}.completed", true).commit()
        val original = library.snapshot(firstId)!!.toString()

        source.getJSONArray("entries").getJSONObject(0).put("title", "Changed upstream")
        val second = library.createPlaylist(source, Random(27))
        assertNotEquals(firstId, second.getString("id"))
        assertEquals(LibraryStore.DEFAULT_PRELOAD, second.getInt("preloadCount"))
        assertEquals(original, library.snapshot(firstId)!!.toString())
        val restarted = OnDeviceLibrary(context)
        assertEquals(2, restarted.savedPlaylists().size)
        assertEquals(order, ids(restarted.snapshot(firstId)!!))
        assertEquals(3, restarted.snapshot(firstId)!!.getInt("preloadCount"))
        assertTrue(store.completed(first.getJSONArray("songs").getJSONObject(0)))
        assertTrue((1..10).map { ids(library.createPlaylist(raw(), Random(it))) }.toSet().size > 1)
    }

    @Test
    fun independentImportsReuseDownloadedAudioChartsAndMetadata() {
        val library = OnDeviceLibrary(context)
        val first = library.createPlaylist(raw(), Random(1))
        val song = first.getJSONArray("songs").getJSONObject(0)
        song.put("title", "Downloaded title").put("artist", "Downloaded artist")
            .put("duration", 123)
        store.audioFile(song).writeText("cached audio")
        store.chartFile(song).writeText("{\"duration\":123,\"notes\":[]}")
        library.save(first)
        val second = library.createPlaylist(raw(), Random(2))
        val songs = second.getJSONArray("songs")
        val reused = (0 until songs.length()).map { songs.getJSONObject(it) }
            .first { it.getString("id") == song.getString("id") }
        assertEquals("ready", reused.getString("status"))
        assertEquals("Downloaded title", reused.getString("title"))
        assertEquals("Downloaded artist", reused.getString("artist"))
        assertEquals(123, reused.getInt("duration"))
        assertTrue(store.offline(reused))
    }

    @Test
    fun interruptedDownloadsRecoverOnRestartWithoutReordering() {
        val library = OnDeviceLibrary(context)
        val playlist = library.createPlaylist(raw(), Random(3))
        val songs = playlist.getJSONArray("songs")
        songs.getJSONObject(0).put("status", "caching")
        songs.getJSONObject(1).put("status", "ready")
        val cached = songs.getJSONObject(2)
        store.audioFile(cached).writeText("audio")
        store.chartFile(cached).writeText("{}")
        library.save(playlist)
        val recovered = OnDeviceLibrary(context).snapshot(playlist.getString("id"))!!
        assertEquals(ids(playlist), ids(recovered))
        assertEquals("pending", recovered.getJSONArray("songs").getJSONObject(0).getString("status"))
        assertEquals("pending", recovered.getJSONArray("songs").getJSONObject(1).getString("status"))
        assertEquals("ready", recovered.getJSONArray("songs").getJSONObject(2).getString("status"))

    }

    @Test
    fun downloadWindowFollowsProgressAndIncludesAlreadyCachedSongsInItsLimit() {
        val library = OnDeviceLibrary(context)
        val playlist = library.createPlaylist(raw(), Random(4))
        val songs = playlist.getJSONArray("songs")
        store.prefs.edit().putBoolean("song.${songs.getJSONObject(0).getString("id")}.completed", true)
            .putBoolean("song.${songs.getJSONObject(1).getString("id")}.completed", true).commit()
        var window = library.setPreload(playlist.getString("id"), 2)
        val cached = songs.getJSONObject(2)
        store.audioFile(cached).writeText("audio")
        store.chartFile(cached).writeText("{}")
        assertEquals(ids(playlist).subList(2, 4), store.upcoming(window).map { it.getString("id") })
        // The next level and the locked one after it are always kept, whatever the setting.
        assertThrows(IllegalArgumentException::class.java) { library.setPreload(playlist.getString("id"), 1) }
        assertEquals(ids(playlist).subList(2, 4), store.upcoming(window.put("preloadCount", 0)).map { it.getString("id") })
        assertEquals(ids(playlist).subList(2, 4), store.essential(window).map { it.getString("id") })
        window = library.setPreload(playlist.getString("id"), 20)
        assertEquals(6, store.upcoming(window).size)
        val edit = store.prefs.edit()
        for (id in ids(playlist)) edit.putBoolean("song.$id.completed", true)
        edit.commit()
        assertTrue(store.upcoming(playlist).isEmpty())
    }

    @Test
    fun clearingDownloadsResetsAllCollectionsAndDiscardsPreviouslyQueuedWork() {
        val library = OnDeviceLibrary(context)
        val first = library.createPlaylist(raw(), Random(5))
        library.createPlaylist(raw(), Random(6))
        val song = first.getJSONArray("songs").getJSONObject(0)
        store.audioFile(song).writeText("audio")
        store.chartFile(song).writeText("{}")
        song.put("status", "ready").put("error", "Old error")
        library.save(first)
        store.prefs.edit().putInt("song.${song.getString("id")}.score", 1000).commit()
        val generation = library.cacheGeneration
        library.clearDownloads()
        assertFalse(store.offline(song))
        assertEquals(1000, store.score(song))
        for (playlist in library.savedPlaylists()) {
            val songs = playlist.getJSONArray("songs")
            for (i in 0 until songs.length()) {
                assertEquals("pending", songs.getJSONObject(i).getString("status"))
                assertFalse(songs.getJSONObject(i).has("error"))
            }
        }
        // These IDs would fail immediately if stale queued work reached the downloader.
        library.download("missing-playlist", "missing-song", generation) { _, _ -> fail("Stale queue ran") }
        library.prepare("missing-playlist", "missing-song", generation) { _, _ -> fail("Stale queue ran") }
        assertFalse(store.offline(song))
    }

    @Test
    fun songsYouTubeWontServeLeaveEveryPlaylistOnceAndImportsSkipThem() {
        val library = OnDeviceLibrary(context)
        val first = library.createPlaylist(raw(), Random(8))
        val second = library.createPlaylist(raw(), Random(9))
        val songs = first.getJSONArray("songs")
        val id = first.getString("id")
        val gone = songs.getJSONObject(0)
        library.downloadFailed(id, gone, IllegalStateException("ERROR: [youtube] ${gone.getString("id")}: Video unavailable\n"))
        // YouTube's rate limit reads much the same, but passes.
        val busy = songs.getJSONObject(1)
        library.downloadFailed(id, busy, IllegalStateException("ERROR: [youtube] x: Video unavailable. This content isn't available, try again later."))
        // The unavailable song leaves every playlist, keeping the order.
        for (playlist in listOf(first, second)) {
            val left = ids(library.snapshot(playlist.getString("id"))!!)
            assertEquals(ids(playlist).filter { it != gone.getString("id") }, left)
        }
        assertTrue(busy.getString("id") in ids(library.snapshot(id)!!))
        val restarted = library
        assertTrue(store.unavailable(gone.getString("id")))
        // The player is told once.
        assertEquals(listOf(gone.getString("title")), store.takeUnavailableNotice())
        assertTrue(store.takeUnavailableNotice().isEmpty())
        // A new import leaves it out.
        assertFalse(gone.getString("id") in ids(restarted.createPlaylist(raw(), Random(10))))
    }

    @Test
    fun selectionStoresOnlyAnIdAndDoesNotRecreateMissingIndexes() {
        val library = OnDeviceLibrary(context)
        val playlist = library.createPlaylist(raw(), Random(7))
        store.save(playlist)
        assertEquals(playlist.getString("id"), store.prefs.getString("playlistId", null))
        assertFalse(store.prefs.contains("playlist"))
        assertEquals(ids(playlist), ids(store.playlist!!))
        File(context.filesDir, "playlists/${playlist.getString("id")}.json").delete()
        assertNull(store.playlist)
        assertTrue(library.savedPlaylists().isEmpty())
    }

    @Test
    fun emptyImportsNeverCreateACollection() {
        val library = OnDeviceLibrary(context)
        assertThrows(IllegalStateException::class.java) { library.createPlaylist(JSONObject()) }
        assertTrue(library.savedPlaylists().isEmpty())
    }

    @Test
    fun renamingKeepsSongsAndDeletingKeepsDownloadsOtherPlaylistsUse() {
        val library = OnDeviceLibrary(context)
        val first = library.createPlaylist(raw(), Random(1))
        val second = library.createPlaylist(
            JSONObject().put("title", "Other").put("entries", JSONArray().put(
                JSONObject().put("id", "songid00000").put("title", "Shared"),
            )),
            Random(2),
        )
        val renamed = library.rename(first.getString("id"), "  Road trip  ")
        assertEquals("Road trip", renamed.getString("title"))
        assertEquals(ids(first), ids(library.snapshot(first.getString("id"))!!))

        val shared = JSONObject().put("id", "songid00000")
        val own = JSONObject().put("id", "songid00001")
        for (song in listOf(shared, own)) {
            store.audioFile(song).writeText("audio")
            store.chartFile(song).writeText("{}")
        }
        store.prefs.edit().putBoolean("song.songid00001.completed", true).commit()
        library.deletePlaylist(first.getString("id"))
        assertNull(library.snapshot(first.getString("id")))
        assertEquals(listOf(second.getString("id")), library.savedPlaylists().map { it.getString("id") })
        assertTrue(store.offline(shared))
        assertFalse(store.audioFile(own).exists())
        assertFalse(store.chartFile(own).exists())
        assertTrue(store.completed(own))
    }

    @Test
    fun plansReadEachPlaylistOnceUntilItChanges() {
        val library = OnDeviceLibrary(context)
        val playlist = library.createPlaylist(raw(), Random(9))
        val id = playlist.getString("id")
        val first = library.readPlaylists().single()
        assertSame(first, library.readPlaylists().single())
        library.rename(id, "Renamed")
        assertEquals("Renamed", library.readPlaylists().single().getString("title"))
        library.deletePlaylist(id)
        assertTrue(library.readPlaylists().isEmpty())
    }

    @Test
    fun oneFolderListingAnswersWhichSongsArePlayable() {
        val songs = (0 until 4).map { JSONObject().put("id", "songid%05d".format(it)) } + JSONObject().put("id", "x").put("demo", true)
        store.audioFile(songs[0]).writeText("audio")
        store.chartFile(songs[0]).writeText("{}")
        // Audio without a chart isn't playable yet.
        store.audioFile(songs[1]).writeText("audio")
        val offline = store.offlineCheck()
        assertEquals(songs.map(store::offline), songs.map(offline))
        assertEquals(listOf(true, false, false, false, true), songs.map(offline))
    }
}
