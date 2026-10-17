package com.keyflow.pianotiles.chart

import kotlin.math.*
import org.json.JSONObject

/** The pretrained chart vocabulary, its rolling musical context, and four-key decoding. */
class ManiaTokens(private val metadata: JSONObject) {
    private val starts = metadata.getJSONObject("event_start")
    private val ends = metadata.getJSONObject("event_end")
    private val minimum = mutableMapOf<String, Int>()
    private val maximum = mutableMapOf<String, Int>()
    private val types = arrayOfNulls<String>(metadata.getInt("vocab_size_in"))
    private val timeStart = starts.getInt("t")
    private val timeEnd = ends.getInt("t")
    private val columnStart = starts.getInt("column")
    private val columnEnd = ends.getInt("column")
    private val outputSize = metadata.getInt("vocab_size_out")

    init {
        for (name in listOf("event_ranges", "input_event_ranges")) {
            val ranges = metadata.getJSONArray(name)
            for (i in 0 until ranges.length()) {
                val range = ranges.getJSONObject(i)
                val kind = range.getString("type")
                minimum[kind] = range.getInt("min_value"); maximum[kind] = range.getInt("max_value")
                for (id in starts.getInt(kind) until ends.getInt(kind)) types[id] = kind
            }
        }
    }

    fun start(kind: String) = starts.getInt(kind)
    fun end(kind: String) = ends.getInt(kind)
    fun kind(id: Int) = types.getOrNull(id)
    fun value(id: Int): Int = kind(id)?.let { id - start(it) + minimum.getValue(it) } ?: 0
    fun encode(kind: String, value: Int): Int = start(kind) + value.coerceIn(minimum.getValue(kind), maximum.getValue(kind)) - minimum.getValue(kind)
    fun timeToken(relativeMs: Int) = encode("t", (relativeMs / 10).coerceAtLeast(0))
    private fun ratio(value: Double, resolution: Int) = when {
        value <= 0 -> -1
        value >= 1 -> resolution + 1
        else -> round(value * resolution).toInt().coerceIn(0, resolution)
    }

    data class Event(val token: Int, val timeMs: Int)

    fun prompt(startMs: Int, durationMs: Int, rhythm: MusicalRhythm, history: List<Event>, difficulty: Double): LongArray {
        val tokens = ArrayList<Int>()
        tokens.add(encode("gamemode", 3))
        val difficultyClass = (difficulty * metadata.getInt("num_diff_classes") / metadata.getDouble("max_difficulty")).toInt()
        tokens.add(encode("difficulty", difficultyClass))
        tokens.add(encode("mapper", metadata.getInt("num_mapper_classes")))
        tokens.add(encode("year", 2024))
        tokens.add(encode("hitsounded", 0))
        tokens.add(encode("song_length", durationMs / 10000))
        tokens.add(encode("keycount", 4))
        tokens.add(encode("hold_note_ratio", ratio(.1, 10)))
        tokens.add(encode("scroll_speed_ratio", -1))
        tokens.add(encode("descriptor", maximum.getValue("descriptor")))
        tokens.add(encode("scroll_speed", 100))
        tokens.add(encode("song_position", ratio(startMs.toDouble() / durationMs.coerceAtLeast(1), 100)))
        tokens.add(1) // sequence start
        tokens.add(metadata.getJSONObject("context_sos").getInt("timing"))
        val firstDownbeat = rhythm.downbeats.firstOrNull() ?: rhythm.beats.firstOrNull()
        for (beat in rhythm.beats) {
            val ms = round(beat * 1000).toInt()
            if (ms < startMs || ms >= startMs + WINDOW_MS) continue
            tokens.add(timeToken(ms - startMs))
            val kind = when {
                firstDownbeat != null && abs(beat - firstDownbeat) < .001 -> "timing_point"
                rhythm.downbeats.binarySearch(beat) >= 0 -> "measure"
                else -> "beat"
            }
            tokens.add(encode(kind, 0))
        }
        tokens.add(metadata.getJSONObject("context_eos").getInt("timing"))
        tokens.add(metadata.getJSONObject("context_sos").getInt("map"))
        for (event in history) {
            if (event.timeMs < startMs || event.timeMs >= startMs + WINDOW_MS) continue
            tokens.add(if (kind(event.token) == "t") timeToken(event.timeMs - startMs) else event.token)
        }
        check(tokens.size < MAX_TOKENS - 64) { "The chart context is too dense. Try a lower difficulty." }
        return LongArray(tokens.size) { tokens[it].toLong() }
    }

