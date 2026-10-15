package com.keyflow.pianotiles

import com.keyflow.pianotiles.downloader.YTDLRequest
import java.io.ByteArrayInputStream
import kotlin.math.abs
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

    /** 120 BPM: a quiet intro, a steady verse, a loud chorus, a silent break and a drop. */
    private fun song(): ByteArray {
        val rate = AudioChart.RATE
        val seconds = 64
        val noise = kotlin.random.Random(1)
        val bytes = ByteArray(rate * seconds * 2)
        for (i in 0 until rate * seconds) {
            val t = i / rate.toDouble()
            val beat = t % .5
            val half = (t + .25) % .5
            val (kick, hat, pad) =
                when {
                    t < 8 -> Triple(if (t % 2 < .5) .25 else 0.0, 0.0, .05)
                    t < 24 -> Triple(.5, 0.0, .08)
                    t < 40 -> Triple(.9, .35, .12)
                    t < 48 -> Triple(0.0, 0.0, 0.0)
                    else -> Triple(1.0, .45, .15)
                }
            var v = pad * kotlin.math.sin(2 * Math.PI * 220 * t)
            if (beat < .12) v += kick * kotlin.math.exp(-beat * 30) * kotlin.math.sin(2 * Math.PI * 60 * beat)
            if (half < .04) v += hat * kotlin.math.exp(-half * 80) * (noise.nextDouble() * 2 - 1)
            val sample = (v.coerceIn(-1.0, 1.0) * 30000).toInt()
            bytes[i * 2] = sample.toByte()
            bytes[i * 2 + 1] = (sample shr 8).toByte()
        }
        return bytes
    }

    @Test
    fun chartsFollowTheBeatRestInSilenceAndBuildChordsWhenLoud() {
        val samples = AudioChart.readPcm(ByteArrayInputStream(song()))
        val chart = AudioChart.generate(samples, "beat-song")
        assertEquals(chart, AudioChart.generate(samples, "beat-song"))
        assertEquals("onsets", chart.method)
        val notes = chart.notes
        assertTrue(notes.zipWithNext().all { (a, b) -> a.time <= b.time })
        assertTrue(notes.all { it.lane in 0..3 && it.endLane in 0..3 })
        // Tiles sit on beats or half-beats of the 120 BPM click.
        for (note in notes) {
            val off = note.time % .25
            assertTrue("${note.time} is off the beat", minOf(off, .25 - off) < .05)
        }
        val moments = notes.groupBy { it.time }.toSortedMap()
        for (group in moments.values) assertEquals(group.size, group.map { it.lane }.toSet().size)
        for ((a, b) in moments.entries.zipWithNext()) {
            val end = a.key + a.value.maxOf { it.duration }
            assertTrue("${a.key} overlaps ${b.key}", end < b.key)
            assertTrue(b.key - a.key >= .18)
        }
        assertTrue(notes.none { it.time in 40.6..47.5 })
        fun rate(from: Double, to: Double) = moments.keys.count { it in from..to } / (to - from)
        assertTrue(rate(26.0, 39.0) > rate(10.0, 23.0))
        assertTrue(rate(10.0, 23.0) > rate(2.0, 7.5))
        assertTrue(moments.values.any { it.size >= 2 })
        assertTrue(moments.filterKeys { it in 47.5..52.5 }.values.any { it.size >= 3 })
        assertTrue(notes.any { it.kind != "tap" })
        assertTrue(abs(chart.beats.zipWithNext { a, b -> b - a }.average() - .5) < .02)
    }
}
