package com.keyflow.pianotiles

import java.io.ByteArrayInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class AudioChartTest {
    /** Known attacks, with optional sustained notes so loudness alone cannot find the changes. */
    private fun audio(attacks: List<Double>, duration: Double, sustain: Boolean = false): AudioChart.Samples {
        val rate = AudioChart.RATE
        val pcm = ByteArray((duration * rate).toInt() * 2)
        var note = -1
        for (i in 0 until pcm.size / 2) {
            val time = i / rate.toDouble()
            while (note + 1 < attacks.size && time >= attacks[note + 1]) note++
            if (note < 0) continue
            val age = time - attacks[note]
            val amplitude = if (sustain) .6 else .7 * kotlin.math.exp(-age * 35)
            val frequency = doubleArrayOf(220.0, 440.0, 330.0, 660.0)[note % 4]
            val value = (amplitude * sin(2 * PI * frequency * age) * 30000).toInt()
            pcm[i * 2] = value.toByte()
            pcm[i * 2 + 1] = (value shr 8).toByte()
        }
        return AudioChart.readPcm(ByteArrayInputStream(pcm))
    }

    private fun checkAttacks(chart: AudioChart.Chart, attacks: List<Double>, minimum: Int) {
        assertEquals("onsets", chart.method)
        val times = chart.notes.map { it.time }.distinct()
        assertTrue("Only ${times.size} attacks charted", times.size >= minimum)
        for (time in times) {
            val error = attacks.minOf { abs(time - it) }
            assertTrue("Tile at $time is ${error * 1000} ms from a real attack", error < .045)
        }
        for ((a, b) in chart.notes.groupBy { it.time }.entries.zipWithNext()) {
            assertTrue("Gesture at ${a.key} crowds ${b.key}", a.key + a.value.maxOf { it.duration } + .17 <= b.key + .001)
        }
    }

    @Test
    fun syncopatedNotesKeepTheirTimingInsteadOfSnappingToBeats() {
        val attacks = (0 until 16).flatMap { bar -> listOf(.19, .57, .92, 1.42, 1.76).map { bar * 2 + it } }
        val chart = AudioChart.generate(audio(attacks, 34.0), "syncopation")
        checkAttacks(chart, attacks, 40)
        assertEquals(chart, AudioChart.generate(audio(attacks, 34.0), "syncopation"))
    }

    @Test
    fun pitchChangesAreDetectedEvenWhenLoudnessStaysConstant() {
        val attacks = (0 until 56).map { .23 + it * .5 }
        val chart = AudioChart.generate(audio(attacks, 30.0, sustain = true), "melody")
        checkAttacks(chart, attacks, 30)
    }

    @Test
    fun acceleratingMusicKeepsTilesOnActualAttacks() {
        var time = .2
        val attacks = (0 until 90).map { i -> time.also { time += .58 - i * .0025 } }
        val chart = AudioChart.generate(audio(attacks, time + 1), "accelerando")
        checkAttacks(chart, attacks, 45)
    }
}
