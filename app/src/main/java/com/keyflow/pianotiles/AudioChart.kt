package com.keyflow.pianotiles

import java.io.BufferedInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.sqrt

/** Streaming mono PCM analysis: bounded memory regardless of song length. */
object AudioChart {
    data class Note(
        val time: Double,
        val lane: Int,
        val kind: String = "tap",
        val duration: Double = 0.0,
        val endLane: Int = lane,
    )

    data class Chart(val duration: Double, val method: String, val notes: List<Note>)

    data class Samples(val energy: DoubleArray, val duration: Double)

    fun readPcm(stream: InputStream): Samples {
        val input = BufferedInputStream(stream, 32768)
        val energy = ArrayList<Double>()
        var sum = 0.0
        var hop = 0
        var samples = 0L
        while (true) {
            val low = input.read()
            if (low < 0) break
            val high = input.read()
            if (high < 0) break
            val value = ((high shl 8) or low).toShort().toInt()
            sum += value.toDouble() * value
            hop++
            samples++
            if (hop == 256) {
                energy.add(sqrt(sum / 256))
                hop = 0
                sum = 0.0
            }
        }
        return Samples(energy.toDoubleArray(), samples / 11025.0)
    }

    fun generate(samples: Samples, id: String): Chart {
        val duration = samples.duration
        require(duration >= 3) { "This audio is too short to create a level." }
        val energy = samples.energy
        val flux = DoubleArray(maxOf(0, energy.size - 1)) { max(0.0, energy[it + 1] - energy[it]) }
        val times = ArrayList<Double>()
        for (i in 3 until flux.size - 3) {
            val time = (i + 1) * 256 / 11025.0
            var sum = 0.0
            val start = maxOf(0, i - 22)
            val end = minOf(flux.lastIndex, i + 22)
            for (j in start..end) sum += flux[j]
            val threshold = max(100.0, sum / (end - start + 1) * 1.55)
            if (time <= 1.5 || time >= duration - .7 || flux[i] <= threshold) continue
            if ((i - 3..i + 3).any { flux[it] > flux[i] }) continue
            if (times.isEmpty() || time - times.last() >= .30) times.add(round(time))
        }
        var method = "energy-onsets"
        if (times.size < duration / 5) {
            times.clear()
            method = "estimated-grid"
            repeat(maxOf(1, ((duration - 2.2) / .6).toInt())) { times.add(round(1.5 + it * .6)) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        val seed =
            ((digest[0].toLong() and 255) shl 24) or
                ((digest[1].toLong() and 255) shl 16) or
                ((digest[2].toLong() and 255) shl 8) or
                (digest[3].toLong() and 255)
        var previous = -1
        val notes = times.mapIndexed { index, time ->
            var lane = (((seed shr (index % 23)) xor (index * 7).toLong()) % 4).toInt()
            if (lane == previous) lane = (lane + 1) % 4
            previous = lane
            Note(time, lane)
        }
        return Chart(round(duration), method, gestures(notes))
    }

    fun gestures(notes: List<Note>): List<Note> {
        val result = ArrayList<Note>()
        var i = 0
        while (i < notes.size) {
            val note = notes[i]
            when {
                i >= 10 && i % 12 == 10 && i + 2 < notes.size -> {
                    result.add(
                        note.copy(kind = "hold", duration = round(notes[i + 2].time - note.time)),
                    )
                    i += 3
                }
                i >= 16 && i % 18 == 16 && i + 2 < notes.size -> {
                    var end = notes[i + 2].lane
                    if (end == note.lane) end = (end + 1) % 4
                    result.add(
                        note.copy(
                            kind = "slide",
                            duration = round(notes[i + 2].time - note.time),
                            endLane = end,
                        ),
                    )
                    i += 3
                }
                else -> {
                    result.add(note)
                    i++
                }
            }
        }
        return result
    }

    private fun round(value: Double) = kotlin.math.round(value * 1000) / 1000
}
