package com.keyflow.pianotiles

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.*
import com.keyflow.pianotiles.library.LibraryStore
import com.keyflow.pianotiles.library.Preparation
import com.keyflow.pianotiles.library.SongTitle
import com.keyflow.pianotiles.ui.Glyph
import com.keyflow.pianotiles.ui.Palette
import com.keyflow.pianotiles.ui.TileArtwork
import com.keyflow.pianotiles.ui.Ui
import java.util.Locale
import org.json.JSONObject

private fun MainActivity.nextIndex(): Int {
    val songs = store.songs()
    return (0 until songs.length()).firstOrNull { !store.completed(songs.getJSONObject(it)) }
        ?: -1
}

internal fun MainActivity.showHome() {
    if (isDestroyed || store.playlist == null) return
    scheduleCleanup()
    // Rows start from the saved copy; the first poll brings them up to date off the main thread.
    shownRevision = -1
    shownSongs = emptyList()
    val playlist = store.playlist!!
    val top = ui.row().apply { setPadding(dp(8), 0, dp(8), 0) }
    val switcher = ui.column().apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        ui.pressable(this, null, 14)
        contentDescription = "Switch playlist"
        setOnClickListener { showPlaylists() }
    }
    switcher.addView(ui.caption("Playlist").apply { textSize = 10f }, LinearLayout.LayoutParams(-2, -2))
    ui.gap(switcher, 4)
    switcher.addView(
        ui.oneLine(ui.text(playlist.optString("title"), 18f, Palette.TEXT, ui.medium)).apply {
            setCompoundDrawablesRelative(null, null, ui.glyph(Glyph.Kind.CHEVRON_DOWN, Palette.MUTED, 18), null)
            compoundDrawablePadding = dp(4)
        },
        LinearLayout.LayoutParams(-2, -2),
    )
    top.addView(switcher, LinearLayout.LayoutParams(0, -2, 1f))
    val streak = store.streak()
    top.addView(
        ui.text("$streak", 14f, if (streak > 0) Palette.GOLD else Palette.MUTED, ui.heavy).apply {
            setCompoundDrawablesRelative(
                ui.glyph(Glyph.Kind.FLAME, if (streak > 0) 0xFFFB923C.toInt() else Palette.FAINT, 18),
                null,
                null,
                null,
            )
            compoundDrawablePadding = dp(3)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(12), dp(8))
            background = ui.rounded(Palette.SURFACE_HI, 18)
            contentDescription = "$streak day streak"
        },
    )
    ui.gap(top, 8)
    val xp = store.xp
    val level = LibraryStore.level(xp)
    top.addView(
        ui.levelBadge(level, LibraryStore.levelFraction(xp)).apply {
            setOnClickListener {
                val missing = LibraryStore.levelStart(level + 1) - xp
                Toast.makeText(context, "Level $level · $missing XP to level ${level + 1}", Toast.LENGTH_SHORT).show()
            }
        },
        LinearLayout.LayoutParams(dp(40), dp(40)),
    )
    top.addView(
        ui.iconButton(Glyph.Kind.GEAR, "Settings", Palette.MUTED) { showSettings() },
        LinearLayout.LayoutParams(dp(48), dp(48)),
    )
    val body = page(Screen.HOME, top)
    val songs = store.songs()
    val total = songs.length()
    val done = store.completedCount()
    var earned = 0
    for (i in 0 until total) earned += store.stars(songs.getJSONObject(i))

    val summary = ui.row()
    ui.weight(summary, ui.text("$done of $total cleared", 13f, Palette.MUTED))
    summary.addView(ui.icon(Glyph.Kind.STAR, Palette.GOLD, 14))
    ui.gap(summary, 4)
    summary.addView(ui.text("$earned / ${total * 3}", 13f, Palette.MUTED))
    ui.fill(body, summary)
    ui.gap(body, 10)
    ui.fill(body, ui.meter(if (total == 0) 0f else done / total.toFloat()))
    ui.gap(body, 20)

    val next = nextIndex()
    if (next >= 0) addHero(body, next, songs.getJSONObject(next))
    else {
        val card = ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        card.addView(ui.stars(3, 26, 4))
        ui.gap(card, 10)
        card.addView(ui.text("Every level cleared", 17f, Palette.TEXT, ui.medium))
        ui.gap(card, 4)
        card.addView(ui.text("Replay any song to chase three stars.", 13f, Palette.MUTED))
        ui.fill(body, card)
    }
    ui.gap(body, 24)
    addArcadeCards(body)
    ui.gap(body, 28)
    ui.fill(body, ui.caption("Journey").apply { setPadding(dp(4), 0, 0, dp(4)) })
    addJourney(body, songs, next)
    startPolling(immediately = true)
    Preparation.wake(this)
}

