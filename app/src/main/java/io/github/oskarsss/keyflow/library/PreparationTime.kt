package io.github.oskarsss.keyflow.library

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

    /** What is left of a download, from the whole preparation's [fraction]. */
    fun downloadLeft(fraction: Double): Long =
        ((1 - fraction / OnDeviceLibrary.DOWNLOAD_SHARE).coerceIn(0.0, 1.0) * downloadMs).toLong()

    /** What is left of making a level, from the whole preparation's [fraction]. */
    fun chartLeft(song: JSONObject, fraction: Double): Long {
        val download = OnDeviceLibrary.DOWNLOAD_SHARE
        return ((1 - (fraction - download) / (1 - download)).coerceIn(0.0, 1.0) * chartMs(song)).toLong()
    }

    fun learnDownload(ms: Long) {
        prefs.edit().putLong(DOWNLOAD_KEY, blend(downloadMs.toDouble(), ms.coerceIn(2_000, 10 * 60_000).toDouble()).toLong()).apply()
    }

    /** Learns from a level made for a song [seconds] long in [ms]. */
    fun learnLevel(ms: Long, seconds: Double) {
        if (seconds <= 10) return
        val rate = (ms / seconds).coerceIn(20.0, 5_000.0)
        prefs.edit().putFloat(CHART_KEY, blend(chartMsPerSecond.toDouble(), rate).toFloat()).apply()
    }

    // Recent songs count most, since the phone's speed changes with its temperature and battery.
    private fun blend(average: Double, value: Double) = average * .7 + value * .3

    /** A song in one worker's queue and the time that worker still needs for it. */
    class Job(val id: String, val ms: Long)

    /**
     * When each song is playable, downloaded with its quick level, and when its full level is made,
     * in milliseconds from now.
     */
    class Schedule(val downloaded: Map<String, Long>, val prepared: Map<String, Long>)

    companion object {
        /**
         * Both workers run side by side, each through its own queue in order. A level can't be
         * made before its song is downloaded, so a song in [charts] that is still in [downloads]
         * starts no earlier than its download ends. [charts] holds the songs whose level is still
         * to make that are downloaded or in [downloads].
         */
        fun schedule(downloads: List<Job>, charts: List<Job>): Schedule {
            val downloaded = HashMap<String, Long>()
            var clock = 0L
            for (job in downloads) {
                clock += job.ms
                downloaded[job.id] = clock
            }
            val prepared = HashMap<String, Long>()
            clock = 0L
            for (job in charts) {
                clock = maxOf(clock, downloaded[job.id] ?: 0L) + job.ms
                prepared[job.id] = clock
            }
            return Schedule(downloaded, prepared)
        }


        private const val DOWNLOAD_KEY = "preparation.downloadMs"
        private const val CHART_KEY = "preparation.chartMsPerSecond"
        const val DEFAULT_DOWNLOAD_MS = 25_000L
        const val DEFAULT_CHART_MS_PER_SECOND = 240f
        /** A quick level, made right after its song's download; a couple of seconds on a phone. */
        const val QUICK_MS = 3_000L

        /** Songs whose length the playlist doesn't say count as this long. */
        const val TYPICAL_SECONDS = 210.0

        fun seconds(song: JSONObject) = song.optDouble("duration", 0.0).takeIf { it > 0 } ?: TYPICAL_SECONDS
    }
}
