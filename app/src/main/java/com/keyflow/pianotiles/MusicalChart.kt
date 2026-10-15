package com.keyflow.pianotiles

import kotlin.math.*

/** Beat-synchronous phrase planning. Acoustic timing, repeated motifs, then two-thumb choreography. */
object MusicalChart {
    private class Phrase(val first: Int, val last: Int, val start: Double, val end: Double) {
        var intensity = 0.0
        var family = 0
        var descriptor = DoubleArray(0)
        var candidates = emptyList<Event>()
    }
    private class Event(val slot: Int, val time: Double, val strength: Double, val metric: Double, val beat: Int)
    private val patterns = arrayOf(
        intArrayOf(0, 2, 1, 3, 0, 3, 1, 2),
        intArrayOf(1, 2, 0, 3, 1, 3, 0, 2),
        intArrayOf(0, 3, 1, 2, 1, 3, 0, 2),
        intArrayOf(1, 3, 0, 2, 0, 3, 1, 2),
    )

    fun generate(samples: AudioChart.Samples, features: MusicFeatures, detected: MusicalRhythm, id: String): AudioChart.Chart {
        val rhythm = detected.forGameplay()
        val beats = rhythm.beats
        require(beats.size >= 4)
        val attacks = AudioChart.attacks(samples).filter { it.time in 0.0..samples.duration }
        val downbeatIndices = rhythm.downbeats.map { beats.binarySearch(it) }.filter { it >= 0 }
        val barStarts = if (downbeatIndices.size >= 2) downbeatIndices else (beats.indices step 4).toList()
        val boundaries = (listOf(0) + barStarts.filterIndexed { index, _ -> index % 2 == 0 } + listOf(beats.size)).distinct().sorted()
        val phrases = boundaries.zipWithNext().filter { it.second > it.first }.map { (first, last) ->
            Phrase(first, last, beats[first], if (last < beats.size) beats[last] else minOf(samples.duration, beats.last() + 60 / rhythm.bpm))
        }
        val energy = phrases.map { p -> meanEnergy(features, p.start, p.end) }
        val activity = phrases.map { p -> attacks.count { it.time >= p.start && it.time < p.end }.toDouble() / (p.end - p.start).coerceAtLeast(.1) }
        val loudest = energy.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
        fun rank(values: List<Double>, value: Double): Double {
            val tolerance = maxOf(1e-9, value * .02)
            val lower = values.count { it < value - tolerance }
            val tied = values.count { abs(it - value) <= tolerance }
            return (lower + tied * .5) / maxOf(1, values.size)
        }
        for ((i, phrase) in phrases.withIndex()) {
            val absolute = (energy[i] / loudest).coerceIn(0.0, 1.0)
            phrase.intensity = if (absolute < .07) 0.0 else
                (.5 * rank(energy, energy[i]) + .25 * rank(activity, activity[i]) + .25 * absolute).coerceIn(.12, 1.0)
            phrase.descriptor = descriptor(features, phrase, attacks)
        }
        // A repeated chorus has similar harmony/timbre at corresponding beats, not just equal RMS.
        val families = mutableListOf<Phrase>()
        for (phrase in phrases) {
            val similar = families.indices.filter { index ->
                val other = families[index]
                abs((other.last - other.first) - (phrase.last - phrase.first)) <= 1 &&
                    abs(other.intensity - phrase.intensity) < .32 && cosine(other.descriptor, phrase.descriptor) >= .93
            }.maxByOrNull { cosine(families[it].descriptor, phrase.descriptor) }
            phrase.family = similar ?: families.size.also { families.add(phrase) }
            phrase.candidates = candidates(phrase, beats, attacks, features)
        }
        // The strongest occurrence teaches the same rhythmic motif to quieter repeats too.
        val templates = phrases.groupBy { it.family }.mapValues { (_, members) -> members.maxBy { it.intensity }.candidates }
        val notes = ArrayList<AudioChart.Note>()
        val seed = id.hashCode()
        var lastChord = -100.0
        var lastGesture = -100.0
        for (phrase in phrases) {
            if (phrase.intensity == 0.0) continue
            val bySlot = phrase.candidates.associateBy { it.slot }
            val template = templates.getValue(phrase.family)
            val desired = maxOf(1, ((phrase.last - phrase.first) * (.65 + 1.2 * phrase.intensity)).roundToInt())
            val selected = template.sortedWith(compareByDescending<Event> { it.metric == 1.0 && it.beat in barStarts }
                .thenByDescending { it.strength * (.6 + .4 * it.metric) }).take(desired)
                .mapNotNull { bySlot[it.slot] }.sortedBy { it.time }
            // Selection is performed per phrase, and repeated phrases share its slot mask.
            val kept = mutableListOf<Event>()
            for (event in selected) {
                if (event.time < 1.5 || event.time > samples.duration - .8) continue
                if (kept.isNotEmpty() && event.time - kept.last().time < .205) continue
                if (notes.isNotEmpty() && event.time - (notes.last().time + notes.last().duration) < .205) continue
                kept.add(event)
            }
            val pattern = patterns[(seed + phrase.family).mod(patterns.size)]
            for ((index, event) in kept.withIndex()) {
                val motifIndex = template.indexOfFirst { it.slot == event.slot }
                val lane = pattern[motifIndex.mod(pattern.size)]
                val next = kept.getOrNull(index + 1)?.time ?: phrase.end
                val before = kept.getOrNull(index - 1)?.time ?: notes.lastOrNull()?.let { it.time + it.duration } ?: -100.0
                val period = (beats.getOrElse(event.beat + 1) { beats[event.beat] + 60 / rhythm.bpm } - beats[event.beat]).coerceIn(.25, 1.5)
                val accent = event.metric == 1.0 && event.beat in barStarts && phrase.intensity >= .6 &&
                    event.strength >= 1.1 && event.time - lastChord >= period * 4 &&
                    event.time - before >= .28 && next - event.time >= .3
                val gap = next - event.time
                val naturalLength = minOf(gap - .28, period * 2).coerceAtLeast(0.0)
                val natural = naturalLength >= .65 &&
                    attacks.none { it.time > event.time + .15 && it.time < event.time + naturalLength - .1 && it.strength > 1.25 } &&
                    stable(features, event.time + .12, event.time + naturalLength - .1)
                val room = minOf(phrase.end - event.time - .25, 1.6)
                val longTone = minOf(period * 2, room)
                val shortTone = minOf(period, room)
                val tonalLength = if (event.metric == 1.0 && event.beat in barStarts && event.time >= 8) {
                    when {
                        longTone >= .65 && stable(features, event.time + .12, event.time + longTone - .1) -> longTone
                        shortTone >= .45 && stable(features, event.time + .12, event.time + shortTone - .1) -> shortTone
                        else -> 0.0
                    }
                } else 0.0
                val length = if (natural) naturalLength else tonalLength
                val sustained = length > 0 && event.time - lastGesture > period * 8
                if (sustained) {
                    val slide = length >= 1.1 && (phrase.family + motifIndex).mod(4) == 0
                    notes.add(AudioChart.Note(round(event.time), lane, if (slide) "slide" else "hold", round(length),
                        if (slide) lane xor 1 else lane))
                    lastGesture = event.time
                } else if (accent) {
                    val opposite = if (lane < 2) 2 + (phrase.family + index).mod(2) else (phrase.family + index).mod(2)
                    notes.add(AudioChart.Note(round(event.time), lane))
                    notes.add(AudioChart.Note(round(event.time), opposite))
                    lastChord = event.time
                } else notes.add(AudioChart.Note(round(event.time), lane))
            }
        }
        val phase = downbeatIndices.firstOrNull()?.mod(4) ?: 0
        return AudioChart.Chart(round(samples.duration), "beat-this-phrases-v1", ThumbChart.makePlayable(notes),
            beats.map(::round), phase, rhythm.bpm, rhythm.downbeats.map(::round),
            phrases.map { AudioChart.Section(round(it.start), round(it.end), it.family, it.intensity) })
    }

