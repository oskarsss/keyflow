package io.github.oskarsss.keyflow.chart

import java.io.InputStream
import kotlin.math.*

/** Beat This! frontend, matching upstream LogMelSpect, without holding decoded audio in memory. */
class MusicFeatures(
    val mel: FloatArray,
    val chroma: FloatArray,
    val energy: FloatArray,
    val duration: Double,
) {
    val frames get() = energy.size

    companion object {
        const val RATE = 22050
        const val BINS = 128
        private const val FFT = 1024
        private const val HOP = 441
        private val ROOT_FFT = sqrt(FFT.toDouble())

        private fun mel(hz: Double): Double = if (hz < 1000) hz / (200.0 / 3)
            else 15 + ln(hz / 1000) / (ln(6.4) / 27)
        private fun hz(mel: Double): Double = if (mel < 15) mel * (200.0 / 3)
            else 1000 * exp((mel - 15) * ln(6.4) / 27)

        private class Filter(val first: Int, val weights: FloatArray)
        private val filters = Array(BINS) { band ->
            val low = mel(30.0)
            val step = (mel(11000.0) - low) / (BINS + 1)
            val left = hz(low + band * step)
            val center = hz(low + (band + 1) * step)
            val right = hz(low + (band + 2) * step)
            val values = FloatArray(FFT / 2 + 1) { k ->
                val f = k * RATE.toDouble() / FFT
                max(0.0, min((f - left) / (center - left), (right - f) / (right - center))).toFloat()
            }
            val first = values.indexOfFirst { it > 0 }
            val last = values.indexOfLast { it > 0 }
            Filter(first.coerceAtLeast(0), if (first < 0) FloatArray(0) else values.copyOfRange(first, last + 1))
        }
        private val window = DoubleArray(FFT) { .5 - .5 * cos(2 * PI * it / FFT) }
        private val pitches = IntArray(FFT / 2 + 1) { k ->
            if (k < 3) -1 else (69 + 12 * ln(k * RATE.toDouble() / FFT / 440) / ln(2.0)).roundToInt().mod(12)
        }

        /** Signed little-endian 16-bit mono, exactly [RATE] Hz. STFT is centered with reflection. */
        fun readPcm(stream: InputStream): MusicFeatures {
            val pcm = Pcm(stream)
            val fft = Fft(FFT)
            val spectrum = DoubleArray(FFT / 2 + 1)
            val mel = FloatList()
            val chroma = FloatList()
            val energy = FloatList()
            val pitch = DoubleArray(12)
            var frame = 0
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val center = frame * HOP
                pcm.ensure(center + FFT / 2)
                if (pcm.ended && (pcm.count == 0 || center > pcm.count)) break
                var square = 0.0
                for (i in 0 until FFT) {
                    val x = pcm.at(center + i - FFT / 2)
                    square += x * x
                    fft.re[i] = x * window[i]
                    fft.im[i] = 0.0
                }
                fft.transform()
                pitch.fill(0.0)
                for (k in spectrum.indices) {
                    spectrum[k] = sqrt(fft.re[k] * fft.re[k] + fft.im[k] * fft.im[k]) / ROOT_FFT
                    if (k in 3..140) pitch[pitches[k]] += spectrum[k]
                }
                for (filter in filters) {
                    var value = 0.0
                    for (k in filter.weights.indices) value += spectrum[filter.first + k] * filter.weights[k]
                    mel.add(ln1p(1000 * value).toFloat())
                }
                val norm = sqrt(pitch.sumOf { it * it }).coerceAtLeast(1e-12)
                for (x in pitch) chroma.add((x / norm).toFloat())
                energy.add(sqrt(square / FFT).toFloat())
                frame++
            }
            return MusicFeatures(mel.toArray(), chroma.toArray(), energy.toArray(), pcm.count.toDouble() / RATE)
        }

        private class Pcm(private val input: InputStream) {
            private val ring = DoubleArray(2048)
            // Read in blocks: a call per byte was most of the frontend's time.
            private val bytes = ByteArray(32768)
            private var filled = 0
            private var position = 0
            var count = 0
                private set
            var ended = false
                private set

            fun ensure(index: Int) {
                while (!ended && count <= index) {
                    if (filled - position < 2) {
                        // Keep a byte left over from the last block; a sample can straddle two.
                        val left = filled - position
                        if (left == 1) bytes[0] = bytes[position]
                        filled = left
                        position = 0
                        val read = input.read(bytes, filled, bytes.size - filled)
                        if (read < 0) { ended = true; return }
                        filled += read
                        continue
                    }
                    val a = bytes[position].toInt() and 0xFF
                    val b = bytes[position + 1].toInt()
                    position += 2
                    ring[count % ring.size] = ((b shl 8) or a).toShort() / 32768.0
                    count++
                }
            }

            fun at(index: Int): Double {
                if (count == 0) return 0.0
                var reflected = abs(index)
                if (ended && reflected >= count) reflected = max(0, 2 * count - 2 - reflected)
                return ring[reflected % ring.size]
            }
        }
    }
}
