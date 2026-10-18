package com.keyflow.pianotiles

import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.keyflow.pianotiles.library.OnDeviceLibrary
import com.keyflow.pianotiles.library.Preparation
import com.keyflow.pianotiles.library.Preparation.QueueState
import com.keyflow.pianotiles.ui.Glyph
import com.keyflow.pianotiles.ui.Palette
import org.json.JSONObject

/**
 * What a journey badge means, opened by tapping it: a song that isn't downloaded yet, or one that
 * plays with a quick level while its full level is made. A few lines explain it; below them a
 * status card follows the work live, step by step, with a bar split into those steps and the time
 * it should take.
 */
internal fun MainActivity.showSongStatus(song: JSONObject, mark: SongMark) {
    val full = Preparation.fullLevels(this)
    val missing = mark == SongMark.MISSING
    val body = ui.column().apply { setPadding(dp(24), dp(22), dp(24), dp(4)) }

    val heading = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
    heading.addView(
        ui.icon(if (missing) Glyph.Kind.DOWNLOAD else Glyph.Kind.QUICK, if (missing) Palette.TEXT else Palette.SKY, 20).apply {
            background = ui.rounded(Palette.SURFACE_HI, 18)
        },
        LinearLayout.LayoutParams(dp(36), dp(36)),
    )
    ui.gap(heading, 12)
    ui.weight(heading, ui.text(if (missing) "Not downloaded yet" else "Playing a quick level", 19f, Palette.TEXT, ui.medium))
    ui.fill(body, heading)
    ui.gap(body, 14)

    fun paragraph(text: String) {
        ui.fill(body, ui.text(text, 14.5f, Palette.MUTED).apply { setLineSpacing(0f, 1.2f) })
        ui.gap(body, 10)
    }
    if (missing) {
        paragraph("This song isn't on your phone yet. It downloads by itself when it's one of your next levels " +
            "or Arcade needs it, or as soon as you open it. Downloading needs an internet connection.")
        paragraph("Once it's here, a quick level is made in seconds so you can play" +
            if (full) ", and the higher quality full level follows a few minutes later." else ".")
    } else {
        paragraph("This song is ready to play. Its quick level was made from the beat in seconds, right after " +
            "the download, and catches most of the notes.")
        paragraph("The full level is higher quality tiling: tiles land on the notes you hear, with chords and " +
            "holds where the music has them. It replaces the quick level by itself, from your next play.")
    }
    ui.gap(body, 4)

    // The live status, set apart from the explanation.
    val card = ui.column().apply {
        background = ui.rounded(0xFF13121A.toInt(), 16, Palette.STROKE)
        setPadding(dp(16), dp(14), dp(16), dp(16))
    }
    card.addView(ui.caption(if (missing) "Download" else "Full level", Palette.ACCENT), LinearLayout.LayoutParams(-2, -2))
    ui.gap(card, 8)
    val top = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
    val headline = ui.oneLine(ui.text("Checking…", 16f, Palette.TEXT, ui.medium))
    ui.weight(top, headline)
    val time = ui.text("", 13f, Palette.SKY, ui.medium).apply { gravity = Gravity.END }
    top.addView(time, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
    ui.fill(card, top)
    ui.gap(card, 12)
    val bar = StageBar(this)
    ui.fill(card, bar, dp(8))
    ui.gap(card, 10)
    val steps = ui.column()
    ui.fill(card, steps)
    ui.fill(body, card)

    if (!missing) {
        ui.gap(body, 14)
        ui.fill(body, ui.text("To save battery, turn off Make full levels in Settings. Only quick levels are made then.",
            13f, Palette.FAINT).apply { setLineSpacing(0f, 1.15f) })
    }

    val dialog = android.app.AlertDialog.Builder(this)
        .setView(android.widget.ScrollView(this).apply { addView(body) })
        .setPositiveButton("OK", null)
        .apply {
            if (missing) setNeutralButton("Song queues") { _, _ -> showQueue() }
            else setNeutralButton("Settings") { _, _ -> showSettings() }
        }
        .show()
    val id = song.optString("id")
    fun refresh() {
        if (!dialog.isShowing || isDestroyed) return
        io.execute {
            val status = runCatching { if (missing) downloadStatus(id) else fullLevelStatus(id) }.getOrNull()
            handler.post {
                if (!dialog.isShowing || isDestroyed) return@post
                status?.let { show(it, headline, time, bar, steps) }
                handler.postDelayed(::refresh, 1000)
            }
        }
    }
    refresh()
}

/**
 * One song's work in [steps]: those before [current] are done, [current] is under way with
 * [within] of it done (null while that's unknown), and [current] past the end means all done.
 */
private class Status(
    val steps: List<String>,
    val current: Int,
    val within: Float?,
    val headline: String,
    val time: String,
    val started: Boolean = true,
)

private fun MainActivity.show(status: Status, headline: TextView, time: TextView, bar: StageBar, steps: LinearLayout) {
    headline.text = status.headline
    headline.setTextColor(if (status.started) Palette.TEXT else Palette.MUTED)
    time.text = status.time
    time.visibility = if (status.time.isEmpty()) View.GONE else View.VISIBLE
    bar.set(status.steps.size, status.current, status.within, status.started)
    steps.removeAllViews()
    for ((i, name) in status.steps.withIndex()) {
        val done = i < status.current
        val now = i == status.current && status.started
        val row = ui.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val dot = when {
            done -> ui.icon(Glyph.Kind.CHECK, Palette.MINT, 16)
            else -> View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    if (now) setColor(Palette.ACCENT) else setStroke(dp(2), Palette.FAINT)
                }
            }
        }
        row.addView(FrameLayoutOf(dot, if (done) 16 else 10), LinearLayout.LayoutParams(dp(16), dp(16)))
        ui.gap(row, 12)
        ui.weight(row, ui.text(name, 14f, if (done || now) Palette.TEXT else Palette.MUTED, if (now) ui.medium else ui.regular))
        val state = when {
            done -> "Done"
            now -> status.within?.let { "${(it * 100).toInt()}%" } ?: "Working…"
            else -> ""
        }
        row.addView(ui.text(state, 13f, if (done) Palette.MINT else Palette.MUTED))
        ui.fill(steps, row)
    }
}

