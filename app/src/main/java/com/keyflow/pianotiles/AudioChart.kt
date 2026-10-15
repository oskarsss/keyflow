package com.keyflow.pianotiles

import java.io.BufferedInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Streaming mono PCM analysis with bounded memory, then a beat-synced chart:
 * 1. an onset envelope (full band plus bass) gives the tempo and a dynamic-programming beat track,
 *    so tiles land on the song's beats;
 * 2. loudness and onset density rank every bar from silent to peak: quiet passages thin out or rest
 *    with long holds, loud ones fill in half-beats, accents become chords and drops hit with up to
 *    four tiles at once;
 * 3. lanes follow short patterns chosen per bar, and bars with the same rhythm reuse their pattern,
 *    so a chorus plays the same way each time it returns.
 *
 * Everything is seeded by the song ID: each song has its own character and stays reproducible.
 */
object AudioChart {
    data class Note(
        val time: Double,
        val lane: Int,
        val kind: String = "tap",
        val duration: Double = 0.0,
        val endLane: Int = lane,
    )

    /** [beats] are beat times in seconds; every fourth beat from [barPhase] starts a bar. */
    data class Chart(
        val duration: Double,
        val method: String,
        val notes: List<Note>,
        val beats: List<Double> = emptyList(),
        val barPhase: Int = 0,
    )

    /** RMS per hop for the full band and for the bass below about 150 Hz. */
    class Samples(
        val energy: DoubleArray,
        val duration: Double,
        val bass: DoubleArray = DoubleArray(energy.size),
    )

    const val RATE = 11025
    private const val HOP = 256
    private const val FRAME = HOP.toDouble() / RATE
    private val LOWPASS = 1 - exp(-2 * Math.PI * 150 / RATE)

    private const val SILENT = 0
    private const val CALM = 1
    private const val MID = 2
    private const val HIGH = 3
    private const val PEAK = 4

    /** Shortest gap between two separate taps, in seconds. */
    private const val MIN_GAP = .19

    fun readPcm(stream: InputStream): Samples {
        val input = BufferedInputStream(stream, 32768)
        val energy = ArrayList<Double>()
        val bass = ArrayList<Double>()
        var sum = 0.0
        var lowSum = 0.0
        var low = 0.0
        var hop = 0
        var samples = 0L
        while (true) {
            val first = input.read()
            if (first < 0) break
            val second = input.read()
            if (second < 0) break
            val value = ((second shl 8) or first).toShort().toDouble()
            low += LOWPASS * (value - low)
            sum += value * value
            lowSum += low * low
            hop++
            samples++
            if (hop == HOP) {
                energy.add(sqrt(sum / HOP))
                bass.add(sqrt(lowSum / HOP))
                hop = 0
                sum = 0.0
                lowSum = 0.0
            }
        }
        return Samples(energy.toDoubleArray(), samples / RATE.toDouble(), bass.toDoubleArray())
    }

