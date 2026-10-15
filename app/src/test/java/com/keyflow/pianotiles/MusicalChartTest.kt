package com.keyflow.pianotiles

import java.io.ByteArrayInputStream
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class MusicalChartTest {
    @Test
    fun repeatedChorusesReuseTheirRhythmAndLanesAndRemainDenserThanTheVerse() {
        val duration = 68.0
        val rate = MusicFeatures.RATE
        val attacks = (0 until 136).flatMap { beat ->
            val t = beat * .5
            val chorus = t in 16.0..<32.0 || t in 48.0..<64.0
            if (chorus) listOf(t, t + .25) else listOf(t)
        }
        val pcm = ByteArray((duration * rate).toInt() * 2)
        var onset = 0
        for (i in 0 until pcm.size / 2) {
            val t = i.toDouble() / rate
            while (onset + 1 < attacks.size && attacks[onset + 1] <= t) onset++
            val age = t - attacks[onset]
            val chorus = t in 16.0..<32.0 || t in 48.0..<64.0
            val verse = t < 16
            val frequency = if (chorus) doubleArrayOf(220.0, 330.0, 440.0, 550.0)[(floor((t % 16) * 2).toInt()).mod(4)]
                else if (verse) 220.0 else 147.0
            val amplitude = if (chorus) .8 else if (verse) .28 else .12
            val value = (amplitude * exp(-age * 22) * (sin(2 * PI * frequency * age) + .3 * sin(2 * PI * frequency * 2 * age)) * 25000).toInt()
            pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
        }
        val halfRate = ByteArray(pcm.size / 2)
        for (i in 0 until halfRate.size / 2) { halfRate[i * 2] = pcm[i * 4]; halfRate[i * 2 + 1] = pcm[i * 4 + 1] }
        val features = MusicFeatures.readPcm(ByteArrayInputStream(pcm))
        val samples = AudioChart.readPcm(ByteArrayInputStream(halfRate))
        val rhythm = MusicalRhythm(DoubleArray(136) { it * .5 }, DoubleArray(34) { it * 2.0 })
        val chart = MusicalChart.generate(samples, features, rhythm, "repeated-chorus")
        assertTrue(chart.notes.isNotEmpty())
        assertEquals("beat-this-phrases-v1", chart.method)
        for (note in chart.notes) assertTrue("Unheard attack at ${note.time}", attacks.minOf { abs(it - note.time) } < .045)
        val a = chart.sections.first { it.start == 20.0 }
        val b = chart.sections.first { it.start == 52.0 }
        assertEquals("Repeated harmony/rhythm must have the same phrase family", a.family, b.family)
        fun motif(start: Double) = chart.notes.filter { it.time >= start && it.time < start + 4 }
        val first = motif(20.0)
        val repeated = motif(52.0)
        assertEquals(first.map { it.lane to it.kind }, repeated.map { it.lane to it.kind })
        for ((original, repeat) in first.zip(repeated)) {
            assertEquals("Repeated rhythm must retain its slot despite onset-window quantization", original.time - 20, repeat.time - 52, .02)
        }
        val verseRate = chart.notes.count { it.time in 4.0..<16.0 } / 12.0
        val chorusRate = chart.notes.count { it.time in 20.0..<32.0 } / 12.0
        assertTrue("Chorus $chorusRate should exceed verse $verseRate", chorusRate > verseRate * 1.15)
        assertTrue(chart.notes.groupBy { it.time }.values.all { it.size <= 2 })
    }
    @Test
    fun sustainedTonesCanBeHeldOverPercussionButSeparatePlucksStayTaps() {
        val rate = MusicFeatures.RATE
        val duration = 32.0
        val period = 60.0 / 84
        val times = generateSequence(.2) { it + period }.takeWhile { it < duration - .5 }.toList()
        val rhythm = MusicalRhythm(times.toDoubleArray(), times.filterIndexed { i, _ -> i % 4 == 0 }.toDoubleArray())
        fun render(continuous: Boolean): AudioChart.Chart {
            val pcm = ByteArray((duration * rate).toInt() * 2)
            for (i in 0 until pcm.size / 2) {
                val t = i.toDouble() / rate
                val age = if (t < .2) 100.0 else (t - .2) % period
                val pad = if (continuous) .20 else .6 * exp(-age * 35)
                val tone = pad * (sin(2 * PI * 220 * t) + .4 * sin(2 * PI * 440 * t))
                val kick = .16 * exp(-age * 60) * sin(2 * PI * 65 * age)
                val chorus = if (t in 12.0..<20.0) .4 * exp(-age * 15) * sin(2 * PI * 910 * age) else 0.0
                val value = ((tone + kick + chorus) * 24000).toInt().coerceIn(-32767, 32767)
                pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
            }
            val half = ByteArray(pcm.size / 2)
            for (i in 0 until half.size / 2) { half[i * 2] = pcm[i * 4]; half[i * 2 + 1] = pcm[i * 4 + 1] }
            return MusicalChart.generate(AudioChart.readPcm(ByteArrayInputStream(half)),
                MusicFeatures.readPcm(ByteArrayInputStream(pcm)), rhythm, "tonal-sustain")
        }
        val held = render(true)
        val plucked = render(false)
        assertTrue("A continuous tone should supply a real sustain gesture", held.notes.any { it.duration > 0 })
        assertTrue("Percussive plucks must not be invented into long notes", plucked.notes.none { it.duration > 0 })
    }

}
