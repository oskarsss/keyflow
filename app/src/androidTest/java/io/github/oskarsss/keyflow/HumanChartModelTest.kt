package io.github.oskarsss.keyflow

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.oskarsss.keyflow.chart.BeatThis
import io.github.oskarsss.keyflow.chart.ChartAudio
import io.github.oskarsss.keyflow.chart.ManiaChartModel
import io.github.oskarsss.keyflow.chart.MusicFeatures
import io.github.oskarsss.keyflow.library.OnDeviceLibrary
import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HumanChartModelTest {
    @Test
    fun humanTrainedModelRunsLocallyWithCachedDecoderAndFollowsOriginalPiano() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File(context.cacheDir, "human-chart-original.wav")
        val pcm = File(context.cacheDir, "human-chart-original.pcm")
        try {
            context.assets.open("demo/first-light.wav").use { source -> audio.outputStream().use(source::copyTo) }
            val runtime = OnDeviceLibrary.get(context).runtime
            val features = runtime.audioSamples(audio, "human-chart-beats", MusicFeatures.RATE, MusicFeatures::readPcm)
            val rhythm = BeatThis(context).analyze(features)
            val started = SystemClock.elapsedRealtime()
            var peakPssKb = Debug.getPss()
            val notes = runtime.audioSamples(audio, "human-chart-pcm", ChartAudio.RATE) { ChartAudio.readPcm(it, pcm) }.use {
                ManiaChartModel(context).analyze(it, rhythm) { peakPssKb = maxOf(peakPssKb, Debug.getPss()) }
            }
            val attacks = (0 until 48).map { 1.5 + it * 60.0 / 84 }
            val errors = notes.map { n -> attacks.minOf { abs(it - n.time) } }
            val aligned = errors.count { it < .100 }.toDouble() / notes.size.coerceAtLeast(1)
            val report = JSONObject().put("milliseconds", SystemClock.elapsedRealtime() - started)
                .put("notes", notes.size).put("alignedFraction100ms", aligned).put("postAnalysisPssKb", Debug.getPss())
                .put("windowBoundaryPeakPssKb", peakPssKb)
                .put("chart", JSONArray(notes.map { JSONObject().put("time", it.time).put("lane", it.lane)
                    .put("kind", it.kind).put("duration", it.duration) }))
            File(context.getExternalFilesDir(null), "human-chart-native-report.json").writeText(report.toString(2))
            android.util.Log.i("HumanChartEvaluation", report.toString())
            assertTrue("No useful chart: $report", notes.size > 20)
            assertTrue("Known piano attacks missed: $aligned", aligned >= .9)
            assertTrue(notes.groupBy { it.time }.values.all { it.size <= 2 })
            assertFalse(pcm.exists())
        } finally { audio.delete(); pcm.delete() }
    }
}
