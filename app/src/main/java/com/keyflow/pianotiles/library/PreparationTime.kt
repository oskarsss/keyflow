package com.keyflow.pianotiles.library

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * How long preparing a song takes on this phone, learned from finished ones. The download costs
 * about the same for any song, while the chart model's time grows with the song's length, so
 * each is learned on its own.
 */
class PreparationTime(private val prefs: SharedPreferences) {
    /** One download, including converting the audio. */
    val downloadMs: Long get() = prefs.getLong(DOWNLOAD_KEY, DEFAULT_DOWNLOAD_MS)

    /** Charting time per second of audio. */
    val chartMsPerSecond: Float get() = prefs.getFloat(CHART_KEY, DEFAULT_CHART_MS_PER_SECOND)

    fun chartMs(song: JSONObject): Long = (seconds(song) * chartMsPerSecond).toLong()

    /** The whole preparation, without the download when one is already kept from before. */
    fun estimate(song: JSONObject, downloaded: Boolean): Long = (if (downloaded) 0 else downloadMs) + chartMs(song)

    /** What is left of a song being prepared, from its whole-preparation [fraction]. */
    fun remaining(song: JSONObject, fraction: Double): Long {
        val download = OnDeviceLibrary.DOWNLOAD_SHARE
        return if (fraction < download) ((1 - fraction / download) * downloadMs).toLong() + chartMs(song)
        else ((1 - (fraction - download) / (1 - download)).coerceIn(0.0, 1.0) * chartMs(song)).toLong()
    }

    fun learn(timing: OnDeviceLibrary.Timing) {
        val edit = prefs.edit()
        timing.downloadMs?.let { edit.putLong(DOWNLOAD_KEY, blend(downloadMs.toDouble(), it.coerceIn(2_000, 10 * 60_000).toDouble()).toLong()) }
        if (timing.seconds > 10) {
            val rate = (timing.chartMs / timing.seconds).coerceIn(20.0, 5_000.0)
            edit.putFloat(CHART_KEY, blend(chartMsPerSecond.toDouble(), rate).toFloat())
        }
        edit.apply()
    }

    // Recent songs count most, since the phone's speed changes with its temperature and battery.
    private fun blend(average: Double, value: Double) = average * .7 + value * .3

    companion object {
        private const val DOWNLOAD_KEY = "preparation.downloadMs"
        private const val CHART_KEY = "preparation.chartMsPerSecond"
        const val DEFAULT_DOWNLOAD_MS = 25_000L
        const val DEFAULT_CHART_MS_PER_SECOND = 240f
        /** Songs whose length the playlist doesn't say count as this long. */
        const val TYPICAL_SECONDS = 210.0

        fun seconds(song: JSONObject) = song.optDouble("duration", 0.0).takeIf { it > 0 } ?: TYPICAL_SECONDS
    }
}
