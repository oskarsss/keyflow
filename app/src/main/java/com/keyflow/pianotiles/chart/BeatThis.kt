package com.keyflow.pianotiles.chart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/** The published Beat This! final0 network; all inference stays on the phone. */
class BeatThis(private val context: Context) {
    fun analyze(features: MusicFeatures, progress: (Int) -> Unit = {}): MusicalRhythm {
        val env = OrtEnvironment.getEnvironment()
        val beat = FloatArray(features.frames) { -1000f }
        val downbeat = FloatArray(features.frames) { -1000f }
        val assigned = BooleanArray(features.frames)
        ModelSessions.use(context, ModelSessions.Model("rhythm/beat-this-final0-int8.onnx", MODEL_BYTES)) { (session) ->
            val starts = MusicalRhythm.chunkStarts(features.frames)
            val values = FloatArray(CHUNK * MusicFeatures.BINS)
            for ((chunk, start) in starts.withIndex()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                values.fill(0f)
                val from = maxOf(0, start)
                val until = minOf(features.frames, start + CHUNK)
                if (until > from) features.mel.copyInto(values, (from - start) * MusicFeatures.BINS,
                    from * MusicFeatures.BINS, until * MusicFeatures.BINS)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(values), longArrayOf(1, CHUNK.toLong(), MusicFeatures.BINS.toLong())).use { input ->
                    session.run(mapOf("input_spectrogram" to input)).use { output ->
                        val b = (output.get("beat").get() as OnnxTensor).floatBuffer
                        val d = (output.get("downbeat").get() as OnnxTensor).floatBuffer
                        for (j in BORDER until CHUNK - BORDER) {
                            val index = start + j
                            if (index !in beat.indices || assigned[index]) continue
                            beat[index] = b.get(j)
                            downbeat[index] = d.get(j)
                            assigned[index] = true
                        }
                    }
                }
                progress((chunk + 1) * 100 / starts.size)
            }
        }
        check(assigned.all { it }) { "The rhythm analysis did not cover the whole song." }
        return MusicalRhythm.fromLogits(beat, downbeat)
    }

    companion object {
        const val CHUNK = 1500
        const val BORDER = 6
        private const val MODEL_BYTES = 21_068_518L
    }
}
