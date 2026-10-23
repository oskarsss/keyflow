package io.github.oskarsss.keyflow.library

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreparationTimeTest {
    private val prefs get() = LibraryStore(RuntimeEnvironment.getApplication()).prefs
    private val time get() = PreparationTime(prefs)

    @Before
    fun reset() {
        prefs.edit().clear().commit()
    }

    private fun song(seconds: Int) = JSONObject().put("id", "songid00000").put("duration", seconds)

    @Test
    fun longerSongsTakeLongerToChartAndADownloadedSongOnlyCountsItsLevel() {
        val short = time.estimate(song(120), downloaded = false)
        val long = time.estimate(song(480), downloaded = false)
        assertTrue(long > short)
        assertEquals(long - short, time.chartMs(song(480)) - time.chartMs(song(120)))
        assertEquals(time.chartMs(song(240)), time.estimate(song(240), downloaded = true))
        // Songs whose length isn't known count as a typical one.
        assertEquals(time.chartMs(song(PreparationTime.TYPICAL_SECONDS.toInt())), time.chartMs(JSONObject()))
    }

    @Test
    fun whatIsLeftFollowsTheSongThroughDownloadThenChart() {
        val song = song(200)
        assertEquals(time.downloadMs, time.downloadLeft(0.0))
        assertEquals(0L, time.downloadLeft(OnDeviceLibrary.DOWNLOAD_SHARE))
        assertEquals(time.chartMs(song), time.chartLeft(song, OnDeviceLibrary.DOWNLOAD_SHARE))
        val halfCharted = OnDeviceLibrary.DOWNLOAD_SHARE + (1 - OnDeviceLibrary.DOWNLOAD_SHARE) / 2
        assertEquals(time.chartMs(song) / 2.0, time.chartLeft(song, halfCharted).toDouble(), 2.0)
        assertEquals(0L, time.chartLeft(song, 1.0))
    }

    @Test
    fun bothWorkersRunSideBySideAndALevelWaitsForItsDownload() {
        val job = PreparationTime::Job
        // a is downloaded; b and c are still to download, 10 s each.
        val schedule = PreparationTime.schedule(listOf(job("b", 10_000), job("c", 10_000)),
            listOf(job("a", 30_000), job("b", 30_000), job("c", 30_000)))
        assertEquals(10_000L, schedule.downloaded["b"])
        assertEquals(20_000L, schedule.downloaded["c"])
        // Levels follow one another; the downloads finished while a's level was made.
        assertEquals(listOf(30_000L, 60_000L, 90_000L), listOf("a", "b", "c").map { schedule.prepared[it] })
        // With nothing else to do, a level starts as soon as its download ends.
        val waiting = PreparationTime.schedule(listOf(job("b", 40_000)), listOf(job("b", 30_000)))
        assertEquals(70_000L, waiting.prepared["b"])
    }

    @Test
    fun finishedSongsTeachThisPhonesSpeed() {
        val before = time.chartMsPerSecond
        // A slow phone: charting takes a second per second of audio.
        repeat(10) {
            time.learnDownload(60_000)
            time.learnLevel(200_000, 200.0)
        }
        assertTrue(time.chartMsPerSecond > before)
        assertEquals(1000f, time.chartMsPerSecond, 50f)
        assertEquals(60_000.0, time.downloadMs.toDouble(), 3_000.0)
        // A tiny clip teaches nothing about charts.
        val rate = time.chartMsPerSecond
        time.learnLevel(1, 5.0)
        assertEquals(rate, time.chartMsPerSecond)
    }
}
