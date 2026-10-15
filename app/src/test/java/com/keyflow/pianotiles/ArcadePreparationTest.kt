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
        library.createPlaylist(JSONObject().put("title", "Buffer test").put("entries", JSONArray((0..7).map {
            JSONObject().put("id", "songid%05d".format(it)).put("title", "Song $it")
        })), Random(1))
        val connected = AtomicBoolean(true)
        val checks = AtomicInteger()
        val requests = ConcurrentLinkedQueue<Arcade.Entry>()
        val preparation = ArcadePreparation(context, { checks.incrementAndGet(); connected.get() }, { _, song -> requests.add(song); Unit })
        try {
            preparation.start()
            await { requests.size == 2 }
            val first = requests.toList()
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
            assertEquals(2, requests.size)
            for (entry in first) {
                store.songFile(entry.song, "mp3").writeText("audio")
                store.chartFile(entry.song).writeText("{}")
            }
            preparation.start(listOf(first[0].id))
            await { requests.size == 3 }
            assertEquals(3, requests.map { it.id }.toSet().size)
            val failed = requests.toList().last()
            val failedPlaylist = library.snapshot(failed.playlistId)!!
            val failedSongs = failedPlaylist.getJSONArray("songs")
            for (i in 0 until failedSongs.length()) {
                if (failedSongs.getJSONObject(i).optString("id") == failed.id)
                    failedSongs.getJSONObject(i).put("status", "error").put("failedAt", System.currentTimeMillis() + 1000)
            }
            library.save(failedPlaylist)
            preparation.start(listOf(first[0].id))
            await { requests.size == 4 }
            assertEquals(4, requests.map { it.id }.toSet().size)
            connected.set(false)
            val offlineCheck = checks.get()
            preparation.start(first.map { it.id })
            await { checks.get() > offlineCheck }
            assertEquals(4, requests.size)
            library.clearDownloads()
            connected.set(true)
            preparation.start()
            await { requests.size == 6 }
        } finally { preparation.close() }
    }
}