    fun generate(samples: Samples, id: String): Chart {
        val duration = samples.duration
        require(duration >= 3) { "This audio is too short to create a level." }
        val random = Random(seed(id))
        val n = samples.energy.size
        val onset = onsets(samples)
        val period = tempo(onset)
        val beatFrames = if (n > 4 * period) track(onset, period) else IntArray(0)
        if (beatFrames.size < duration / 4) return grid(duration, random)
        val beats = beatFrames.map { it * FRAME }
        val beatSeconds = period * FRAME

        // ---- Song character: every song leans on different patterns and gestures.
        val bias = random.nextDouble(-.25, .25)
        val holdAffinity = random.nextDouble(.6, 1.4)
        val slideAffinity = random.nextDouble(.5, 1.5)
        val weights = DoubleArray(PATTERNS) { .25 + random.nextDouble() }

        // ---- How intense each beat is, smoothed into bars.
        val loudness = smooth(samples.energy, (1.2 / FRAME).toInt())
        val busy = smooth(onset, (2.0 / FRAME).toInt())
        val loudRank = ranks(loudness)
        val busyRank = ranks(busy)
        val loudPeak = max(percentile(loudness, .95), 1.0)
        val local = smooth(onset, (1.5 / FRAME).toInt())
        fun peak(frame: Int): Double {
            var best = 0.0
            for (f in maxOf(0, frame - 2)..minOf(n - 1, frame + 2)) best = max(best, onset[f])
            return best
        }
        fun strength(frame: Int) = peak(frame) / max(local[frame.coerceIn(0, n - 1)], .35)

        val phase = (0 until 4).maxByOrNull { p ->
            var sum = 0.0
            for (k in p until beatFrames.size step 4) sum += peak(beatFrames[k])
            sum
        } ?: 0
        fun inBar(k: Int) = (k - phase).mod(4)
        fun barOf(k: Int) = Math.floorDiv(k - phase, 4)

        val beatScore = DoubleArray(beatFrames.size) { k ->
            val f = beatFrames[k].coerceIn(0, n - 1)
            .5 * loudRank[f] + .25 * busyRank[f] + .25 * (loudness[f] / loudPeak).coerceIn(0.0, 1.0)
        }
        val silent = BooleanArray(beatFrames.size) { k ->
            loudness[beatFrames[k].coerceIn(0, n - 1)] < loudPeak * .07
        }
        // Bars are ranked against the rest of the song, so every song gets quiet and loud parts.
        val groups = beatFrames.indices.groupBy(::barOf).toSortedMap()
        val audible = groups.filterValues { members -> members.count { silent[it] } < 3 }.keys
        val raw = groups.mapValues { (_, members) -> members.sumOf { beatScore[it] } / members.size }
        // A median of three removes one-bar blips but keeps sudden drops sharp.
        val score = raw.mapValues { (bar, x) ->
            val before = if (bar - 1 in audible) raw.getValue(bar - 1) else x
            val after = if (bar + 1 in audible) raw.getValue(bar + 1) else x
            maxOf(minOf(before, x), minOf(maxOf(before, x), after))
        }
        val order = audible.sortedBy { score[it] }
        val bars = HashMap<Int, Int>()
        for (bar in groups.keys) if (bar !in audible) bars[bar] = SILENT
        for ((index, bar) in order.withIndex()) {
            val members = groups.getValue(bar)
            val rank = index / maxOf(1.0, order.size - 1.0) - bias * .3
            val ratio = members.sumOf { loudness[beatFrames[it].coerceIn(0, n - 1)] } /
                members.size / loudPeak
            var level =
                when {
                    rank < .2 -> CALM
                    rank < .5 -> MID
                    rank < .8 -> HIGH
                    else -> PEAK
                }
            if (ratio >= .75) level = maxOf(level, MID)
            if (ratio < .28) level = minOf(level, CALM)
            bars[bar] = level
        }
        fun level(k: Int) = if (silent[k]) SILENT else bars[barOf(k)] ?: SILENT

        // ---- Which beats and half-beats get a tile.
        class Event(val time: Double, val frame: Int, val beat: Int, val half: Boolean) {
            var size = 1
            var kind = "tap"
            var duration = 0.0
            var skip = false
        }
        val events = ArrayList<Event>()
        val loose = bias * 1.2
        for (k in beatFrames.indices) {
            val level = level(k)
            if (level == SILENT) continue
            val f = beatFrames[k]
            val next = if (k + 1 < beatFrames.size) beatFrames[k + 1] else f + period.roundToInt()
            val spacing = (next - f) * FRAME
            val s = strength(f)
            val position = inBar(k)
            val keep =
                when (level) {
                    CALM -> position == 0 || (position == 2 && s > 2.4 + loose)
                    MID -> position % 2 == 0 || s > 1.2 + loose
                    else -> true
                }
            if (keep) events.add(Event(f * FRAME, f, k, false))
            // Half-beats follow the music: only where a real onset sits between two beats.
            var hf = (f + next) / 2
            for (c in hf - 2..hf + 2) if (c in 0 until n && onset[c] > onset[hf.coerceIn(0, n - 1)]) hf = c
            if (hf !in 0 until n) continue
            val hs = strength(hf)
            val gap = minOf(hf - f, next - hf) * FRAME
            val half =
                gap >= MIN_GAP &&
                    when (level) {
                        MID -> spacing >= .5 && hs > 2.6 + loose
                        HIGH -> hs > 1.6 + loose
                        PEAK -> hs > 1.05 + loose
                        else -> false
                    }
            if (half) events.add(Event(hf * FRAME, hf, k, true))
        }
        events.retainAll { it.time >= 1.5 && it.time <= duration - .8 }

        // ---- Chords on accents; the first bar of a sudden rise (a drop) hits hardest.
        var lastChord = -100.0
        for ((i, e) in events.withIndex()) {
            if (e.half || e.skip) continue
            val level = level(e.beat)
            val position = inBar(e.beat)
            val bar = barOf(e.beat)
            val rise = (bars[bar] ?: 0) - (bars[bar - 1] ?: bars[bar] ?: 0)
            val firstOfBar = i == 0 || barOf(events[i - 1].beat) != bar
            val accent = peak(e.frame)
            val size =
                when {
                    level >= HIGH && rise >= 2 && position == 0 && firstOfBar ->
                        if (level == PEAK) 4 else 3
                    // The first bar of a peak is a drop: everything at once.
                    level == PEAK && rise >= 1 && position == 0 && firstOfBar ->
                        if ((bars[bar - 2] ?: PEAK) <= MID || random.nextDouble() < .4) 4 else 3
                    level == PEAK && position == 0 && accent > 3.0 ->
                        if (random.nextDouble() < .45) 3 else 2
                    level >= HIGH && position % 2 == 0 && accent > 2.6 -> 2
                    level == MID && position == 0 && accent > 3.4 && random.nextDouble() < .45 -> 2
                    else -> 1
                }
            if (size == 1) continue
            val cooldown = beatSeconds * (if (size >= 3) 3.5 else 1.9)
            if (e.time - lastChord < cooldown && rise < 1) continue
            val before = events.getOrNull(i - 1)
            if (before != null && e.time - before.time < .3) {
                if (before.half) before.skip = true else continue
            }
            for (j in i + 1 until events.size) {
                if (events[j].time - e.time >= .3) break
                events[j].skip = true
            }
            e.size = size
            lastChord = e.time
        }
        events.removeAll { it.skip }

        // ---- Holds on sustained or calm stretches, holds and slides to close phrases.
        val sustain = smooth(samples.energy, 4)
        for ((i, e) in events.withIndex()) {
            if (e.skip || e.size > 1) continue
            val level = level(e.beat)
            val bar = barOf(e.beat)
            val position = inBar(e.beat)
            var until = events.getOrNull(i + 1)?.time ?: (duration - .8)
            var phraseEnd = false
            if (
                level >= MID && !e.half && position == 2 && bar.mod(2) == 1 &&
                    random.nextDouble() <
                        (if (bar.mod(4) == 3) .7 else .3) * (holdAffinity + slideAffinity) / 2
            ) {
                // Close a four-bar phrase: swallow the rest of the bar into one gesture.
                val downbeat = beatFrames.indices.firstOrNull { it > e.beat && inBar(it) == 0 }
                if (downbeat != null) {
                    val target = beatFrames[downbeat] * FRAME
                    var j = i + 1
                    while (j < events.size && events[j].time < target - .01) {
                        if (events[j].size > 1) break
                        j++
                    }
                    if (j == events.size || events[j].time >= target - .01) {
                        for (m in i + 1 until j) events[m].skip = true
                        until = events.getOrNull(j)?.time ?: (duration - .8)
                        phraseEnd = true
                    }
                }
            }
            val gap = until - e.time
            val release = max(.18, beatSeconds * .45)
            val length = minOf(gap - release, beatSeconds * 4)
            if (length < max(.35, beatSeconds * .9)) continue
            val held = loudness.getOrElse(e.frame) { 0.0 }
            val end = minOf(n - 1, (until / FRAME).toInt())
            var sustained = 0
            var total = 0
            for (f in e.frame until end step 4) {
                total++
                if (sustain[f] >= held * .5) sustained++
            }
            val natural = gap >= beatSeconds * 1.9 && total > 0 && sustained >= total * .7
            val calm = level == CALM && gap >= beatSeconds * 1.9
            val chance =
                when {
                    phraseEnd -> 1.0
                    calm -> .6 * holdAffinity
                    natural -> .75
                    else -> 0.0
                }
            if (random.nextDouble() >= chance) continue
            e.duration = round(length)
            e.kind =
                if (level >= MID && random.nextDouble() < .45 * slideAffinity) "slide" else "hold"
        }
        events.removeAll { it.skip }

        // ---- Lanes: per-bar patterns; bars with the same rhythm replay the same pattern.
        val signatures = HashMap<Int, Int>()
        for (e in events) {
            val bar = barOf(e.beat)
            val slot = inBar(e.beat) * 2 + if (e.half) 1 else 0
            signatures[bar] = (signatures[bar] ?: (level(e.beat) shl 24)) or (1 shl slot) or
                ((e.size - 1) shl (8 + slot * 2))
        }
        val library = HashMap<Int, IntArray>()
        val notes = ArrayList<Note>()
        var previous = -1
        var currentBar = Int.MIN_VALUE
        var step = 0
        var sequence = IntArray(0)
        for (e in events) {
            val bar = barOf(e.beat)
            if (bar != currentBar) {
                currentBar = bar
                step = 0
                sequence = library.getOrPut(signatures[bar] ?: 0) { pattern(random, weights) }
            }
            var lane = sequence[step++ % sequence.size]
            if (lane == previous) lane = (lane + if (lane == 3) -1 else 1)
            val time = round(e.time)
            when {
                e.size > 1 -> {
                    for (l in chord(lane, e.size, random)) notes.add(Note(time, l))
                    previous = lane
                }
                e.kind == "slide" -> {
                    val far = 2 + random.nextInt(2)
                    val endLane = if (lane <= 1) minOf(3, lane + far) else maxOf(0, lane - far)
                    notes.add(Note(time, lane, "slide", e.duration, endLane))
                    previous = endLane
                }
                else -> {
                    notes.add(Note(time, lane, e.kind, e.duration))
                    previous = lane
                }
            }
        }
        return Chart(
            round(duration),
            "beat-grid",
            notes.sortedWith(compareBy({ it.time }, { it.lane })),
            beats.filter { it <= duration }.map(::round),
            phase,
        )
    }

