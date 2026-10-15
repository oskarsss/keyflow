package com.keyflow.pianotiles

import kotlin.random.Random

/**
 * Adds variety on top of a generated chart, deterministically per song:
 * - gold tiles (every level): bonus points and one life back;
 * - chords (from level 2): a second tile at the same moment, tapped with two fingers;
 * - bombs (from level 3): a dark tile beside a real one that must not be touched.
 *
 * Extra tiles only go into lanes that are free around that moment, so they never collide with
 * holds, slides or neighbouring taps.
 */
object TileVariety {
    fun decorate(source: List<GameSession.Note>, level: Int, seed: Int): List<GameSession.Note> {
        val random = Random(seed)
        val notes =
            source
                .map { GameSession.Note(it.time, it.lane, it.kind, it.duration, it.endLane) }
                .toMutableList()
        val chordChance = if (level >= 1) minOf(.3, .16 + level * .02) else 0.0
        val bombChance = if (level >= 2) minOf(.14, .06 + level * .01) else 0.0
        var lastGold = -100
        val extra = ArrayList<GameSession.Note>()
        for (i in notes.indices) {
            val note = notes[i]
            if (note.kind != "tap") continue
            val gapBefore = if (i > 0) note.time - notes[i - 1].time else 9.0
            val gapAfter = if (i + 1 < notes.size) notes[i + 1].time - note.time else 9.0
            if (i >= 6 && i - lastGold >= 10 && random.nextDouble() < 1 / 12.0) {
                notes[i] = GameSession.Note(note.time, note.lane, GameSession.GOLD)
                lastGold = i
                continue
            }
            val roll = random.nextDouble()
            if (gapBefore >= .3 && gapAfter >= .25 && roll < chordChance) {
                free(notes + extra, note.time, .35, note.lane, random)?.let {
                    extra.add(GameSession.Note(note.time, it))
                }
            } else if (note.time >= 5 && roll < chordChance + bombChance) {
                free(notes + extra, note.time, .5, note.lane, random)?.let {
                    extra.add(GameSession.Note(note.time, it, GameSession.AVOID))
                }
            }
        }
        return (notes + extra).sortedBy { it.time }
    }

    private fun free(
        notes: List<GameSession.Note>,
        time: Double,
        margin: Double,
        except: Int,
        random: Random,
    ): Int? {
        val open =
            (0..3).filter { lane ->
                lane != except &&
                    notes.none { n ->
                        time >= n.time - margin &&
                            time <= n.time + n.duration + margin &&
                            lane in minOf(n.lane, n.endLane)..maxOf(n.lane, n.endLane)
                    }
            }
        return if (open.isEmpty()) null else open[random.nextInt(open.size)]
    }
}
