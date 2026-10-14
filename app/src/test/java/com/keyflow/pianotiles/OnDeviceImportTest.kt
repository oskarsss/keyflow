package com.keyflow.pianotiles

import com.keyflow.pianotiles.downloader.YTDLRequest
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class OnDeviceImportTest {
    @Test
    fun playlistLinksAreCanonicalized() {
        assertEquals(
            "https://music.youtube.com/playlist?list=PLA2wtd_DK6uw",
            PlaylistUrl.normalize("https://www.youtube.com/watch?v=foo&list=PLA2wtd_DK6uw"),
        )
        for (link in
            listOf(
                "file:///etc/passwd",
                "https://youtube.com.evil.test/playlist?list=PLA2wtd_DK6uw",
                "http://youtube.com/playlist?list=PLA2wtd_DK6uw",
                "https://youtube.com/playlist?list=x",
            )) {
            assertThrows(IllegalArgumentException::class.java) { PlaylistUrl.normalize(link) }
        }
    }

    @Test
    fun reusedYtdlnisRequestsPreserveLiteralArguments() {
        val request =
            YTDLRequest("https://youtube.com/watch?v=literal")
                .addOption("--output", "/private/path with spaces/%(id)s.%(ext)s")
                .addOption("--format", "bestaudio")
                .addOption("--extract-audio")
        assertEquals(
            listOf(
                "--output",
                "/private/path with spaces/%(id)s.%(ext)s",
                "--format",
                "bestaudio",
                "--extract-audio",
                "https://youtube.com/watch?v=literal",
            ),
            request.buildCommand(),
        )
    }

    @Test
    fun pcmAnalysisIsStreamingAndDeterministic() {
        val bytes = ByteArray(11025 * 6 * 2)
        for (i in 0 until bytes.size / 2) {
            val sample = if (i % 6600 < 300) 15000 else 0
            bytes[i * 2] = sample.toByte()
            bytes[i * 2 + 1] = (sample shr 8).toByte()
        }
        val samples = AudioChart.readPcm(ByteArrayInputStream(bytes))
        assertEquals(6.0, samples.duration, .001)
        val chart = AudioChart.generate(samples, "test-song")
        assertEquals(chart, AudioChart.generate(samples, "test-song"))
        assertTrue(chart.notes.isNotEmpty())
        assertTrue(chart.notes.all { it.lane in 0..3 })
    }

    @Test
    fun gesturePhrasesDoNotOverlapFollowingNotes() {
        val notes = (0 until 60).map { AudioChart.Note(1.5 + it * .4, it % 4) }
        val gestures = AudioChart.gestures(notes)
        assertEquals(setOf("tap", "hold", "slide"), gestures.map { it.kind }.toSet())
        for ((note, next) in gestures.zipWithNext()) assertTrue(
            note.time + note.duration < next.time,
        )
    }
}
