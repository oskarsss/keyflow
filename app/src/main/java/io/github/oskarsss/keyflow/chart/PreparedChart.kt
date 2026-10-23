package io.github.oskarsss.keyflow.chart

import org.json.JSONArray
import org.json.JSONObject

/** Everything expensive is persisted by preparation, so opening a level runs no model. */
object PreparedChart {
    fun encode(chart: Chart): JSONObject =
        JSONObject().put("duration", chart.duration)
            .put("method", chart.method).put("beats", JSONArray(chart.beats)).put("barPhase", chart.barPhase)
            .put("bpm", chart.bpm).put("downbeats", JSONArray(chart.downbeats))
            .put("sections", JSONArray(chart.sections.map { section -> JSONObject()
                .put("start", section.start).put("end", section.end).put("family", section.family).put("intensity", section.intensity) }))
            .put("notes", JSONArray(chart.notes.map { note -> JSONObject().put("time", note.time).put("lane", note.lane)
                .put("kind", note.kind).put("duration", note.duration).put("endLane", note.endLane) }))

    /**
     * The journey highlights a level can play, from the song's saved sections and bars. Choosing
     * them is quick, so each play can take a different one. Built-in and short songs play whole.
     */
    fun highlights(chart: JSONObject): List<LevelExcerpt.Range> {
        val downbeats = chart.optJSONArray("downbeats") ?: return emptyList()
        val sections = chart.optJSONArray("sections") ?: return emptyList()
        return LevelExcerpt.choices(
            chart.optDouble("duration", 0.0),
            (0 until sections.length()).map { sections.getJSONObject(it) }.map {
                Chart.Section(it.getDouble("start"), it.getDouble("end"), it.getInt("family"), it.getDouble("intensity"))
            },
            (0 until downbeats.length()).map(downbeats::getDouble),
        )
    }
}