private fun MainActivity.addHero(body: LinearLayout, index: Int, song: JSONObject) {
    // A gradient frame makes the next level read as the main call to action.
    val frame = FrameLayout(this).apply {
        background = ui.gradient(26)
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }
    val card = ui.column().apply {
        background = ui.rounded(0xFF15131F.toInt(), 24)
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }
    frame.addView(card)
    val top = ui.row()
    val cover = artwork(index, 16)
    cover.addView(
        ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            covers.into(this, song, false, dp(240))
        },
        FrameLayout.LayoutParams(-1, -1),
    )
    top.addView(cover, LinearLayout.LayoutParams(dp(104), dp(104)))
    ui.gap(top, 16)
    val label = ui.column()
    label.addView(ui.caption("Next up · Level ${index + 1}", Palette.ACCENT), LinearLayout.LayoutParams(-2, -2))
    ui.gap(label, 8)
    label.addView(
        ui.text(song.optString("title"), 20f, Palette.TEXT, ui.medium).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        },
    )
    ui.gap(label, 4)
    label.addView(ui.oneLine(ui.text(song.optString("artist"), 13.5f, Palette.MUTED)))
    if (store.score(song) > 0) {
        ui.gap(label, 8)
        label.addView(ui.text(String.format(Locale.US, "Best %,d", store.score(song)), 12.5f, Palette.MUTED))
    }
    ui.weight(top, label)
    ui.fill(card, top)
    ui.gap(card, 14)
    val play = ui.button("Play", Ui.Style.PRIMARY, Glyph.Kind.PLAY) {
        prepareSong(song, index)
    }
    ui.fill(card, play)
    val status = ui.text("", 12.5f, Palette.MUTED).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    card.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    hero = play to status
    heroCheckedAt = 0L
    ui.fill(body, frame)
}

/** Whole-song preparation: the download, then the on-device chart, as one progress value. */
internal fun MainActivity.downloadFraction(song: JSONObject): Float? =
    song.optDouble("prepared").takeIf { song.optString("status") == "caching" && !it.isNaN() }?.toFloat()

internal fun MainActivity.downloadStage(song: JSONObject): String {
    val fraction = downloadFraction(song) ?: return "Getting music ready…"
    val stage = song.optString("progress").ifBlank { "Preparing song" }
    return "$stage · ${(fraction * 100).toInt()}%"
}

/**
 * Levels as a winding path of nodes: cleared ones glow, the next one pulses, and locked ones
 * stay blurred until reached. Only the nodes near the visible part of the page exist, so a
 * playlist of hundreds of songs scrolls as smoothly as a short one.
 */
