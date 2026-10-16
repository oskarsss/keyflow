package com.keyflow.pianotiles

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ArcadePreparationTest {
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "Background preparation timed out" }
            Thread.sleep(5)
        }
    }

    @Test
    fun backgroundPreparationDeduplicatesRefillsAndStopsRequestingWhenOffline() {
        val context = RuntimeEnvironment.getApplication()
        val store = LibraryStore(context)
        store.prefs.edit().clear().commit()
        File(context.filesDir, "playlists").deleteRecursively()
        File(context.filesDir, "songs").deleteRecursively()
        val library = OnDeviceLibrary.get(context)
        val playlist = library.createPlaylist(JSONObject().put("title", "Buffer test").put("entries", JSONArray((0..7).map {
            JSONObject().put("id", "songid%05d".format(it)).put("title", "Song $it")
        })), Random(1))
        val connected = AtomicBoolean(true)
        val checks = AtomicInteger()
        val requests = ConcurrentLinkedQueue<Arcade.Entry>()
        val preparation = ArcadePreparation(context, { checks.incrementAndGet(); connected.get() }, { _, song, _ -> requests.add(song); Unit })
        // Nothing is cleared yet, so Arcade borrows the first levels and never the far locked ones.
        val nearest = (0 until Arcade.MIN_POOL).map { playlist.getJSONArray("songs").getJSONObject(it).getString("id") }.toSet()
        try {
            preparation.start()
            await { requests.size == Arcade.BUFFER }
            val first = requests.toList()
            assertTrue(first.all { it.id in nearest })
            val queued = library.snapshot(first[0].playlistId)!!
            val queuedSongs = queued.getJSONArray("songs")
            for (i in 0 until queuedSongs.length()) {
                if (queuedSongs.getJSONObject(i).optString("id") in first.map { it.id })
                    queuedSongs.getJSONObject(i).put("status", "error").put("failedAt", 1L)
            }
            library.save(queued)
            val before = checks.get()
            preparation.start()
            await { checks.get() > before }
            assertEquals(Arcade.BUFFER, requests.size)
            for (entry in first) {
                store.songFile(entry.song, "mp3").writeText("audio")
                store.chartFile(entry.song).writeText("{}")
            }
            // Playing a song makes room for one new song.
            preparation.played(first[0].id)
            preparation.start()
            await { requests.size == Arcade.BUFFER + 1 }
            assertEquals(Arcade.BUFFER + 1, requests.map { it.id }.toSet().size)
            val failed = requests.toList().last()
            val failedPlaylist = library.snapshot(failed.playlistId)!!
            val failedSongs = failedPlaylist.getJSONArray("songs")
            for (i in 0 until failedSongs.length()) {
                if (failedSongs.getJSONObject(i).optString("id") == failed.id)
                    failedSongs.getJSONObject(i).put("status", "error").put("failedAt", System.currentTimeMillis() + 1000)
            }
            library.save(failedPlaylist)
            preparation.start()
            await { requests.size == Arcade.BUFFER + 2 }
            assertEquals(Arcade.BUFFER + 2, requests.map { it.id }.toSet().size)
            assertTrue(requests.all { it.id in nearest })
            connected.set(false)
            val offlineCheck = checks.get()
            preparation.start()
            await { checks.get() > offlineCheck }
            assertEquals(Arcade.BUFFER + 2, requests.size)
            library.clearDownloads()
            connected.set(true)
            preparation.start()
            await { requests.size == 2 * Arcade.BUFFER + 2 }
            assertEquals(listOf(first[0].id), preparation.history())
            assertTrue(requests.toList().takeLast(Arcade.BUFFER).none { it.id == first[0].id })
        } finally { preparation.close() }
    }
}
