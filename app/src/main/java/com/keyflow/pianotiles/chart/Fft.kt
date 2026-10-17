package com.keyflow.pianotiles.chart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 FFT over [re] and [im], with precomputed twiddles for size [n]. */
internal class Fft(private val n: Int) {
    val re = DoubleArray(n)
    val im = DoubleArray(n)
    private val cosines = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sines = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    private val reversed = IntArray(n) { i ->
        var r = 0
        var x = i
        var bits = Integer.numberOfTrailingZeros(n)
        while (bits-- > 0) {
            r = (r shl 1) or (x and 1)
            x = x shr 1
        }
        r
    }

    fun transform() {
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val stride = n / size
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val c = cosines[k * stride]
                    val s = sines[k * stride]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += size
            }
            size *= 2
        }
    }
}

/** A growable float buffer for streaming analysis. */
internal class FloatList {
    var values = FloatArray(4096)
    var size = 0

    fun add(value: Float) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    fun toArray() = values.copyOf(size)
}
