package com.keyflow.pianotiles.chart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** Human-chart-trained audio-to-actions model. Invoked only by background song preparation. */
class ManiaChartModel(private val context: Context) {
    private val manifest = context.assets.open("chart/model.json").bufferedReader().use { JSONObject(it.readText()) }
    private val vocabulary = context.assets.open("chart/tokenizer.json").bufferedReader().use { ManiaTokens(JSONObject(it.readText())) }

    fun analyze(audio: ChartAudio, detected: MusicalRhythm, progress: (Int) -> Unit = {}): List<Chart.Note> {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Charts must be prepared on a background worker." }
        val rhythm = detected.forGameplay()
        val environment = OrtEnvironment.getEnvironment()
        val events = ArrayList<ManiaTokens.Event>()
        val duration = (audio.duration * 1000).toInt()
        ModelSessions.use(model("chart-encoder-int8.onnx"), model("chart-decoder-int8.onnx")) { (encoder, decoder) ->
            for (window in 0 until audio.windows) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val features = audio.input(window)
                val start = audio.startMs(window) / 1000.0
                val fresh = start + if (window > 0) ManiaTokens.HISTORY_STEP / 100.0 else 0.0
                val rhythmicAudio = features.audible && rhythm.beats.count {
                    it >= fresh && it < minOf(audio.duration, start + ManiaTokens.KEEP_MS / 1000.0)
                } >= 4
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(features.mel), longArrayOf(1, 128, 2048)).use { input ->
                    encoder.run(mapOf("mel" to input)).use { memory ->
                        val prompt = vocabulary.prompt(audio.startMs(window).toInt(), duration, rhythm, events, DIFFICULTY)
                        val predicted = decode(environment, decoder, memory[0] as OnnxTensor, prompt,
                            window > 0, window == audio.windows - 1, rhythmicAudio)
                        vocabulary.append(events, predicted, audio.startMs(window).toInt(), duration, window == audio.windows - 1)
                    }
                }
                progress((window + 1) * 100 / audio.windows)
            }
        }
        return vocabulary.notes(events, audio.duration).also {
            check(it.isNotEmpty()) { "This recording did not produce a playable chart. Try another song." }
        }
    }

    private fun decode(environment: OrtEnvironment, decoder: OrtSession, memory: OnnxTensor,
                       prompt: LongArray, hasHistory: Boolean, final: Boolean, rhythmicAudio: Boolean): List<Int> {
        val generated = ArrayList<Int>()
        val one = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asLongBuffer()
        var previous: OrtSession.Result? = null
        try {
            OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(6, 2, 1, 8, 0, 64)).use { empty ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(prompt), longArrayOf(1, prompt.size.toLong())).use { prefill ->
                    OnnxTensor.createTensor(environment, one, longArrayOf(1, 1)).use { step ->
                        for (index in prompt.size until ManiaTokens.MAX_TOKENS) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val result = decoder.run(mapOf("ids" to if (generated.isEmpty()) prefill else step,
                                "memory" to memory, "past" to (previous?.get(1) as? OnnxTensor ?: empty)))
                            previous?.close(); previous = result
                            val logits = (result[0] as OnnxTensor).floatBuffer
                            val token = vocabulary.choose(logits, prompt, generated, if (hasHistory) ManiaTokens.HISTORY_STEP else 0, rhythmicAudio)
                            if (vocabulary.endsWindow(token, final)) return generated
                            generated.add(token)
                            one.put(0, token.toLong())
                        }
                    }
                }
            }
            error("The generated chart exceeded the model's window limit. Try a lower difficulty.")
        } finally { previous?.close() }
    }

    private fun model(name: String): File {
        val info = manifest.getJSONObject("files").getJSONObject(name)
        val hash = info.getString("sha256")
        val directory = File(context.noBackupFilesDir, "chart-model").apply { mkdirs() }
        val destination = File(directory, "${hash.take(12)}-$name")
        synchronized(LOCK) {
            if (destination.length() != info.getLong("bytes")) {
                val temporary = File(destination.path + ".part")
                try {
                    context.assets.open("chart/$name").use { source -> temporary.outputStream().use(source::copyTo) }
                    check(temporary.length() == info.getLong("bytes")) { "The chart model could not be prepared." }
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally { temporary.delete() }
            }
            directory.listFiles()?.filter { it != destination && it.name.endsWith("-$name") }?.forEach { it.delete() }
        }
        return destination
    }

    companion object {
        const val METHOD = "human-mania-mini-int8-v1"
        const val DIFFICULTY = 1.5
        private val LOCK = Any()
    }
}