private fun MainActivity.addJourney(body: LinearLayout, songs: org.json.JSONArray, next: Int) {
    val width = resources.displayMetrics.widthPixels - dp(40)
    val rowHeight = dp(118)
    val count = songs.length()
    fun offset(i: Int) = (kotlin.math.sin(i * 1.15) * .26).toFloat()
    fun center(i: Int) = width / 2f + offset(i) * width to i * rowHeight + rowHeight / 2f
    var shown = IntRange.EMPTY
    val map = FrameLayout(this)
    val trail = object : View(this) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val dashes = android.graphics.DashPathEffect(floatArrayOf(dp(2).toFloat(), dp(10).toFloat()), 0f)
        private val path = android.graphics.Path()

        override fun onDraw(canvas: android.graphics.Canvas) {
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            if (shown.isEmpty()) return
            for (i in maxOf(0, shown.first - 1) until minOf(count - 1, shown.last + 1)) {
                val (x1, y1) = center(i)
                val (x2, y2) = center(i + 1)
                path.reset()
                path.moveTo(x1, y1)
                path.cubicTo(x1, (y1 + y2) / 2, x2, (y1 + y2) / 2, x2, y2)
                val reached = next < 0 || i < next
                paint.pathEffect = if (reached) null else dashes
                paint.strokeWidth = dp(if (reached) 6 else 4).toFloat()
                paint.color = if (reached) 0x99A78BFA.toInt() else Palette.STROKE
                canvas.drawPath(path, paint)
            }
        }
    }
    map.addView(trail, FrameLayout.LayoutParams(-1, -1))
    body.addView(map, LinearLayout.LayoutParams(-1, rowHeight * count))
    journeyIds = (0 until count).map { songs.getJSONObject(it).optString("id") }
    val nodes = HashMap<Int, List<View>>()
    val page = scroll ?: return
    fun refresh() {
        // The map's top within the scrolling content; the body is the scroll view's only child.
        val top = map.top + body.top
        val visible = if (page.height == 0) 0..8 else
            ((page.scrollY - top) / rowHeight - SPARE_ROWS)..((page.scrollY + page.height - top) / rowHeight + SPARE_ROWS)
        val wanted = maxOf(0, visible.first)..minOf(count - 1, visible.last)
        if (wanted == shown) return
        for (i in nodes.keys.filter { it !in wanted }) {
            nodes.remove(i)?.forEach(map::removeView)
            rows.remove(journeyIds[i])
        }
        // Rows bind from the latest copy, which polling keeps up to date.
        val latest = store.songs()
        for (i in wanted) if (i !in nodes) {
            val before = map.childCount
            addNode(map, i, latest.optJSONObject(i) ?: songs.getJSONObject(i), i == next, center(i), width)
            nodes[i] = (before until map.childCount).map(map::getChildAt)
        }
        shown = wanted
        trail.invalidate()
    }
    page.setOnScrollChangeListener { _, _, _, _, _ -> refresh() }
    map.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
    refresh()
}

/** Journey rows built beyond each edge of the screen, so a fling never shows a gap. */
private const val SPARE_ROWS = 3

