package com.keyflow.pianotiles

import java.io.BufferedInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Streaming mono PCM analysis with bounded memory, then a chart whose tiles sit on the song's own
 * note onsets. Timing detection is separate from gameplay pattern selection:
 * 1. a log-frequency spectrogram feeds SuperFlux onset detection (Böck & Widmer, DAFx 2013):
 *    spectral flux against a frequency max-filtered earlier frame, then adaptive peak picking.
 *    Tiles follow detected attacks rather than a fixed beat grid;
 * 2. the same onset envelope gives the tempo and a dynamic-programming beat track (Ellis 2007).
 *    Beats are only used for structure: bars, metrical weight and the beat lines on screen;
 * 3. bars are ranked from calm to peak by loudness and activity. Each level allows a different
 *    number of tiles per beat, filled with the strongest onsets, preferring those on beats. Quiet
 *    passages thin out or rest with long holds; accents become chords and drops use all lanes;
 * 4. lanes follow the pitch of each new note (up moves right, down moves left), so a repeated
 *    melody repeats its lane pattern. Same-pitch notes fall back to per-bar patterns.
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
        val bpm: Double = 0.0,
        val downbeats: List<Double> = emptyList(),
        val sections: List<Section> = emptyList(),
    )

    data class Section(val start: Double, val end: Double, val family: Int, val intensity: Double)

    /**
     * [energy] is RMS per [HOP] samples. [flux] is the SuperFlux onset envelope and [pitch] the
     * log-frequency band where new energy appeared, both per [STEP] samples.
     */
    class Samples(
        val energy: DoubleArray,
        val duration: Double,
        val flux: FloatArray = FloatArray(0),
        val pitch: FloatArray = FloatArray(0),
    )

    const val RATE = 11025
    private const val HOP = 256
    private const val FRAME = HOP.toDouble() / RATE
    private const val SIZE = 512
    private const val STEP = 128
    private const val TICK = STEP.toDouble() / RATE

    /** Frame t covers the [SIZE] samples that end at (t + 1)·[STEP]; its time is the window centre. */
    private fun tickTime(t: Int) = ((t + 1) * STEP - SIZE / 2) / RATE.toDouble()

    private const val SILENT = 0
    private const val CALM = 1
    private const val MID = 2
    private const val HIGH = 3
    private const val PEAK = 4

    /** Shortest gap between two separate tiles, in seconds. */
    private const val MIN_GAP = .17

    // ------------------------------------------------------------------ streaming analysis

    /** Log-spaced bands, about a semitone wide once bins are narrow enough, from 43 Hz to 5 kHz. */
    private val BANDS: List<IntRange> = run {
        val bands = ArrayList<IntRange>()
        var lo = 2
        while (lo < 233) {
            val hi = maxOf(lo + 1, (lo * Math.pow(2.0, 1 / 12.0)).roundToInt())
            bands.add(lo until minOf(hi, 233))
            lo = hi
        }
        bands
    }

    /** Bands from about 150 Hz up describe the pitch of a new note; kick drums sit below. */
    private val MELODIC = BANDS.indexOfFirst { it.first >= 7 }

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

    internal class FloatList {
        var values = FloatArray(4096)
        var size = 0

        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun toArray() = values.copyOf(size)
    }

    fun readPcm(stream: InputStream): Samples {
        val input = BufferedInputStream(stream, 32768)
        val energy = ArrayList<Double>()
        val flux = FloatList()
        val pitch = FloatList()
        val window = DoubleArray(SIZE) { .5 - .5 * cos(2 * PI * it / SIZE) }
        val ring = DoubleArray(SIZE)
        val fft = Fft(SIZE)
        // Log band spectra of the last three frames; flux compares against two frames back.
        val history = Array(3) { DoubleArray(BANDS.size) }
        var frames = 0
        var sum = 0.0
        var hop = 0
        var samples = 0L
        while (true) {
            val first = input.read()
            if (first < 0) break
            val second = input.read()
            if (second < 0) break
            val value = ((second shl 8) or first).toShort() / 32768.0
            ring[(samples % SIZE).toInt()] = value
            sum += value * value
            samples++
            if (++hop == HOP) {
                energy.add(sqrt(sum / HOP) * 32768)
                hop = 0
                sum = 0.0
            }
            if (samples % STEP != 0L || samples < SIZE) continue
            val oldest = (samples % SIZE).toInt()
            for (i in 0 until SIZE) {
                fft.re[i] = ring[(oldest + i) % SIZE] * window[i]
                fft.im[i] = 0.0
            }
            fft.transform()
            val current = history[frames % 3]
            for ((b, range) in BANDS.withIndex()) {
                var magnitude = 0.0
                for (k in range) magnitude += sqrt(fft.re[k] * fft.re[k] + fft.im[k] * fft.im[k])
                current[b] = ln(1 + magnitude)
            }
            var total = 0.0
            var weighted = 0.0
            var melodic = 0.0
            if (frames >= 2) {
                val earlier = history[(frames - 2) % 3]
                for (b in BANDS.indices) {
                    var reference = earlier[b]
                    if (b > 0) reference = max(reference, earlier[b - 1])
                    if (b + 1 < BANDS.size) reference = max(reference, earlier[b + 1])
                    val rise = current[b] - reference
                    if (rise <= 0) continue
                    total += rise
                    if (b >= MELODIC) {
                        weighted += rise * b
                        melodic += rise
                    }
                }
            }
            flux.add(total.toFloat())
            pitch.add(if (melodic > 1e-6) (weighted / melodic).toFloat() else -1f)
            frames++
        }
        // The first frames have no full window yet; pad so frame t lines up with tickTime(t).
        val lead = SIZE / STEP - 1
        val fluxValues = FloatArray(lead) + flux.toArray()
        val pitchValues = FloatArray(lead) { -1f } + pitch.toArray()
        return Samples(energy.toDoubleArray(), samples / RATE.toDouble(), fluxValues, pitchValues)
    }

    // ------------------------------------------------------------------ chart

    private class Onset(val tick: Int, val time: Double, val strength: Double, val pitch: Float) {
        var level = 0
        var beat = 0
        var metric = 0.0
        var score = 0.0
        var size = 1
        var kind = "tap"
        var duration = 0.0
    }

    fun generate(samples: Samples, id: String): Chart {
        val duration = samples.duration
        require(duration >= 3) { "This audio is too short to create a level." }
        val random = Random(seed(id))
        val n = samples.flux.size
        if (n < 64) return grid(duration, random)
        val envelope = envelope(samples.flux)
        val period = tempo(envelope)
        val beatTicks = if (n > 4 * period) track(envelope, period) else IntArray(0)
        if (beatTicks.size < duration / 4) return grid(duration, random)
        val beats = beatTicks.map(::tickTime)
        val beatSeconds = period * TICK

        // ---- Song character: every song leans on different patterns and gestures.
        val bias = random.nextDouble(-.12, .12)
        val holdAffinity = random.nextDouble(.6, 1.4)
        val slideAffinity = random.nextDouble(.5, 1.5)
        val weights = DoubleArray(PATTERNS) { .25 + random.nextDouble() }

        // ---- Bars ranked from calm to peak by loudness and onset activity.
        val loudness = smooth(samples.energy, (1.2 / FRAME).toInt())
        val busy = smooth(envelope, (2.0 / TICK).toInt())
        val loudRank = ranks(loudness)
        val busyRank = ranks(busy)
        val loudPeak = max(percentile(loudness, .95), 1.0)
        fun loudAt(time: Double) = loudness[(time / FRAME).toInt().coerceIn(0, loudness.size - 1)]
        fun loudRankAt(time: Double) = loudRank[(time / FRAME).toInt().coerceIn(0, loudness.size - 1)]

        val phase = (0 until 4).maxByOrNull { p ->
            var sum = 0.0
            for (k in p until beatTicks.size step 4) sum += peak(envelope, beatTicks[k], 2)
            sum
        } ?: 0
        fun inBar(k: Int) = (k - phase).mod(4)
        fun barOf(k: Int) = Math.floorDiv(k - phase, 4)

        val beatScore = DoubleArray(beatTicks.size) { k ->
            val time = beats[k]
            .5 * loudRankAt(time) + .25 * busyRank[beatTicks[k].coerceIn(0, n - 1)] +
                .25 * (loudAt(time) / loudPeak).coerceIn(0.0, 1.0)
        }
        val silent = BooleanArray(beatTicks.size) { k -> loudAt(beats[k]) < loudPeak * .07 }
        val groups = beatTicks.indices.groupBy(::barOf).toSortedMap()
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
            val rank = index / maxOf(1.0, order.size - 1.0) - bias
            val ratio = members.sumOf { loudAt(beats[it]) } / members.size / loudPeak
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

        // ---- Onsets: keep SuperFlux peak times; beats only label their metrical position.
        val local = smooth(samples.flux, (1.5 / TICK).toInt())
        val onsets = ArrayList<Onset>()
        for (t in pick(samples.flux)) {
            val time = tickTime(t)
            if (time < 1.5 || time > duration - .8) continue
            var k = java.util.Arrays.binarySearch(beatTicks, t)
            if (k < 0) k = -k - 2
            k = k.coerceIn(0, beatTicks.size - 1)
            // Snap to the closer beat when an onset sits just before one.
            if (k + 1 < beatTicks.size && beatTicks[k + 1] - t < (beatTicks[k + 1] - beatTicks[k]) * .12) k++
            val span = if (k + 1 < beatTicks.size) beatTicks[k + 1] - beatTicks[k] else period.roundToInt()
            val position = ((t - beatTicks[k]).toDouble() / span).coerceIn(-.5, 1.0)
            val strength = samples.flux[t] / max(local[t], 1e-3)
            val onset = Onset(t, time, strength, samples.pitch.getOrElse(t) { -1f })
            onset.beat = k
            onset.level = if (silent[k]) SILENT else bars[barOf(k)] ?: SILENT
            onset.metric =
                when {
                    kotlin.math.abs(position) < .1 -> 1.0
                    kotlin.math.abs(position - .5) < .1 -> .7
                    kotlin.math.abs(position - .25) < .08 || kotlin.math.abs(position - .75) < .08 -> .45
                    else -> .3
                }
            onset.score = strength * (.55 + .45 * onset.metric)
            if (onset.level != SILENT) onsets.add(onset)
        }

        // ---- Each level allows a number of tiles per beat, filled with the strongest onsets.
        val chosen = java.util.TreeMap<Double, Onset>()
        fun fits(time: Double, gap: Double): Boolean {
            val before = chosen.floorKey(time)
            val after = chosen.ceilingKey(time)
            return (before == null || time - before >= gap) && (after == null || after - time >= gap)
        }
        for ((bar, members) in onsets.groupBy { barOf(it.beat) }) {
            val level = bars[bar] ?: continue
            val beatsInBar = groups[bar]?.size ?: 4
            val (perBeat, gap, floor) =
                when (level) {
                    CALM -> Triple(.5, max(.45, beatSeconds * .9), 1.3)
                    MID -> Triple(1.1, max(.24, beatSeconds * .45), 1.0)
                    HIGH -> Triple(1.6, max(.2, beatSeconds * .3), .85)
                    else -> Triple(2.1, max(MIN_GAP, beatSeconds * .24), .75)
                }
            val limit = (perBeat * beatsInBar + bias * 2).roundToInt().coerceAtLeast(1)
            var taken = 0
            for (onset in members.sortedByDescending { it.score }) {
                if (taken >= limit) break
                if (onset.strength < floor) continue
                if (!fits(onset.time, gap)) continue
                chosen[onset.time] = onset
                taken++
            }
        }
        val events = chosen.values.toMutableList()

        // ---- Chords on accents; the first bar of a sudden rise (a drop) hits hardest.
        var lastChord = -100.0
        val accents = events.map { it.strength }.sorted()
        val strong = accents.getOrElse((accents.size * .85).toInt()) { 9.0 }
        for ((i, e) in events.withIndex()) {
            val bar = barOf(e.beat)
            val position = inBar(e.beat)
            val onBeat = e.metric >= 1.0
            val rise = (bars[bar] ?: 0) - (bars[bar - 1] ?: bars[bar] ?: 0)
            val firstOfBar = i == 0 || barOf(events[i - 1].beat) != bar
            val size =
                when {
                    // A downbeat phase inferred from drum accents can be ambiguous. The first
                    // strong attack after a quiet section still marks the drop in that bar.
                    e.metric >= .7 && e.level >= HIGH && rise >= 2 && firstOfBar ->
                        if (e.level == PEAK) 4 else 3
                    !onBeat -> 1
                    e.level == PEAK && rise >= 1 && position == 0 && firstOfBar ->
                        if ((bars[bar - 2] ?: PEAK) <= MID || random.nextDouble() < .4) 4 else 3
                    e.level == PEAK && position == 0 && e.strength >= strong ->
                        if (random.nextDouble() < .45) 3 else 2
                    e.level >= HIGH && position % 2 == 0 && e.strength >= strong -> 2
                    e.level == MID && position == 0 && e.strength >= strong && random.nextDouble() < .45 -> 2
                    else -> 1
                }
            if (size == 1) continue
            val cooldown = beatSeconds * (if (size >= 3) 3.5 else 1.9)
            if (e.time - lastChord < cooldown && rise < 1) continue
            val before = events.getOrNull(i - 1)
            val after = events.getOrNull(i + 1)
            // A drop can have a half-beat hat immediately after its first accent.
            val spacing = if (size >= 3 && rise >= 1) .22 else .3
            if (before != null && e.time - before.time < spacing) continue
            if (after != null && after.time - e.time < spacing) continue
            e.size = size
            lastChord = e.time
        }

        // ---- Holds where the sound sustains into a gap; some become slides.
        for ((i, e) in events.withIndex()) {
            if (e.size > 1) continue
            val until = events.getOrNull(i + 1)?.time ?: (duration - .8)
            val gap = until - e.time
            val release = max(.18, beatSeconds * .45)
            val length = minOf(gap - release, beatSeconds * 4)
            if (length < max(.35, beatSeconds * .9)) continue
            val held = loudAt(e.time + .05)
            var total = 0
            var sustained = 0
            var time = e.time + .1
            while (time < until - .1) {
                total++
                if (samples.energy[(time / FRAME).toInt().coerceIn(0, samples.energy.size - 1)] >= held * .45) sustained++
                time += .05
            }
            val natural = gap >= beatSeconds * 1.4 && total > 0 && sustained >= total * .7
            val chance =
                when {
                    e.level == CALM -> .6 * holdAffinity
                    natural -> .55 * holdAffinity
                    else -> 0.0
                }
            if (random.nextDouble() >= chance) continue
            e.duration = round(length)
            e.kind =
                if (e.level >= MID && random.nextDouble() < .4 * slideAffinity) "slide" else "hold"
        }

        // ---- Lanes follow the melody; same-pitch notes use per-bar patterns.
        val signatures = HashMap<Int, Int>()
        for (e in events) {
            val bar = barOf(e.beat)
            val slot = (inBar(e.beat) * 2 + if (e.metric == .7) 1 else 0).coerceIn(0, 7)
            signatures[bar] = (signatures[bar] ?: (e.level shl 24)) or (1 shl slot)
        }
        val library = HashMap<Int, IntArray>()
        val notes = ArrayList<Note>()
        var previous = -1
        var lastPitch = -1f
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
            val patterned = sequence[step++ % sequence.size]
            val move = if (e.pitch >= 0 && lastPitch >= 0) e.pitch - lastPitch else 0f
            var lane =
                when {
                    previous < 0 || kotlin.math.abs(move) < 1.5f -> patterned
                    else -> {
                        val distance = if (kotlin.math.abs(move) > 5f) 2 else 1
                        val target = previous + if (move > 0) distance else -distance
                        // Bounce off the edges instead of piling up there.
                        if (target in 0..3) target else previous - (target - previous)
                    }
                }.coerceIn(0, 3)
            if (lane == previous) lane = (lane + if (lane == 3) -1 else 1)
            if (e.pitch >= 0) lastPitch = e.pitch
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
            "onsets",
            notes.sortedWith(compareBy({ it.time }, { it.lane })),
            beats.filter { it in 0.0..duration }.map(::round),
            phase,
        )
    }

    internal data class Attack(val time: Double, val strength: Double)

    internal fun attacks(samples: Samples): List<Attack> {
        if (samples.flux.isEmpty()) return emptyList()
        val local = smooth(samples.flux, (1.5 / TICK).toInt())
        return pick(samples.flux).map { Attack(tickTime(it), samples.flux[it] / max(local[it], 1e-3)) }
    }

    // ------------------------------------------------------------------ analysis

    /** The onset envelope with its local mean removed and unit deviation, for tempo and beats. */
    private fun envelope(flux: FloatArray): DoubleArray {
        val raw = DoubleArray(flux.size) { flux[it].toDouble() }
        val mean = smooth(raw, (.4 / TICK).toInt())
        for (i in raw.indices) raw[i] = max(0.0, raw[i] - mean[i])
        var square = 0.0
        for (value in raw) square += value * value
        val deviation = sqrt(square / maxOf(1, raw.size))
        if (deviation > 1e-9) for (i in raw.indices) raw[i] /= deviation
        return raw
    }

    /**
     * Peak picking after madmom's onset processor: a local maximum over 10 ms before and 50 ms
     * after, above the mean of the previous 150 ms plus a threshold, at least 30 ms apart.
     */
    private fun pick(flux: FloatArray): List<Int> {
        val n = flux.size
        var mean = 0.0
        for (value in flux) mean += value
        mean /= maxOf(1, n)
        val threshold = mean * .35
        val before = maxOf(1, (.01 / TICK).roundToInt())
        val after = (.05 / TICK).roundToInt()
        val average = (.15 / TICK).roundToInt()
        val apart = (.03 / TICK).roundToInt()
        val result = ArrayList<Int>()
        var sum = 0.0
        for (t in 0 until n) {
            if (t > average) sum -= flux[t - average - 1]
            if (t > 0) sum += flux[t - 1]
            val value = flux[t]
            if (value <= 0) continue
            var peak = true
            for (j in maxOf(0, t - before)..minOf(n - 1, t + after)) {
                if (j != t && (flux[j] > value || (j < t && flux[j] == value))) {
                    peak = false
                    break
                }
            }
            if (!peak) continue
            if (value < sum / maxOf(1, minOf(t, average)) + threshold) continue
            if (result.isNotEmpty() && t - result.last() < apart) continue
            result.add(t)
        }
        return result
    }

    private fun peak(values: DoubleArray, index: Int, radius: Int): Double {
        var best = 0.0
        for (i in maxOf(0, index - radius)..minOf(values.size - 1, index + radius)) best = max(best, values[i])
        return best
    }

    /** Beat period in frames, from autocorrelation weighted toward 120 BPM. */
    private fun tempo(onset: DoubleArray): Double {
        val shortest = (60.0 / 190 / TICK).toInt()
        val longest = (60.0 / 65 / TICK).toInt() + 1
        val n = onset.size
        if (n < longest * 4) return 60.0 / 120 / TICK
        val correlation = DoubleArray(longest + 2)
        for (lag in shortest - 1..longest + 1) {
            var sum = 0.0
            for (i in 0 until n - lag) sum += onset[i] * onset[i + lag]
            correlation[lag] = sum / (n - lag)
        }
        var best = shortest
        var bestScore = -1.0
        for (lag in shortest..longest) {
            val octaves = ln(60.0 / (lag * TICK) / 120) / ln(2.0)
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

    private fun smooth(values: FloatArray, radius: Int) =
        smooth(DoubleArray(values.size) { values[it].toDouble() }, radius)

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
