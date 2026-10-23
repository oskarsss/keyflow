package io.github.oskarsss.keyflow.chart

import io.github.oskarsss.keyflow.library.LibraryStore
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
    fun allLearnedLocationsAndWhatHighlightsNeedArePersistedBeforePlayback() {
        val notes = listOf(Chart.Note(2.0, 0), Chart.Note(3.0, 2, "hold", .8, 2))
        val chart = Chart(240.0, ManiaChartModel.METHOD, notes,
            downbeats = (0..120).map { it * 2.0 }, sections = listOf(
                Chart.Section(0.0, 60.0, 0, .1), Chart.Section(60.0, 210.0, 1, .9)))
        val saved = PreparedChart.encode(chart)
        val restored = JSONObject(saved.toString())
        assertEquals(2, restored.getJSONArray("notes").length())
        assertEquals(2, restored.getJSONArray("notes").getJSONObject(1).getInt("lane"))
        assertEquals(.8, restored.getJSONArray("notes").getJSONObject(1).getDouble("duration"), 0.0)
        val cuts = PreparedChart.highlights(restored)
        assertTrue(cuts.isNotEmpty())
        assertTrue(cuts.all { it.end - it.start <= 150 })
        assertEquals(LevelExcerpt.choices(chart.duration, chart.sections, chart.downbeats), cuts)
    }

    @Test
    fun audioAndAnIncompleteChartDoNotBecomeReadyToPlay() {
        val store = LibraryStore(RuntimeEnvironment.getApplication())
        val song = JSONObject().put("id", "preparedChartTest")
        val audio = store.audioFile(song)
        val chart = store.chartFile(song)
        val temporary = java.io.File(chart.path + ".part")
        try {
            audio.writeBytes(byteArrayOf(1, 2, 3))
            temporary.writeText(PreparedChart.encode(Chart(20.0, ManiaChartModel.METHOD,
                listOf(Chart.Note(2.0, 1)))).toString())
            assertFalse(store.offline(song))
            java.nio.file.Files.move(temporary.toPath(), chart.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertTrue(store.offline(song))
            assertTrue(PreparedChart.highlights(store.chart(song)).isEmpty())
        } finally { audio.delete(); chart.delete(); temporary.delete() }
    }
}