    /** Apply the reference monotonic-time and history masks; no generated timestamps are invented. */
    fun choose(logits: java.nio.FloatBuffer, prompt: LongArray, generated: List<Int>, minimumTime: Int, rhythmicAudio: Boolean = false): Int {
        var lastTime = minimumTime
        for (token in prompt.reversedArray()) {
            val id = token.toInt()
            if (id == 1 || id in 3..8 && id % 2 == 1) break
            if (kind(id) == "t") { lastTime = maxOf(lastTime, value(id)); break }
        }
        generated.lastOrNull { kind(it) == "t" }?.let { lastTime = maxOf(lastTime, value(it)) }
        // Human maps can intentionally leave intros empty. For audible rhythmic windows, prefer
        // a plausible learned timestamp over a weak immediate stop; confident stops still win.
        val preferTimestamp = if (rhythmicAudio && generated.isEmpty()) {
            val next = (timeStart + lastTime until minOf(timeEnd, timeStart + LOOKAHEAD_STEP))
                .maxOfOrNull { logits.get(it) } ?: Float.NEGATIVE_INFINITY
            maxOf(logits.get(2), logits.get(6)) <= next + 2.5f
        } else false
        var best = -1
        var score = Float.NEGATIVE_INFINITY
        for (id in 0 until outputSize) {
            if (preferTimestamp && (id == 2 || id == 6)) continue
            if (id in timeStart until minOf(timeEnd, timeStart + lastTime)) continue
            // The checkpoint supports up to 18 keys; this application always requests four.
            if (id in columnStart + 4 until columnEnd) continue
            val candidate = logits.get(id)
            check(candidate.isFinite()) { "The chart model produced invalid predictions." }
            if (candidate > score) { best = id; score = candidate }
        }
        check(best >= 0)
        return best
    }

    fun endsWindow(token: Int, final: Boolean): Boolean = token == 2 || token == 6 ||
        !final && kind(token) == "t" && value(token) >= LOOKAHEAD_STEP

    fun append(history: MutableList<Event>, tokens: List<Int>, startMs: Int, durationMs: Int, final: Boolean) {
        var time = startMs
        val limit = if (final) durationMs else minOf(durationMs, startMs + KEEP_MS)
        for (token in tokens) {
            if (token == 2 || token == 6) break
            if (kind(token) == "t") time = startMs + value(token) * 10 + 5
            if (kind(token) != null && time <= limit && time <= durationMs) history.add(Event(token, time))
        }
    }

    /** Pair holds by lane, so overlapping left/right holds cannot overwrite one another. */
    fun notes(events: List<Event>, duration: Double): List<Chart.Note> {
        val notes = ArrayList<Chart.Note>()
        val holds = arrayOfNulls<Double>(4)
        var lane = -1
        var time = 0.0
        fun finish(kind: String) {
            if (lane !in 0..3 || time !in 1.5..(duration - .6)) return
            when (kind) {
                "circle" -> notes.add(Chart.Note(time, lane))
                "hold_note" -> {
                    holds[lane]?.let { notes.add(Chart.Note(it, lane)) }
                    holds[lane] = time
                }
                "hold_note_end" -> {
                    val first = holds[lane]
                    if (first != null && time - first >= .15) notes.add(Chart.Note(first, lane, "hold", time - first, lane))
                    else if (first != null) notes.add(Chart.Note(first, lane))
                    holds[lane] = null
                }
            }
        }
        for (event in events) when (val kind = kind(event.token)) {
            "t" -> { time = event.timeMs / 1000.0; lane = -1 }
            "column" -> lane = value(event.token)
            "circle", "hold_note", "hold_note_end" -> finish(kind)
        }
        // An unclosed tail is a tap, rather than an invented hold of arbitrary length.
        for (i in 0..3) holds[i]?.let { notes.add(Chart.Note(it, i)) }
        return ThumbChart.makePlayable(notes.distinctBy { it.time to it.lane })
    }

    companion object {
        const val MAX_TOKENS = 2560
        const val WINDOW_MS = 16376
        const val KEEP_MS = 13100
        const val LOOKAHEAD_STEP = 1310
        const val HISTORY_STEP = 327
    }
}