    private fun candidates(p: Phrase, beats: DoubleArray, attacks: List<AudioChart.Attack>, features: MusicFeatures): List<Event> {
        val from = attacks.binarySearchBy(p.start - .1) { it.time }.let { if (it < 0) -it - 1 else it }
        val nearby = attacks.drop(from).takeWhile { it.time < p.end + .1 }
        fun near(time: Double, radius: Double): AudioChart.Attack? = nearby.filter { abs(it.time - time) <= radius }
            .maxByOrNull { it.strength * (1 - abs(it.time - time) / (radius * 2)) }
        fun alignment(parts: Int): Double {
            var score = 0.0
            for (k in p.first until p.last) {
                val end = beats.getOrElse(k + 1) { p.end }
                for (part in 1 until parts) near(beats[k] + (end - beats[k]) * part / parts, .045)?.let { score += it.strength }
            }
            return score
        }
        // Three-part subdivisions preserve shuffle/swing instead of forcing straight eighths.
        val parts = if (alignment(3) > alignment(4) * 1.15 && alignment(3) > 3) 3 else 4
        val events = ArrayList<Event>()
        for (k in p.first until p.last) {
            val end = beats.getOrElse(k + 1) { p.end }
            val span = end - beats[k]
            if (span !in .22..1.6) continue
            for (part in 0 until parts) {
                val time = beats[k] + span * part / parts
                val attack = near(time, minOf(.065, span * .14))
                val energy = features.energy.getOrElse((time * 50).toInt()) { 0f }
                if (energy < .0008) continue
                if (attack == null || part != 0 && attack.strength < .9) continue
                // The learned pulse chooses the rhythmic role; an audible attack supplies its microtiming.
                val hit = attack.time
                val metric = if (part == 0) 1.0 else if (parts == 4 && part == 2) .8 else .55
                events.add(Event((k - p.first) * 12 + part * (12 / parts), hit, attack.strength, metric, k))
            }
        }
        return events.distinctBy { round(it.time) }
    }

