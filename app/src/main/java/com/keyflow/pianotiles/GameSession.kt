package com.keyflow.pianotiles

import kotlin.math.abs
import kotlin.math.ceil

/** Audio playback position is the only judgement clock. Notes must be chronological. */
class GameSession(val notes: List<Note>) {
    class Note(
        val time: Double,
        val lane: Int,
        val kind: String = "tap",
        val duration: Double = 0.0,
        val endLane: Int = lane,
    ) {
        var judgement = 0

        fun laneAt(time: Double): Float =
            lane +
                .5f +
                if (kind == "slide")
                    ((endLane - lane) * ((time - this.time) / duration).coerceIn(0.0, 1.0))
                        .toFloat()
                else 0f
    }

    var hits = 0
        private set

    var perfect = 0
        private set

    var misses = 0
        private set

    var combo = 0
        private set

    var maxCombo = 0
        private set

    var score = 0
        private set

    private var expired = 0
    var lastCompletedLane = 0
        private set

    var lastQuality = 1
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
    ): Int {
        val regrip =
            active.entries.firstOrNull {
                it.value.awaitingGrip && abs(it.value.note.laneAt(time) - position) <= .7f
            }
        if (regrip != null) {
            val gesture = regrip.value
            active.remove(regrip.key)
            gesture.awaitingGrip = false
            gesture.position = position
            active[pointer] = gesture
            return 4
        }
        advance(time)
        var candidate: Note? = null
        var closest = WINDOW + .000001
        for (i in expired until notes.size) {
            val note = notes[i]
            if (note.time > time + WINDOW) break
            if (note.judgement != 0 || note.lane != lane) continue
            val delta = abs(note.time - time)
            if (delta <= closest) {
                candidate = note
                closest = delta
            }
        }
        val note =
            candidate
                ?: run {
                    if (penalize) miss()
                    return if (penalize) 3 else 0
                }
        val quality = if (closest <= .080) 1 else 2
        if (note.kind != "tap" && note.duration > 0) {
            note.judgement = 4
            active[pointer] = Gesture(note, quality, position)
            return 4
        }
        complete(note, quality)
        return quality
    }

    private fun complete(note: Note, quality: Int) {
        note.judgement = quality
        lastCompletedLane = note.endLane
        lastQuality = quality
        hits++
        combo++
        maxCombo = maxOf(maxCombo, combo)
        if (note.judgement == 1) perfect++
        score += (if (note.judgement == 1) 100 else 70) + minOf(combo, 50) * 2
    }

    fun move(pointer: Int, position: Float, time: Double): Int {
        val gesture = active[pointer]
        if (gesture != null) {
            gesture.position = position
            advance(time)
            return 4
        }
        return press(position.toInt().coerceIn(0, 3), time, pointer, position, penalize = false)
    }

    fun release(pointer: Int, time: Double) {
        val gesture = active.remove(pointer) ?: return
        if (
            time >= gesture.note.time + gesture.note.duration - .14 &&
                abs(gesture.position - gesture.note.laneAt(time)) <= .7f
        )
            complete(gesture.note, gesture.quality)
        else {
            gesture.note.judgement = 3
            miss()
        }
    }

    fun advance(time: Double) {
        while (expired < notes.size && time - notes[expired].time > WINDOW) {
            val note = notes[expired++]
            if (note.judgement == 0) {
                note.judgement = 3
                miss()
            }
        }
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val gesture = iterator.next().value
            val note = gesture.note
            if (gesture.awaitingGrip) {
                if (time > note.time + note.duration + WINDOW) {
                    note.judgement = 3
                    miss()
                    iterator.remove()
                }
                continue
            }
            val outside = abs(gesture.position - note.laneAt(time)) > .7f
            if (outside && gesture.outsideSince < 0) gesture.outsideSince = time
            if (!outside) gesture.outsideSince = -1.0
            if (outside && time - gesture.outsideSince > .16) {
                note.judgement = 3
                miss()
                iterator.remove()
            } else if (time >= note.time + note.duration - .06 && !outside) {
                complete(note, gesture.quality)
                iterator.remove()
            }
        }
    }

    private fun miss() {
        misses++
        combo = 0
    }

    fun accuracy() = if (notes.isEmpty()) 0.0 else (perfect + (hits - perfect) * .7) / notes.size

    fun passed() = notes.isNotEmpty() && !failed() && hits >= ceil(notes.size * .75)

    fun stars() =
        when {
            !passed() -> 0
            accuracy() >= .95 -> 3
            accuracy() >= .85 -> 2
            else -> 1
        }

    fun failed() = misses >= 8

    companion object {
        const val WINDOW = .180
    }
}