    // ------------------------------------------------------------------ analysis

    /** Positive loudness change (full band plus bass), with the local mean removed. */
    private fun onsets(samples: Samples): DoubleArray {
        val n = samples.energy.size
        val raw = DoubleArray(n)
        var loud = 0.0
        var bass = 0.0
        for (i in 0 until n) {
            val l = ln(1 + samples.energy[i] / 64)
            val b = ln(1 + samples.bass.getOrElse(i) { 0.0 } / 64)
            if (i > 0) raw[i] = max(0.0, l - loud) + .8 * max(0.0, b - bass)
            loud = l
            bass = b
        }
        val mean = smooth(raw, 16)
        for (i in 0 until n) raw[i] = max(0.0, raw[i] - mean[i])
        var square = 0.0
        for (value in raw) square += value * value
        val deviation = sqrt(square / maxOf(1, n))
        if (deviation > 1e-9) for (i in 0 until n) raw[i] /= deviation
        return raw
    }

    /** Beat period in frames, from autocorrelation weighted toward 120 BPM. */
    private fun tempo(onset: DoubleArray): Double {
        val shortest = (60.0 / 190 / FRAME).toInt()
        val longest = (60.0 / 65 / FRAME).toInt() + 1
        val n = onset.size
        if (n < longest * 4) return 60.0 / 120 / FRAME
        val correlation = DoubleArray(longest + 2)
        for (lag in shortest - 1..longest + 1) {
            var sum = 0.0
            for (i in 0 until n - lag) sum += onset[i] * onset[i + lag]
            correlation[lag] = sum / (n - lag)
        }
        var best = shortest
        var bestScore = -1.0
        for (lag in shortest..longest) {
            val octaves = ln(60.0 / (lag * FRAME) / 120) / ln(2.0)
            val score = correlation[lag] * exp(-.5 * octaves * octaves)
            if (score > bestScore) {
                bestScore = score
                best = lag
            }
        }
        val a = correlation[best - 1]
        val b = correlation[best]
        val c = correlation[best + 1]
        val curve = a - 2 * b + c
        val shift = if (curve < 0) ((a - c) / (2 * curve)).coerceIn(-.5, .5) else 0.0
        return best + shift
    }

