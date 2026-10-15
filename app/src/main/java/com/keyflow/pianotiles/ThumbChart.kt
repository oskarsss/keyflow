package com.keyflow.pianotiles

/** Last gate for generated and legacy charts: two hands, one action per hand, no crossing slides. */
object ThumbChart {
    fun makePlayable(source: List<AudioChart.Note>): List<AudioChart.Note> {
        val result = ArrayList<AudioChart.Note>()
        val busy = doubleArrayOf(-100.0, -100.0)
        val last = doubleArrayOf(-100.0, -100.0)
        for (moment in source.groupBy { it.time }.toSortedMap().values) {
            val used = BooleanArray(2)
            // Keep one tile from each side when reducing old three/four-tile chords.
            val required = moment.filter { it.kind != GameSession.AVOID }.sortedBy { it.lane }
            val candidates = required.distinctBy { it.lane / 2 }.toMutableList()
            for (note in required) if (candidates.size < 2 && note !in candidates) candidates.add(note)
            val momentNotes = ArrayList<AudioChart.Note>()
            for (note in candidates) {
                var hand = note.lane / 2
                val available = { h: Int -> !used[h] && note.time >= busy[h] && note.time - last[h] >= .22 }
                if (!available(hand)) {
                    hand = 1 - hand
                    if (!available(hand)) continue
                }
                val lane = hand * 2 + note.lane.mod(2)
                val duration = note.duration.coerceIn(0.0, if (note.kind == "slide") 1.8 else 2.5)
                val end = if (note.kind == "slide" && note.endLane != note.lane) lane xor 1 else lane
                momentNotes.add(AudioChart.Note(note.time, lane, note.kind, duration, end))
                used[hand] = true
                last[hand] = note.time
                busy[hand] = note.time + duration + if (duration > 0) .20 else 0.0
            }
            result.addAll(momentNotes)
            result.addAll(moment.filter { bomb -> bomb.kind == GameSession.AVOID && result.none { n ->
                (n.time == bomb.time && n.lane == bomb.lane) || n.duration > 0 && bomb.time >= n.time &&
                    bomb.time <= n.time + n.duration + .1 && bomb.lane in minOf(n.lane, n.endLane)..maxOf(n.lane, n.endLane)
            } })
        }
        return result.sortedWith(compareBy({ it.time }, { it.lane }))
    }

    fun forPlay(source: List<GameSession.Note>): List<GameSession.Note> = makePlayable(source.map {
        AudioChart.Note(it.time, it.lane, it.kind, it.duration, it.endLane)
    }).map { GameSession.Note(it.time, it.lane, it.kind, it.duration, it.endLane) }
}
