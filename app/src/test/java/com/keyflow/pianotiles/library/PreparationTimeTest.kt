package com.keyflow.pianotiles.library

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
    fun longerSongsTakeLongerToChartAndAKeptDownloadIsSkipped() {
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
        assertEquals(time.estimate(song, downloaded = false), time.remaining(song, 0.0))
        assertEquals(time.chartMs(song), time.remaining(song, OnDeviceLibrary.DOWNLOAD_SHARE))
        val halfCharted = OnDeviceLibrary.DOWNLOAD_SHARE + (1 - OnDeviceLibrary.DOWNLOAD_SHARE) / 2
        assertEquals(time.chartMs(song) / 2.0, time.remaining(song, halfCharted).toDouble(), 2.0)
        assertEquals(0L, time.remaining(song, 1.0))
    }

    @Test
    fun finishedSongsTeachThisPhonesSpeed() {
        val before = time.chartMsPerSecond
        // A slow phone: charting takes a second per second of audio.
        repeat(10) { time.learn(OnDeviceLibrary.Timing(downloadMs = 60_000, chartMs = 200_000, seconds = 200.0)) }
        assertTrue(time.chartMsPerSecond > before)
        assertEquals(1000f, time.chartMsPerSecond, 50f)
        assertEquals(60_000.0, time.downloadMs.toDouble(), 3_000.0)
        // A reused download teaches nothing about downloads, and a tiny clip nothing about charts.
        val download = time.downloadMs
        val rate = time.chartMsPerSecond
        time.learn(OnDeviceLibrary.Timing(downloadMs = null, chartMs = 1, seconds = 5.0))
        assertEquals(download, time.downloadMs)
        assertEquals(rate, time.chartMsPerSecond)
    }
}