/** [view] centred in a 16dp box, so checks and dots line up. */
private fun MainActivity.FrameLayoutOf(view: View, size: Int) = android.widget.FrameLayout(this).apply {
    addView(view, android.widget.FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
}

private const val READY = "Ready"

/** Where a song's download and quick level have got. Disk work. */
private fun MainActivity.downloadStatus(id: String): Status {
    val steps = listOf("Find the song", "Download", "Make the quick level")
    val song = findSong(id)
    val files = store.files()
    if (song != null && files.playable(song)) return Status(steps, steps.size, null, "Ready to play", "")
    if (song != null && files.downloaded(song)) return Status(steps, 2, null, "Making the quick level", "a few seconds")
    val queued = Preparation.queues(this).downloads.firstOrNull { it.item.id == id }
        ?: return Status(steps, 0, null, "Not started", "starts when you open it", started = false)
    val share = OnDeviceLibrary.DOWNLOAD_SHARE
    return when (val state = queued.state) {
        is QueueState.Working -> {
            val downloading = state.stage.startsWith("Downloading")
            val within = if (downloading) ((state.fraction - .03) / (share - .03)).coerceIn(0.0, 1.0).toFloat() else null
            Status(steps, if (downloading) 1 else 0, within,
                if (downloading) "Downloading" else "Finding the song", "${Preparation.describe(state.leftMs)} left")
        }
        is QueueState.InLine -> Status(steps, 0, null, "In line", "playable in ${Preparation.describe(state.doneInMs)}", started = false)
        is QueueState.Held -> Status(steps, 0, null, state.reason, "", started = false)
    }
}

/** Where a song's full level has got. Disk work. */
private fun MainActivity.fullLevelStatus(id: String): Status {
    val steps = listOf("Download", "Quick level", "Find the beat", "Place the tiles")
    val song = findSong(id)
    if (song != null && store.files().prepared(song)) return Status(steps, steps.size, null, READY, "next play uses it")
    val queued = Preparation.queues(this).levels.firstOrNull { it.item.id == id }
        ?: return Status(steps, 2, null, "Waiting its turn", "made when this level comes up", started = false)
    val share = OnDeviceLibrary.DOWNLOAD_SHARE
    val beat = OnDeviceLibrary.BEAT_SHARE
    return when (val state = queued.state) {
        is QueueState.Working -> {
            val level = (state.fraction - share) / (1 - share)
            val placing = state.stage.startsWith("Placing")
            val within = if (placing) ((level - beat) / (.99 - beat)).coerceIn(0.0, 1.0).toFloat() else null
            Status(steps, if (placing) 3 else 2, within, if (placing) "Placing the tiles" else "Finding the beat",
                "${Preparation.describe(state.leftMs)} left")
        }
        is QueueState.InLine -> Status(steps, 2, null, "In line", "ready in ${Preparation.describe(state.doneInMs)}", started = false)
        is QueueState.Held -> Status(steps, 2, null, state.reason, "", started = false)
    }
}

private fun MainActivity.findSong(id: String): JSONObject? {
    val songs = store.songs()
    return (0 until songs.length()).map(songs::getJSONObject).firstOrNull { it.optString("id") == id }
}

/**
 * A progress bar split into one segment per step: finished steps are full, the step under way
 * fills as it goes, or shimmers while how far it has got is unknown.
 */
private class StageBar(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var count = 1
    private var current = 0
    private var within: Float? = null
    private var started = true

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun set(count: Int, current: Int, within: Float?, started: Boolean) {
        this.count = count
        this.current = current
        this.within = within
        this.started = started
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val gap = 4 * resources.displayMetrics.density
        val width = (this.width - gap * (count - 1)) / count
        val h = height.toFloat()
        val r = h / 2
        for (i in 0 until count) {
            val left = i * (width + gap)
            paint.color = Palette.STROKE
            canvas.drawRoundRect(left, 0f, left + width, h, r, r, paint)
            val fill = when {
                i < current -> 1f
                i > current || !started -> 0f
                else -> within ?: -1f
            }
            when {
                fill > 0f -> {
                    paint.color = if (i < current) Palette.MINT else Palette.ACCENT
                    canvas.drawRoundRect(left, 0f, left + maxOf(h, width * fill), h, r, r, paint)
                }
                fill < 0f -> {
                    // Unknown progress: a soft pulse across the whole step.
                    val phase = (android.os.SystemClock.uptimeMillis() % 1400) / 1400f
                    val alpha = (60 + 120 * (0.5 - 0.5 * kotlin.math.cos(phase * 2 * Math.PI))).toInt()
                    paint.color = (alpha shl 24) or (Palette.ACCENT and 0xFFFFFF)
                    canvas.drawRoundRect(left, 0f, left + width, h, r, r, paint)
                    postInvalidateOnAnimation()
                }
            }
        }
    }
}
