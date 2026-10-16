package com.keyflow.pianotiles

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.math.*

/** Temporary PCM for background chart preparation; never needed when starting a level. */
class ChartAudio private constructor(private val file: File, val samples: Long, private val peak: Int) : Closeable {
    val duration get() = samples.toDouble() / RATE
    val windows: Int get() = if (samples <= SAMPLES) 1 else ((samples - SAMPLES + STRIDE - 1) / STRIDE + 1).toInt()

    fun startSample(window: Int) = window.toLong() * STRIDE
    fun startMs(window: Int) = startSample(window) * 1000 / RATE

    data class Input(val mel: FloatArray, val audible: Boolean)

    fun mel(window: Int) = input(window).mel

    fun input(window: Int): Input {
        require(window in 0 until windows)
        val first = startSample(window)
        val available = minOf(SAMPLES.toLong(), (samples - first).coerceAtLeast(0)).toInt()
        val bytes = ByteArray(available * 2)
        RandomAccessFile(file, "r").use { input -> input.seek(first * 2); input.readFully(bytes) }
        val audio = FloatArray(SAMPLES)
        val denominator = peak.coerceAtLeast(1).toFloat()
        for (i in 0 until available) {
            val value = ((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 255)).toShort()
            audio[i] = value / denominator
        }
        val from = if (window > 0) ManiaTokens.HISTORY_STEP * 10 * RATE / 1000 else 0
        val until = minOf(available, ManiaTokens.KEEP_MS * RATE / 1000)
        val power = (from until until).sumOf { audio[it].toDouble() * audio[it] }
        val audible = until > from && power / (until - from) >= .0001 // normalized RMS >= 1%
        return Input(frontend(audio), audible)
    }

    override fun close() { file.delete() }

    companion object {
        const val RATE = 16000
        const val FFT = 1024
        const val HOP = 128
        const val FRAMES = 2048
        const val BINS = 128
        const val SAMPLES = (FRAMES - 1) * HOP
        // 20% history, 20% lookahead; same integer sample stride as the reference pipeline.
        const val STRIDE = 157209
        private val window = DoubleArray(FFT) { .5 - .5 * cos(2 * PI * it / FFT) }
        private class Filter(val first: Int, val weights: FloatArray)
        private val filters = Array(BINS) { band ->
            fun mel(f: Double) = 2595 * log10(1 + f / 700)
            fun hz(m: Double) = 700 * (10.0.pow(m / 2595) - 1)
            val low = mel(20.0)
            val step = (mel(8000.0) - low) / (BINS + 1)
            val left = hz(low + band * step)
            val center = hz(low + (band + 1) * step)
            val right = hz(low + (band + 2) * step)
            val weights = FloatArray(FFT / 2 + 1) { k ->
                val frequency = k * RATE.toDouble() / FFT
                maxOf(0.0, minOf((frequency - left) / (center - left), (right - frequency) / (right - center))).toFloat()
            }
            val first = weights.indexOfFirst { it > 0 }
            val last = weights.indexOfLast { it > 0 }
            Filter(first.coerceAtLeast(0), if (first < 0) FloatArray(0) else weights.copyOfRange(first, last + 1))
        }

        /** Signed 16-bit LE mono from FFmpeg. Decode once and retain a bounded temporary file. */
        fun readPcm(input: InputStream, target: File): ChartAudio {
            var samples = 0L
            var peak = 0
            var low = -1
            try {
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        for (i in 0 until count) {
                            if (low < 0) low = buffer[i].toInt() and 255
                            else {
                                val value = ((buffer[i].toInt() shl 8) or low).toShort().toInt()
                                peak = maxOf(peak, abs(value)); samples++; low = -1
                            }
                        }
                    }
                }
                require(low < 0 && samples >= RATE * 3) { "This audio is too short or incomplete to prepare a chart." }
                return ChartAudio(target, samples, peak)
            } catch (error: Throwable) { target.delete(); throw error }
        }

        /** Published frontend: normalized waveform, reflected Hann STFT, HTK power mel, log1p.
         * Output is channel-first [128, 2048], as required by the exported encoder.
         */
        fun frontend(audio: FloatArray): FloatArray {
            require(audio.size == SAMPLES)
            val fft = AudioChart.Fft(FFT)
            val power = DoubleArray(FFT / 2 + 1)
            val result = FloatArray(BINS * FRAMES)
            for (frame in 0 until FRAMES) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val center = frame * HOP
                for (i in 0 until FFT) {
                    var index = abs(center + i - FFT / 2)
                    if (index >= audio.size) index = 2 * audio.size - 2 - index
                    fft.re[i] = audio[index] * window[i]; fft.im[i] = 0.0
                }
                fft.transform()
                for (k in power.indices) power[k] = fft.re[k] * fft.re[k] + fft.im[k] * fft.im[k]
                for ((band, filter) in filters.withIndex()) {
                    var value = 0.0
                    for (k in filter.weights.indices) value += power[filter.first + k] * filter.weights[k]
                    result[band * FRAMES + frame] = ln1p(value).toFloat()
                }
            }
            return result
        }
    }
}