    /** Dynamic-programming beat tracking (Ellis 2007): strong onsets at a steady spacing. */
    private fun track(onset: DoubleArray, period: Double): IntArray {
        val n = onset.size
        val shortest = maxOf(1, (period / 2).roundToInt())
        val longest = (period * 2).roundToInt()
        val penalty = DoubleArray(longest + 1) { d ->
            if (d < shortest) 0.0 else ln(d / period).let { 100 * it * it }
        }
        val score = DoubleArray(n)
        val back = IntArray(n) { -1 }
        for (t in 0 until n) {
            var best = 0.0
            var from = -1
            for (d in shortest..longest) {
                val p = t - d
                if (p < 0) break
                val s = score[p] - penalty[d]
                if (s > best) {
                    best = s
                    from = p
                }
            }
            score[t] = onset[t] + best
            back[t] = from
        }
        var last = maxOf(0, n - longest)
        for (t in last until n) if (score[t] > score[last]) last = t
        val result = ArrayList<Int>()
        var t = last
        while (t >= 0) {
            result.add(t)
            t = back[t]
        }
        return result.reversed().toIntArray()
    }

    private fun smooth(values: DoubleArray, radius: Int): DoubleArray {
        val sums = DoubleArray(values.size + 1)
        for (i in values.indices) sums[i + 1] = sums[i] + values[i]
        return DoubleArray(values.size) {
            val from = maxOf(0, it - radius)
            val to = minOf(values.size, it + radius + 1)
            (sums[to] - sums[from]) / (to - from)
        }
    }