    private fun descriptor(features: MusicFeatures, phrase: Phrase, attacks: List<AudioChart.Attack>): DoubleArray {
        val result = DoubleArray(8 * 12 + 16 + 32)
        for (part in 0 until 8) {
            val start = ((phrase.start + (phrase.end - phrase.start) * part / 8) * 50).toInt().coerceIn(0, features.frames - 1)
            val end = ((phrase.start + (phrase.end - phrase.start) * (part + 1) / 8) * 50).toInt().coerceIn(start + 1, features.frames)
            for (frame in start until end) {
                for (pitch in 0 until 12) result[part * 12 + pitch] += features.chroma[frame * 12 + pitch] / (end - start)
                for (band in 0 until 128) result[96 + band / 8] += features.mel[frame * 128 + band] / (end - start) / 64
            }
        }
        val mean = (96 until 112).sumOf { result[it] } / 16
        for (i in 96 until 112) result[i] = (result[i] - mean) * .3
        for (attack in attacks) if (attack.time >= phrase.start && attack.time < phrase.end) {
            // Soft phase bins keep a 10 ms onset shift from turning a repeated rhythm into a new family.
            val phase = (attack.time - phrase.start) / (phrase.end - phrase.start) * 32
            val bin = floor(phase).toInt()
            val fraction = phase - bin
            val weight = ln1p(attack.strength)
            result[112 + bin.mod(32)] += weight * (1 - fraction)
            result[112 + (bin + 1).mod(32)] += weight * fraction
        }
        val norm = sqrt((112 until 144).sumOf { result[it] * result[it] }).coerceAtLeast(1e-9)
        for (i in 112 until 144) result[i] = result[i] / norm * 1.4
        return result
    }

    private fun meanEnergy(features: MusicFeatures, start: Double, end: Double): Double {
        val a = (start * 50).toInt().coerceIn(0, features.frames - 1)
        val b = (end * 50).toInt().coerceIn(a + 1, features.frames)
        var sum = 0.0
        for (i in a until b) sum += features.energy[i]
        return sum / (b - a)
    }

    /** Sustained harmonic shape plus continuous energy; brief drum peaks are filtered out. */
    private fun stable(features: MusicFeatures, start: Double, end: Double): Boolean {
        if (end <= start) return false
        val harmonic = features.harmonic
        val a = (start * 50).toInt().coerceIn(0, features.frames - 1)
        val referenceEnergy = features.energy[a]
        val harmonicEnergy = harmonic.energy[a]
        if (referenceEnergy < .001 || harmonicEnergy < .00001) return false
        var good = 0
        var meanA = 0.0
        for (i in 0 until 128) meanA += harmonic.mel[a * 128 + i]
        meanA /= 128
        for (part in 0..5) {
            val b = ((start + (end - start) * part / 5) * 50).toInt().coerceIn(0, features.frames - 1)
            // Filtering may bridge a repeated pluck, but a genuine energy valley must still break it.
            if (features.energy[b] < referenceEnergy * .35 || harmonic.energy[b] < harmonicEnergy * .6) continue
            var meanB = 0.0
            for (i in 0 until 128) meanB += harmonic.mel[b * 128 + i]
            meanB /= 128
            var dot = 0.0; var aa = 0.0; var bb = 0.0
            for (i in 0 until 128) {
                val x = harmonic.mel[a * 128 + i] - meanA; val y = harmonic.mel[b * 128 + i] - meanB
                dot += x * y; aa += x * x; bb += y * y
            }
            if (aa * bb > 1e-12 && dot / sqrt(aa * bb) >= .94) good++
        }
        return good >= 5
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa * bb < 1e-12) 0.0 else dot / sqrt(aa * bb)
    }
    private fun round(time: Double) = kotlin.math.round(time * 1000) / 1000
}
