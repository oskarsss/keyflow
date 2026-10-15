package com.keyflow.pianotiles

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RhythmAnalysisTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun nativeNeuralInferenceAndFrontendFollowKnownPianoAttacks() {
        val audio = File(context.cacheDir, "neural-original-test.wav")
        try {
            context.assets.open("demo/first-light.wav").use { input -> audio.outputStream().use(input::copyTo) }
            val runtime = OnDeviceLibrary.get(context).runtime
            val features = runtime.audioSamples(audio, "neural-demo-features", MusicFeatures.RATE, MusicFeatures::readPcm)
            val samples = runtime.audioSamples(audio, "neural-demo-onsets", AudioChart::readPcm)
            val rhythm = BeatThis(context).analyze(features)
            assertTrue("No neural pulse found", rhythm.beats.size > 20)
            val chart = MusicalChart.generate(samples, features, rhythm, "neural-original-test")
            assertTrue(chart.notes.size > 20)
            val attacks = (0 until 48).map { 1.5 + it * 60.0 / 84 }
            for (note in chart.notes) assertTrue("Tile at ${note.time} misses the original piano attack",
                attacks.minOf { abs(it - note.time) } < .060)
            assertTrue(chart.notes.groupBy { it.time }.values.all { it.size <= 2 })
            assertTrue(chart.sections.isNotEmpty())
        } finally { audio.delete() }
    }

    @Test
    fun optionalUserSongCorpusRunsThroughProductionPreparationWithoutNetwork() {
        val directory = InstrumentationRegistry.getArguments().getString("rhythmFixtureDir")
        assumeTrue("Optional external music evaluation", directory != null)
        val sources = File(directory!!).listFiles().orEmpty().filter { it.extension == "mp3" }.sortedBy { it.name }
        assertEquals("Expected all six requested songs", 6, sources.size)
        val library = OnDeviceLibrary.get(context)
        val store = LibraryStore(context)
        val id = "rhythm-evaluation-${System.nanoTime()}"
        val songs = JSONArray(sources.mapIndexed { index, file -> JSONObject().put("id", "rhythm%05d".format(index))
            .put("title", file.nameWithoutExtension).put("artist", "Test corpus").put("status", "pending") })
        val playlist = JSONObject().put("id", id).put("title", "Rhythm evaluation").put("preloadCount", 0).put("songs", songs)
        val report = JSONArray()
        library.save(playlist)
        try {
            for ((index, source) in sources.withIndex()) {
                val song = songs.getJSONObject(index)
                source.copyTo(store.songFile(song, "mp3"), overwrite = true)
                val started = android.os.SystemClock.elapsedRealtime()
                library.cacheSong(id, song.getString("id")) {}
                assertTrue(store.offline(song))
                val chart = store.chart(song)
                assertEquals("beat-this-phrases-v1", chart.getString("method"))
                val notes = chart.getJSONArray("notes")
                assertTrue("Too few notes in ${source.name}", notes.length() > 60)
                val groups = (0 until notes.length()).map { notes.getJSONObject(it) }.groupBy { it.getDouble("time") }
                assertTrue(groups.values.all { it.size <= 2 && it.map { n -> n.getInt("lane") / 2 }.distinct().size == it.size })
                val sections = chart.getJSONArray("sections")
                val families = (0 until sections.length()).groupingBy { sections.getJSONObject(it).getInt("family") }.eachCount()
                val item = JSONObject().put("song", source.nameWithoutExtension).put("notes", notes.length())
                    .put("bpm", chart.getDouble("bpm")).put("sections", sections.length())
                    .put("repeatedFamilies", families.values.count { it > 1 })
                    .put("milliseconds", android.os.SystemClock.elapsedRealtime() - started)
                report.put(item)
                android.util.Log.i("RhythmEvaluation", item.toString())
            }
            File(context.getExternalFilesDir(null), "rhythm-native-report.json").writeText(report.toString(2))
        } finally {
            library.deletePlaylist(id)
            for (index in 0 until songs.length()) {
                val song = songs.getJSONObject(index)
                store.songFile(song, "mp3").delete()
                store.chartFile(song).delete()
            }
        }
    }
}