    /** Percentile rank (0..1) of every value within the song. */
    private fun ranks(values: DoubleArray): DoubleArray {
        val sorted = values.copyOf().apply { sort() }
        return DoubleArray(values.size) {
            var index = java.util.Arrays.binarySearch(sorted, values[it])
            if (index < 0) index = -index - 1
            index / maxOf(1.0, sorted.size - 1.0)
        }
    }

    private fun percentile(values: DoubleArray, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.copyOf().apply { sort() }
        return sorted[((sorted.size - 1) * fraction).toInt()]
    }

    // ------------------------------------------------------------------ patterns

    private const val PATTERNS = 6

    /** Eight lanes for one bar: walks, zigzags, trills, stairs, mirrors or free jumps. */
    private fun pattern(random: Random, weights: DoubleArray): IntArray {
        var pick = random.nextDouble() * weights.sum()
        var kind = 0
        while (kind < PATTERNS - 1 && pick >= weights[kind]) pick -= weights[kind++]
        val start = random.nextInt(4)
        val flip = random.nextBoolean()
        val lanes =
            IntArray(8) { step ->
                val lane =
                    when (kind) {
                        0 -> intArrayOf(0, 1, 2, 3, 2, 1)[(start + step) % 6]
                        1 -> intArrayOf(0, 2, 1, 3)[(start + step) % 4]
                        2 -> if (step % 2 == 0) start else (start + 1 + start % 2 + 4) % 4
                        3 -> (start + step) % 4
                        4 -> ((start + step / 2) % 4).let { if (step % 2 == 0) it else 3 - it }
                        else -> random.nextInt(4)
                    }
                if (flip) 3 - lane else lane
            }
        for (i in 1 until lanes.size) if (lanes[i] == lanes[i - 1]) lanes[i] = (lanes[i] + 2) % 4
        return lanes
    }

    private fun chord(lane: Int, size: Int, random: Random): List<Int> =
        when (size) {
            2 -> {
                val other =
                    if (random.nextBoolean()) (lane + 2) % 4
                    else if (lane == 0 || (lane != 3 && random.nextBoolean())) lane + 1
                    else lane - 1
                listOf(lane, other)
            }
            3 -> {
                val out = (0..3).filter { it != lane }.random(random)
                (0..3).filter { it != out }
            }
            else -> listOf(0, 1, 2, 3)
        }

    /** Arrhythmic or very short audio: an even grid. */
    private fun grid(duration: Double, random: Random): Chart {
        val lanes = pattern(random, DoubleArray(PATTERNS) { 1.0 })
        val notes =
            (0 until maxOf(1, ((duration - 2.3) / .6).toInt())).map {
                Note(round(1.5 + it * .6), lanes[it % lanes.size])
            }
        return Chart(round(duration), "estimated-grid", notes)
    }

    private fun seed(id: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        return ((digest[0].toInt() and 255) shl 24) or ((digest[1].toInt() and 255) shl 16) or
            ((digest[2].toInt() and 255) shl 8) or (digest[3].toInt() and 255)
    }

    private fun round(value: Double) = kotlin.math.round(value * 1000) / 1000
}
