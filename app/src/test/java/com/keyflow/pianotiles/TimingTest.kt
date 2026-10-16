package com.keyflow.pianotiles

import org.junit.Assert.*
import org.junit.Test

class TimingTest {
    private fun hits(lean: Double, count: Int = 40) =
        (0 until count).map { lean + if (it % 2 == 0) 25.0 else -25.0 }

    @Test
    fun lateHitsMoveTheOffsetEarlierAndEarlyHitsLater() {
        assertEquals(-35, TimingCalibration.adjust(0, TimingCalibration.lean(hits(50.0))))
        assertEquals(35, TimingCalibration.adjust(0, TimingCalibration.lean(hits(-50.0))))
    }

    @Test
    fun repeatedRunsConvergeOnTheDeviceDelay() {
        val delay = 120.0
        var offset = 0
        repeat(8) { offset = TimingCalibration.adjust(offset, TimingCalibration.lean(hits(delay + offset))) }
        assertEquals(-delay, offset.toDouble(), 8.0)
    }

    @Test
    fun smallLeansShortRunsAndHugeStepsAreIgnoredOrLimited() {
        assertEquals(10, TimingCalibration.adjust(10, TimingCalibration.lean(hits(4.0))))
        assertNull(TimingCalibration.lean(hits(80.0, TimingCalibration.MIN_HITS - 1)))
        assertEquals(10, TimingCalibration.adjust(10, null))
        assertEquals(-50, TimingCalibration.adjust(10, TimingCalibration.lean(hits(170.0))))
        assertEquals(-TimingCalibration.RANGE_MS, TimingCalibration.adjust(-280, TimingCalibration.lean(hits(170.0))))
    }

    @Test
    fun leanIsMeasuredInRealTimeAtFasterSpeeds() {
        assertEquals(40.0, TimingCalibration.lean(hits(60.0), 1.5f)!!, 1e-9)
    }

    @Test
    fun steadyPositionWaitsForOutputThenSmoothsCoarseReports() {
        val clock = SteadyPosition()
        val frame = 16_666_667L
        var now = 1_000_000_000L
        assertEquals(0.0, clock.update(0.0, true, now), 0.0)
        now += frame
        // The reported position has not moved, so playback has not been heard yet.
        assertEquals(0.0, clock.update(0.0, true, now), 0.0)
        assertFalse(clock.running)
        now += frame
        assertEquals(.020, clock.update(.020, true, now), 0.0)
        // Reports that arrive in 50 ms steps still give a position that advances every frame.
        var previous = .020
        for (i in 1..60) {
            now += frame
            val reported = (i * frame / 1e9 + .020).let { (it / .05).toInt() * .05 }
            val position = clock.update(reported, true, now)
            assertTrue("frame $i went backwards", position > previous)
            assertEquals(i * frame / 1e9 + .020, position, .05)
            previous = position
        }
        assertTrue(clock.running)
    }

    @Test
    fun steadyPositionJumpsToSeeksAndStopsWhenPaused() {
        val clock = SteadyPosition()
        var now = 1_000_000_000L
        clock.update(1.0, true, now)
        now += 10_000_000
        clock.update(1.01, true, now)
        now += 10_000_000
        assertEquals(5.0, clock.update(5.0, true, now), 0.0)
        assertEquals(5.5, clock.update(5.5, false, now + 1), 0.0)
        assertFalse(clock.running)
    }
}
