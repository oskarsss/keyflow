package com.keyflow.pianotiles

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Audio playback position is the only judgement clock. Notes must be chronological.
 *
 * Kinds: `tap`, `hold`, `slide`, `gold` (a tap worth a bonus that heals a life) and `avoid` (a bomb
 * that costs a life when touched and is simply skipped otherwise).
 */
class GameSession(
    val notes: List<Note>,
    startLives: Int = MAX_LIVES,
    /** Time Attack turns this off: misses still break the combo but never end the run. */
    private val mortal: Boolean = true,
    startCombo: Int = 0,
) {
    class Note(
        val time: Double,
        val lane: Int,
        val kind: String = "tap",
        val duration: Double = 0.0,
        val endLane: Int = lane,
    ) {
        var judgement = 0
        var timingErrorMs = 0.0

        fun laneAt(time: Double): Float =
            lane +
                .5f +
                if (kind == "slide")
                    ((endLane - lane) * ((time - this.time) / duration).coerceIn(0.0, 1.0))
                        .toFloat()
                else 0f
    }

    /** Notes the player must hit; bombs are excluded. */
    val scored = notes.count { it.kind != AVOID }

    var hits = 0
        private set

    var dodged = 0
        private set

    var bonuses = 0
        private set

    var chordBonuses = 0
        private set
    var lastChordBonus = 0
        private set
    var lastChordLanes = IntArray(0)
        private set

    private class Chord(val notes: List<Note>) {
        var first = Double.POSITIVE_INFINITY
        var last = Double.NEGATIVE_INFINITY
        var awarded = false
        val lanes = notes.map { it.lane }.toIntArray()
    }

    private val chords = HashMap<Note, Chord>().apply {
        for (group in notes.filter { it.kind in listOf("tap", GOLD) && it.duration == 0.0 }.groupBy { it.time }.values) {
            if (group.size < 2) continue
            val chord = Chord(group)
            for (note in group) put(note, chord)
        }
    }

    var perfect = 0
        private set

    var misses = 0
        private set

    var combo = startCombo
        private set

    var maxCombo = 0
        private set

    var score = 0
        private set

    /** Each miss costs a life; every [RECOVER_STREAK] hits in a row earns one back. */
    var lives = startLives
        private set

    private var expired = 0
    var lastCompletedLane = 0
        private set

    var lastCompletedKind = "tap"
        private set

    var lastTimingErrorMs = 0.0
        private set

    var lastQuality = 1
        private set

    var lastMissLane = 0
        private set

    private class Gesture(val note: Note, val quality: Int, var position: Float) {
        var outsideSince = -1.0
        var awaitingGrip = false
    }

    private val active = mutableMapOf<Int, Gesture>()

    fun activeKind(pointer: Int) = active[pointer]?.note?.kind

    fun suspendGestures() {
        active.values.forEach { it.awaitingGrip = true }
    }

    fun tap(lane: Int, time: Double): Int = press(lane, time, 0)

    fun press(
        lane: Int,
        time: Double,
        pointer: Int,
        position: Float = lane + .5f,
        penalize: Boolean = true,
        /** The visible tile under the touch, even if it has not reached its timing window. */
        target: Note? = null,
    ): Int {
        val regrip =
            active.entries.firstOrNull {
                it.value.awaitingGrip && abs(it.value.note.laneAt(time) - position) <= .9f
            }
        if (regrip != null) {
            val gesture = regrip.value
            active.remove(regrip.key)
            gesture.awaitingGrip = false
            gesture.position = position
            active[pointer] = gesture
            return 4
        }
        val touched = target?.takeIf { it.judgement == 0 && it in notes }
        advance(time)
        if (touched != null && touched.judgement == DODGED) {
            // A bomb touched as it expires was hit, rather than successfully avoided.
            dodged--
            touched.judgement = 3
            miss(touched.lane)
            return 3
        }
        // Expiry may already have charged this touch's tile. Never charge it a second time.
        if (touched != null && touched.judgement != 0) return 3
        if (touched != null && abs(touched.time - time) > WINDOW) {
            touched.judgement = 3
            miss(touched.lane)
            return 3
        }
        var candidate: Note? = touched
        var closest = touched?.let { abs(it.time - time) } ?: (WINDOW + .000001)
        if (touched == null) {
            for (i in expired until notes.size) {
                val note = notes[i]
                if (note.time > time + WINDOW) break
                if (note.judgement != 0 || note.lane != lane) continue
                val delta = abs(note.time - time)
                if (delta <= closest && (candidate == null || candidate.kind == AVOID || note.kind != AVOID)) {
                    candidate = note
                    closest = delta
                }
            }
        }
        val note =
            candidate
                ?: run {
                    if (penalize) miss(lane)
                    return if (penalize) 3 else 0
                }
        if (note.kind == AVOID) {
            note.judgement = 3
            miss(lane)
            return 3
        }
        note.timingErrorMs = (time - note.time) * 1000
        val quality = if (closest <= PERFECT_WINDOW) 1 else 2
        if (note.kind != "tap" && note.duration > 0) {
            note.judgement = 4
            active[pointer] = Gesture(note, quality, position)
            return 4
        }
        complete(note, quality, time)
        return quality
    }

    private fun complete(note: Note, quality: Int, time: Double) {
        note.judgement = quality
        lastCompletedLane = note.endLane
        lastQuality = quality
        lastCompletedKind = note.kind
        lastTimingErrorMs = note.timingErrorMs
        hits++
        combo++
        maxCombo = maxOf(maxCombo, combo)
        if (combo % RECOVER_STREAK == 0 && lives in 1 until MAX_LIVES) lives++
        if (note.judgement == 1) perfect++
        score += (if (note.judgement == 1) 100 else 70) * multiplier
        if (note.kind == GOLD) {
            bonuses++
            score += GOLD_BONUS
            if (lives in 1 until MAX_LIVES) lives++
        }
        chords[note]?.let { chord ->
            chord.first = minOf(chord.first, time)
            chord.last = maxOf(chord.last, time)
            if (!chord.awarded && chord.last - chord.first <= CHORD_WINDOW &&
                chord.notes.all { it.judgement in 1..2 }) {
                chord.awarded = true
                chordBonuses++
                lastChordBonus = CHORD_BONUS * (chord.notes.size - 1) * multiplier
                lastChordLanes = chord.lanes
                score += lastChordBonus
            }
        }
    }

    fun move(pointer: Int, position: Float, time: Double): Int {
        // A held finger only owns the gesture it started. After the tail completes (or fails),
        // subsequent MOVE events must not consume a following tile inside its early hit window.
        // Every new tile needs its own DOWN, including when another pointer is still holding.
        val gesture = active[pointer] ?: return 0
        gesture.position = position
        advance(time)
        return 4
    }

    fun release(pointer: Int, time: Double) {
        val gesture = active.remove(pointer) ?: return
        if (
            time >= gesture.note.time + gesture.note.duration - .14 &&
                abs(gesture.position - gesture.note.laneAt(time)) <= .9f
        )
            complete(gesture.note, gesture.quality, time)
        else {
            gesture.note.judgement = 3
            miss(gesture.note.laneAt(time).toInt().coerceIn(0, 3))
        }
    }

    fun advance(time: Double) {
        while (expired < notes.size && time - notes[expired].time > WINDOW) {
            val note = notes[expired++]
            if (note.judgement == 0 && note.kind == AVOID) {
                note.judgement = DODGED
                dodged++
            } else if (note.judgement == 0) {
                note.judgement = 3
                miss(note.lane)
            }
        }
        if (active.isEmpty()) return
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val gesture = iterator.next().value
            val note = gesture.note
            if (gesture.awaitingGrip) {
                if (time > note.time + note.duration + WINDOW) {
                    note.judgement = 3
                    miss(note.endLane)
                    iterator.remove()
                }
                continue
            }
            val outside = abs(gesture.position - note.laneAt(time)) > .9f
            if (outside && gesture.outsideSince < 0) gesture.outsideSince = time
            if (!outside) gesture.outsideSince = -1.0
            if (outside && time - gesture.outsideSince > .24) {
                note.judgement = 3
                miss(note.laneAt(time).toInt().coerceIn(0, 3))
                iterator.remove()
            } else if (time >= note.time + note.duration - .06 && !outside) {
                complete(note, gesture.quality, time)
                iterator.remove()
            }
        }
    }

    private fun miss(lane: Int) {
        lastMissLane = lane
        misses++
        combo = 0
        if (mortal && lives > 0) lives--
    }

    /** Score multiplier: ×1, then one more step for every 10 notes of combo, up to ×4. */
    val multiplier
        get() = minOf(1 + combo / 10, 4)

    fun accuracy() = if (scored == 0) 0.0 else (perfect + (hits - perfect) * .7) / scored

    fun passed() = scored > 0 && !failed() && hits >= ceil(scored * .75)

    /** Letter grade for the results screen. */
    fun grade() =
        when {
            !passed() -> "F"
            accuracy() >= .97 && misses == 0 -> "S"
            accuracy() >= .9 -> "A"
            accuracy() >= .8 -> "B"
            accuracy() >= .7 -> "C"
            else -> "D"
        }

    fun stars() =
        when {
            !passed() -> 0
            accuracy() >= .95 -> 3
            accuracy() >= .85 -> 2
            else -> 1
        }

    fun failed() = mortal && lives <= 0

    companion object {
        const val PERFECT_WINDOW = .080
        const val WINDOW = .180
        const val MAX_LIVES = 8
        const val RECOVER_STREAK = 25
        const val GOLD_BONUS = 250
        const val CHORD_BONUS = 100
        const val CHORD_WINDOW = .065
        const val DODGED = 5
        const val GOLD = "gold"
        const val AVOID = "avoid"
    }
}
