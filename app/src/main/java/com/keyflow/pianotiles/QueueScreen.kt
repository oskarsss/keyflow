package com.keyflow.pianotiles

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.keyflow.pianotiles.library.OnDeviceLibrary
import com.keyflow.pianotiles.library.Preparation
import com.keyflow.pianotiles.library.Preparation.QueueState
import com.keyflow.pianotiles.library.PreparationPlan.Reason
import com.keyflow.pianotiles.ui.Palette

/**
 * Settings › Song queues: the songs one worker still has to download and the downloaded songs
 * whose levels the other still has to make, each in the order its worker takes them, refreshed
 * while the page is open.
 */
internal fun MainActivity.showQueue() {
    val body = page(Screen.QUEUE, bar("Song queues") { showSettings() })
    val summary = ui.text("Checking…", 13f, Palette.MUTED).apply { setPadding(dp(4), dp(12), dp(4), 0) }
    ui.fill(body, summary)
    val downloads = section(body, "Downloading")
    val levels = section(body, "Preparing levels")
    downloads.visibility = View.GONE
    levels.visibility = View.GONE
    refreshQueue(scroll, summary, downloads, levels)
}

private fun MainActivity.refreshQueue(page: ScrollView?, summary: TextView, downloads: LinearLayout, levels: LinearLayout) {
    io.execute {
        val queues = runCatching { Preparation.queues(this) }.getOrNull()
        handler.post {
            // A rebuilt or different screen has its own page.
            if (scroll !== page || isDestroyed) return@post
            if (queues != null) {
                summary.text = when {
                    queues.downloads.isEmpty() && queues.levels.isEmpty() -> "Every song your playlists and Arcade need is ready."
                    else -> listOfNotNull(
                        songs(queues.downloads.size).takeIf { queues.downloads.isNotEmpty() }?.let { "$it to download" },
                        levelCount(queues.levels.size).takeIf { queues.levels.isNotEmpty() }?.let { "$it to prepare" },
                    ).joinToString(" · ")
                }
                showQueued(downloads, queues.downloads, download = true)
                showQueued(levels, queues.levels, download = false)
            }
            handler.postDelayed({ refreshQueue(page, summary, downloads, levels) }, 2000)
        }
    }
}

private fun MainActivity.showQueued(list: LinearLayout, queue: List<Preparation.Queued>, download: Boolean) {
    list.visibility = if (queue.isEmpty()) View.GONE else View.VISIBLE
    list.removeAllViews()
    val share = OnDeviceLibrary.DOWNLOAD_SHARE
    for ((i, queued) in queue.take(SHOWN).withIndex()) {
        if (i > 0) divider(list)
        val row = ui.column().apply { setPadding(dp(18), dp(12), dp(18), dp(12)) }
        ui.fill(row, ui.oneLine(ui.text(queued.title, 15f, Palette.TEXT, ui.medium)))
        ui.gap(row, 4)
        val source = listOf(queued.playlist, reasonLabel(queued.item.reason)).filter { it.isNotBlank() }
        // Each worker's bar runs over its own part of the preparation.
        val step = (queued.state as? QueueState.Working)?.fraction?.let { if (download) it / share else (it - share) / (1 - share) }
            ?.coerceIn(0.0, 1.0)
        val status = when (val state = queued.state) {
            is QueueState.Working -> "${state.stage} · ${((step ?: 0.0) * 100).toInt()}%"
            is QueueState.InLine -> when {
                download -> "Downloaded in ${Preparation.describe(state.doneInMs)}"
                queued.quick -> "Full level in ${Preparation.describe(state.doneInMs)}"
                else -> "Ready in ${Preparation.describe(state.doneInMs)}"
            }
            is QueueState.Held -> state.reason
        }
        val quick = if (queued.quick) listOf("Quick level ready") else emptyList()
        ui.fill(row, ui.text((source + quick + status).joinToString(" · "), 12.5f,
            if (queued.state is QueueState.Held) Palette.FAINT else Palette.MUTED))
        step?.let {
            ui.gap(row, 8)
            ui.fill(row, ui.meter(it.toFloat(), height = 4))
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

private fun levelCount(count: Int) = if (count == 1) "1 level" else "$count levels"

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