private fun MainActivity.addNode(
    map: FrameLayout,
    index: Int,
    song: JSONObject,
    current: Boolean,
    center: Pair<Float, Float>,
    width: Int,
) {
    val unlocked = store.unlocked(index)
    val done = store.completed(song)
    val stars = store.stars(song)
    val title = SongTitle.display(song.optString("title"), unlocked)
    val size = dp(if (current) 84 else 72)
    val (cx, cy) = center
    if (current) {
        val halo = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x55A78BFA)
            }
        }
        map.addView(halo, FrameLayout.LayoutParams(size, size).apply {
            leftMargin = (cx - size / 2).toInt()
            topMargin = (cy - size / 2).toInt()
        })
        val pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                halo,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f),
                android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, .9f, 0f),
            )
            .apply {
                duration = 1400
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        // A node scrolled out of range is removed, and its endless pulse with it.
        halo.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { if (!pulse.isStarted) pulse.start() }
            override fun onViewDetachedFromWindow(view: View) = pulse.cancel()
        })
    }
    val ring =
        when {
            current -> Palette.ACCENT
            stars == 3 -> Palette.GOLD
            done -> 0xFF7C5CF2.toInt()
            else -> Palette.STROKE
        }
    val node = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.SURFACE)
            setStroke(dp(if (current) 4 else 3), ring)
        }
        setPadding(dp(6), dp(6), dp(6), dp(6))
        contentDescription = "Level ${index + 1}, $title" + if (unlocked) "" else ", locked"
        isClickable = true
        isFocusable = true
        foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF),
            null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(-1) },
        )
        setOnClickListener {
            if (unlocked) prepareSong(song, index)
            else {
                animate().translationX(dp(6).toFloat()).setDuration(50).withEndAction {
                    animate().translationX(-dp(6).toFloat()).setDuration(80).withEndAction {
                        animate().translationX(0f).setDuration(50)
                    }
                }
                Toast.makeText(context, "Clear level $index to unlock", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val disc = FrameLayout(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.SURFACE_HI) }
        clipToOutline = true
        contentDescription =
            if (unlocked) "${song.optString("title")} album cover"
            else "Blurred locked album cover for level ${index + 1}"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    val art = TileArtwork(this, index)
    if (!unlocked) {
        art.alpha = .45f
        if (Build.VERSION.SDK_INT >= 31)
            art.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(12f, 12f, android.graphics.Shader.TileMode.CLAMP))
    }
    disc.addView(art, FrameLayout.LayoutParams(-1, -1))
    disc.addView(
        ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            covers.into(this, song, !unlocked, dp(96))
        },
        FrameLayout.LayoutParams(-1, -1),
    )
    if (!unlocked) disc.addView(ui.icon(Glyph.Kind.LOCK, 0xDDF5F3FA.toInt(), 22), FrameLayout.LayoutParams(-1, -1))
    node.addView(disc, FrameLayout.LayoutParams(-1, -1))
    map.addView(node, FrameLayout.LayoutParams(size, size).apply {
        leftMargin = (cx - size / 2).toInt()
        topMargin = (cy - size / 2).toInt()
    })
    // Level number chip on the node's top edge.
    map.addView(
        ui.text("${index + 1}", 11f, if (unlocked) Palette.ON_ACCENT else Palette.MUTED, ui.heavy).apply {
            gravity = Gravity.CENTER
            background = if (unlocked) ui.rounded(ring.takeIf { it != Palette.STROKE } ?: Palette.ACCENT, 10) else ui.rounded(Palette.SURFACE_HI, 10)
            minWidth = dp(24)
            setPadding(dp(6), dp(3), dp(6), dp(3))
        },
        FrameLayout.LayoutParams(-2, -2).apply {
            leftMargin = (cx - dp(12)).toInt()
            topMargin = (cy - size / 2 - dp(9)).toInt()
        },
    )
    val badge = FrameLayout(this)
    map.addView(badge, FrameLayout.LayoutParams(dp(28), dp(28)).apply {
        leftMargin = (cx + size / 2 - dp(24)).toInt()
        topMargin = (cy + size / 2 - dp(24)).toInt()
    })
    if (done)
        map.addView(
            ui.stars(stars, 15, 1, Palette.FAINT),
            FrameLayout.LayoutParams(-2, -2).apply {
                leftMargin = (cx - dp(25)).toInt()
                topMargin = (cy + size / 2 + dp(2)).toInt()
            },
        )
    // Labels sit on whichever side of the node has more room.
    val gap = dp(16)
    val onRight = cx <= width / 2f
    val labels = ui.column().apply { gravity = if (onRight) Gravity.START else Gravity.END }
    labels.addView(
        ui.oneLine(ui.text(title, 15f, if (unlocked) Palette.TEXT else Palette.MUTED, ui.medium)).apply {
            gravity = if (onRight) Gravity.START else Gravity.END
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    ui.gap(labels, 4)
    val subtitle = ui.oneLine(ui.text("", 12.5f, Palette.MUTED)).apply {
        gravity = if (onRight) Gravity.START else Gravity.END
    }
    labels.addView(subtitle, LinearLayout.LayoutParams(-1, -2))
    val labelWidth = if (onRight) width - (cx + size / 2 + gap).toInt() else (cx - size / 2 - gap).toInt()
    map.addView(labels, FrameLayout.LayoutParams(labelWidth, -2).apply {
        leftMargin = if (onRight) (cx + size / 2 + gap).toInt() else 0
        topMargin = (cy - dp(20)).toInt()
    })
    val row = SongRow(index, subtitle, badge)
    rows[song.optString("id")] = row
    bindRow(row, song)
}

/** Refreshes the parts of a node that change while songs download. */
private fun MainActivity.bindRow(row: SongRow, song: JSONObject) {
    val unlocked = store.unlocked(row.index)
    val secs = song.optDouble("duration").toInt()
    val length = if (secs > 150 && store.prefs.getBoolean("shortLevels", true)) "Highlights · ≤2:30"
        else if (secs > 0) String.format(Locale.US, "%d:%02d", secs / 60, secs % 60) else ""
    row.subtitle.text = listOf(song.optString("artist"), length).filter { it.isNotBlank() }.joinToString(" · ")
    row.trailing.removeAllViews()
    if (unlocked && !store.completed(song)) {
        row.trailing.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.ACCENT)
            setStroke(dp(2), Palette.BG)
        }
        row.trailing.addView(ui.icon(Glyph.Kind.PLAY, Palette.ON_ACCENT, 14), FrameLayout.LayoutParams(-1, -1))
    } else row.trailing.background = null
}

