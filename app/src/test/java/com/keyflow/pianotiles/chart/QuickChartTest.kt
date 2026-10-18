package com.keyflow.pianotiles.chart

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class QuickChartTest {
    /** A 60 s song at 120 BPM: an attack on every beat, a hard one between beats in its loud half. */
    private fun song(): Pair<MusicFeatures, MusicalRhythm> {
        val frames = 60 * 50
        val mel = FloatArray(frames * MusicFeatures.BINS)
        val energy = FloatArray(frames) { if (it < frames / 2) .01f else .05f }
        fun attack(frame: Int, level: Float) {
            for (f in frame until minOf(frames, frame + 3)) for (b in 0 until MusicFeatures.BINS)
                mel[f * MusicFeatures.BINS + b] = level * (1 - (f - frame) / 3f)
        }
        for (frame in 0 until frames step 25) attack(frame, 2f)
        for (frame in frames / 2 + 12 until frames step 25) attack(frame, 3f)
        val beats = DoubleArray(120) { it * .5 }
        return MusicFeatures(mel, FloatArray(frames * 12) { .3f }, energy, 60.0) to
            MusicalRhythm(beats, beats.filterIndexed { i, _ -> i % 4 == 0 }.toDoubleArray())
    }

    @Test
    fun tilesFollowTheBeatAddLoudOffBeatsAndStayPlayable() {
        val (features, rhythm) = song()
        val chart = QuickChart.generate(features, rhythm, seed = 7)
        assertEquals(QuickChart.METHOD, chart.method)
        assertTrue(chart.notes.size > 60)
        // Every tile sits on a beat or halfway between two.
        assertTrue(chart.notes.all { note -> abs(note.time * 4 - Math.round(note.time * 4)) < .004 })
        val offBeats = chart.notes.filter { abs(it.time * 2 - Math.round(it.time * 2)) > .1 }
        assertTrue(offBeats.isNotEmpty())
        // Off-beats only come where the music hits hard, in the loud half.
        assertTrue(offBeats.all { it.time >= 30 })
        assertEquals(ThumbChart.makePlayable(chart.notes), chart.notes)
        assertTrue(chart.notes.all { it.lane in 0..3 })
        // Sections and bars come along, so highlights work as for a full level.
        assertTrue(chart.sections.isNotEmpty())
        assertEquals(chart, QuickChart.generate(features, rhythm, seed = 7))
    }
}
