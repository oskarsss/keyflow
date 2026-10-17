package com.keyflow.pianotiles.chart

import org.junit.Assert.*
import org.junit.Test

class MusicalRhythmTest {
    @Test
    fun chunkStitchingCoversEveryFrameWithoutTrustingModelBorders() {
        for (frames in listOf(1, 50, 1488, 1489, 1500, 1501, 2976, 3000, 9001, 60000)) {
            val coverage = BooleanArray(frames)
            for (start in MusicalRhythm.chunkStarts(frames)) for (j in 6 until 1494) {
                if (start + j in coverage.indices) coverage[start + j] = true
            }
            assertTrue("Uncovered frame for length $frames", coverage.all { it })
        }
    }

    @Test
    fun compoundArpeggiosAndFastRockDoNotTurnIntoAnUnplayablePulse() {
        val compound = MusicalRhythm(DoubleArray(120) { it * .26 }, DoubleArray(20) { it * 6 * .26 }).forGameplay()
        assertEquals(60 / .78, compound.bpm, .01)
        assertTrue(compound.beats.size < 45)
        val rock = MusicalRhythm(DoubleArray(160) { it * .32 }, DoubleArray(40) { it * 4 * .32 }).forGameplay()
        assertEquals(60 / .64, rock.bpm, .01)
        val withFalseHalfBars = MusicalRhythm(DoubleArray(80) { it * .5 },
            doubleArrayOf(0.0, 2.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 14.0)).forGameplay()
        assertFalse(withFalseHalfBars.downbeats.any { it == 5.0 })
        assertEquals(120.0, withFalseHalfBars.bpm, .01)
    }

    @Test
    fun minimalPostprocessingRejectsNegativeLogitsMergesPlateausAndSnapsDownbeats() {
        val beat = FloatArray(100) { -8f }
        beat[10] = 4f; beat[11] = 4f; beat[35] = 3f; beat[60] = 5f; beat[85] = 4f
        val down = FloatArray(100) { -8f }; down[12] = 3f; down[58] = 2f
        val rhythm = MusicalRhythm.fromLogits(beat, down)
        assertArrayEquals(doubleArrayOf(.21, .7, 1.2, 1.7), rhythm.beats, 1e-9)
        assertArrayEquals(doubleArrayOf(.21, 1.2), rhythm.downbeats, 1e-9)
        assertEquals(120.0, rhythm.bpm, .01)
        assertTrue(MusicalRhythm.peaks(FloatArray(30) { -1f }).isEmpty())
    }
}
