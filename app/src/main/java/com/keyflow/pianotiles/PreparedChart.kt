package com.keyflow.pianotiles

import org.json.JSONArray
import org.json.JSONObject

/** Everything expensive is persisted by preparation, including the chosen journey excerpt. */
object PreparedChart {
    fun encode(chart: AudioChart.Chart): JSONObject {
        val highlight = LevelExcerpt.choose(chart.duration, chart.sections, chart.downbeats)
        return JSONObject().put("version", LibraryStore.CHART_VERSION).put("duration", chart.duration)
            .put("method", chart.method).put("beats", JSONArray(chart.beats)).put("barPhase", chart.barPhase)
            .put("bpm", chart.bpm).put("downbeats", JSONArray(chart.downbeats))
            .put("sections", JSONArray(chart.sections.map { section -> JSONObject()
                .put("start", section.start).put("end", section.end).put("family", section.family).put("intensity", section.intensity) }))
            .put("highlight", highlight?.let { JSONObject().put("start", it.start).put("end", it.end) } ?: JSONObject.NULL)
            .put("notes", JSONArray(chart.notes.map { note -> JSONObject().put("time", note.time).put("lane", note.lane)
                .put("kind", note.kind).put("duration", note.duration).put("endLane", note.endLane) }))
    }

    fun highlight(chart: JSONObject): LevelExcerpt.Range? {
        if (chart.has("highlight")) return chart.optJSONObject("highlight")?.let {
            LevelExcerpt.Range(it.getDouble("start"), it.getDouble("end"))
        }
        // Built-ins and old external fixtures have no cached excerpt metadata.
        val sections = chart.optJSONArray("sections")?.let { values -> (0 until values.length()).map { i ->
            val section = values.getJSONObject(i)
            AudioChart.Section(section.getDouble("start"), section.getDouble("end"), section.getInt("family"), section.getDouble("intensity"))
        } }.orEmpty()
        val downbeats = chart.optJSONArray("downbeats")?.let { values -> (0 until values.length()).map { values.getDouble(it) } }.orEmpty()
        return LevelExcerpt.choose(chart.optDouble("duration", 0.0), sections, downbeats)
    }
}
