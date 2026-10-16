package com.keyflow.pianotiles

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
class PreparedChartTest {
    @Test
    fun allLearnedLocationsAndTheChosenExcerptArePersistedBeforePlayback() {
        val notes = listOf(AudioChart.Note(2.0, 0), AudioChart.Note(3.0, 2, "hold", .8, 2))
        val chart = AudioChart.Chart(240.0, ManiaChartModel.METHOD, notes,
            downbeats = (0..120).map { it * 2.0 }, sections = listOf(
                AudioChart.Section(0.0, 60.0, 0, .1), AudioChart.Section(60.0, 210.0, 1, .9)))
        val saved = PreparedChart.encode(chart)
        val restored = JSONObject(saved.toString())
        assertEquals(2, restored.getJSONArray("notes").length())
        assertEquals(2, restored.getJSONArray("notes").getJSONObject(1).getInt("lane"))
        assertEquals(.8, restored.getJSONArray("notes").getJSONObject(1).getDouble("duration"), 0.0)
        val range = PreparedChart.highlight(restored)!!
        assertTrue(range.end - range.start <= 150)
        // Startup uses the stored decision, independently of the analysis inputs.
        restored.remove("sections"); restored.remove("downbeats")
        assertEquals(range, PreparedChart.highlight(restored))
    }

    @Test
    fun audioAndAnIncompleteChartDoNotBecomeReadyToPlay() {
        val store = LibraryStore(RuntimeEnvironment.getApplication())
        val song = JSONObject().put("id", "preparedChartTest")
        val audio = store.songFile(song, "mp3")
        val chart = store.chartFile(song)
        val temporary = java.io.File(chart.path + ".part")
        try {
            audio.writeBytes(byteArrayOf(1, 2, 3))
            temporary.writeText(PreparedChart.encode(AudioChart.Chart(20.0, ManiaChartModel.METHOD,
                listOf(AudioChart.Note(2.0, 1)))).toString())
            assertFalse(store.offline(song))
            java.nio.file.Files.move(temporary.toPath(), chart.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertTrue(store.offline(song))
            assertTrue(store.chart(song).has("highlight"))
            assertNull(PreparedChart.highlight(store.chart(song)))
        } finally { audio.delete(); chart.delete(); temporary.delete() }
    }
}
