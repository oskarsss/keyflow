package com.keyflow.pianotiles

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opt-in evaluation of the actual Kotlin frontend/planner on external annotated audio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RhythmEvaluationTest {
    @Test
    fun exportEvaluationFeaturesAndCharts() {
        val path = System.getenv("KEYFLOW_RHYTHM_EVALUATION")
        assumeTrue("Set KEYFLOW_RHYTHM_EVALUATION to a directory of .pcm22/.pcm11 pairs", path != null)
        val root = File(path!!)
        for (input in root.listFiles().orEmpty().filter { it.extension == "pcm22" }.sortedBy { it.name }) {
            val name = input.nameWithoutExtension
            val features = input.inputStream().use(MusicFeatures::readPcm)
            val buffer = ByteBuffer.allocate(features.mel.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            features.mel.forEach(buffer::putFloat)
            File(root, "$name.mel.f32").writeBytes(buffer.array())
            val samples = File(root, "$name.pcm11").inputStream().use(AudioChart::readPcm)
            fun save(chart: AudioChart.Chart, suffix: String) {
                File(root, "$name.$suffix.json").writeText(JSONObject()
                    .put("duration", chart.duration).put("method", chart.method).put("bpm", chart.bpm)
                    .put("beats", JSONArray(chart.beats)).put("downbeats", JSONArray(chart.downbeats))
                    .put("sections", JSONArray(chart.sections.map { JSONObject().put("start", it.start).put("end", it.end).put("family", it.family).put("intensity", it.intensity) }))
                    .put("notes", JSONArray(chart.notes.map { JSONObject().put("time", it.time).put("lane", it.lane)
                        .put("kind", it.kind).put("duration", it.duration).put("endLane", it.endLane) })).toString())
            }
            save(AudioChart.generate(samples, name), "baseline")
            val predictions = File(root, "$name.rhythm.json")
            if (predictions.isFile) {
                val raw = JSONObject(predictions.readText())
                fun times(key: String) = raw.getJSONArray(key).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
                val rhythm = MusicalRhythm(times("beats"), times("downbeats"))
                if (rhythm.beats.size >= 4) save(MusicalChart.generate(samples, features, rhythm, name), "musical")
            }
        }
    }
}
