package com.keyflow.pianotiles

import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.keyflow.pianotiles.library.Preparation
import com.keyflow.pianotiles.library.Preparation.QueueState
import com.keyflow.pianotiles.library.PreparationPlan.Reason
import com.keyflow.pianotiles.ui.Palette

/**
 * Settings › Download queue: every song the worker still has to download and prepare, in the
 * order it takes them, refreshed while the page is open.
 */
internal fun MainActivity.showQueue() {
    val body = page(Screen.QUEUE, bar("Download queue") { showSettings() })
    val summary = ui.text("Checking…", 13f, Palette.MUTED).apply { setPadding(dp(4), dp(12), dp(4), 0) }
    ui.fill(body, summary)
    val list = section(body, "Up next")
    list.visibility = android.view.View.GONE
    refreshQueue(scroll, summary, list)
}

private fun MainActivity.refreshQueue(page: ScrollView?, summary: TextView, list: LinearLayout) {
    io.execute {
        val queue = runCatching { Preparation.queue(this) }.getOrDefault(emptyList())
        handler.post {
            // A rebuilt or different screen has its own page.
            if (scroll !== page || isDestroyed) return@post
            showQueued(queue, summary, list)
            handler.postDelayed({ refreshQueue(page, summary, list) }, 2000)
        }
    }
}

private fun MainActivity.showQueued(queue: List<Preparation.Queued>, summary: TextView, list: LinearLayout) {
    val waiting = queue.mapNotNull { (it.state as? QueueState.InLine)?.readyInMs ?: (it.state as? QueueState.Preparing)?.leftMs }
    val held = queue.count { it.state is QueueState.Held }
    summary.text = when {
        queue.isEmpty() -> "Every song your playlists and Arcade need is on the phone."
        waiting.isEmpty() -> "${songs(queue.size)} waiting"
        else -> listOfNotNull(
            "${songs(waiting.size)} to prepare · done in ${Preparation.describe(waiting.max())}",
            if (held > 0) "${songs(held)} waiting" else null,
        ).joinToString(" · ")
    }
    list.visibility = if (queue.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    list.removeAllViews()
    for ((i, queued) in queue.take(SHOWN).withIndex()) {
        if (i > 0) divider(list)
        val row = ui.column().apply { setPadding(dp(18), dp(12), dp(18), dp(12)) }
        ui.fill(row, ui.oneLine(ui.text(queued.title, 15f, Palette.TEXT, ui.medium)))
        ui.gap(row, 4)
        val source = listOf(queued.playlist, reasonLabel(queued.item.reason)).filter { it.isNotBlank() }
        val status = when (val state = queued.state) {
            is QueueState.Preparing -> "${state.stage} · ${(state.fraction * 100).toInt()}%"
            is QueueState.InLine -> "Ready in ${Preparation.describe(state.readyInMs)}"
            is QueueState.Held -> state.reason
        }
        ui.fill(row, ui.text((source + status).joinToString(" · "), 12.5f,
            if (queued.state is QueueState.Held) Palette.FAINT else Palette.MUTED))
        (queued.state as? QueueState.Preparing)?.let {
            ui.gap(row, 8)
            ui.fill(row, ui.meter(it.fraction.toFloat(), height = 4))
        }
        ui.fill(list, row)
    }
    if (queue.size > SHOWN) {
        divider(list)
        ui.fill(list, ui.text("and ${songs(queue.size - SHOWN)} more", 12.5f, Palette.MUTED).apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
        })
    }
}

private fun songs(count: Int) = if (count == 1) "1 song" else "$count songs"

private fun reasonLabel(reason: Reason) = when (reason) {
    Reason.WAITING -> "You opened it"
    Reason.ARCADE_NEXT -> "Next in your Arcade run"
    Reason.NEXT -> "Next level"
    Reason.LOCKED -> "Level after next"
    Reason.ARCADE -> "Arcade"
    Reason.AHEAD -> "Download ahead"
    Reason.ALL -> "Download all"
}

/** Long queues, such as Download all, list this many songs. */
private const val SHOWN = 100