internal fun MainActivity.startPolling(immediately: Boolean = false) {
    if (polling || !foreground || store.playlist == null || isOriginals) return
    polling = true
    handler.postDelayed(::pollLibrary, if (immediately) 0L else 1000L)
}

/**
 * Follows preparation progress once a second. Playlists unchanged since the last look are not
 * read, and only rows whose song changed are rebound.
 */
private fun MainActivity.pollLibrary() {
    if (!foreground || screen != Screen.HOME || store.playlist == null) {
        polling = false
        return
    }
    val id = store.playlist!!.optString("id")
    val seen = shownRevision
    val before = shownSongs
    val checkHero = hero != null && android.os.SystemClock.elapsedRealtime() - heroCheckedAt >= 3000
    if (checkHero) heroCheckedAt = android.os.SystemClock.elapsedRealtime()
    // Songs that failed are tried again once their wait is over, while the player looks on.
    if (++polls % 30 == 0) Preparation.wake(this)
    io.execute {
        val readiness = if (checkHero) runCatching { Preparation.readiness(this)[id] }.getOrNull() else null
        val revision = library.revision(id)
        val playlist = if (revision == seen) null else library.snapshot(id)
        val texts = playlist?.getJSONArray("songs")?.let { songs -> (0 until songs.length()).map { songs.getJSONObject(it).toString() } }
        val changed = texts?.indices?.filter { before.getOrNull(it) != texts[it] }.orEmpty()
        handler.post {
            polling = false
            if (screen != Screen.HOME || isDestroyed) return@post
            if (store.playlist?.optString("id") == id) readiness?.let(::showHeroReadiness)
            if (store.playlist?.optString("id") != id || playlist == null || texts == null) {
                startPolling()
                return@post
            }
            shownRevision = revision
            shownSongs = texts
            if (changed.isNotEmpty()) {
                store.save(playlist)
                val songs = playlist.getJSONArray("songs")
                val ids = (0 until songs.length()).map { songs.getJSONObject(it).optString("id") }
                if (ids == journeyIds) {
                    for (i in changed) rows[ids[i]]?.let { bindRow(it, songs.getJSONObject(i)) }
                } else {
                    val y = scroll?.scrollY ?: 0
                    showHome()
                    scroll?.post { scroll?.scrollTo(0, y) }
                    return@post
                }
            }
            startPolling()
        }
    }
}

/** Dims the next level's button and says when it will be playable, while it isn't ready. */
private fun MainActivity.showHeroReadiness(state: Preparation.Readiness) {
    val (play, status) = hero ?: return
    val ready = state == Preparation.Readiness.Ready
    play.alpha = if (ready) 1f else .5f
    status.visibility = if (ready) View.GONE else View.VISIBLE
    status.text = when (state) {
        is Preparation.Readiness.Waiting -> "Getting this level ready · playable in ${Preparation.describe(state.ms)}"
        Preparation.Readiness.Offline -> "Connect to the internet to get this level ready"
        Preparation.Readiness.Failed -> "Couldn't prepare this level · tap Play to retry"
        else -> ""
    }
}
