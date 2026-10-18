package com.keyflow.pianotiles

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*
import com.keyflow.pianotiles.arcade.Arcade
import com.keyflow.pianotiles.game.GameSession
import com.keyflow.pianotiles.library.Preparation
import com.keyflow.pianotiles.library.SongTitle
import com.keyflow.pianotiles.ui.Glyph
import com.keyflow.pianotiles.ui.Palette
import com.keyflow.pianotiles.ui.Ui
import java.util.Locale
import org.json.JSONObject

private fun MainActivity.modeColors(mode: Arcade.Mode) =
    if (mode == Arcade.Mode.TIME) intArrayOf(0xFF38BDF8.toInt(), 0xFF6366F1.toInt())
    else intArrayOf(0xFFFB923C.toInt(), 0xFFE11D48.toInt())

private fun MainActivity.modeGlyph(mode: Arcade.Mode) =
    if (mode == Arcade.Mode.TIME) Glyph.Kind.BOLT else Glyph.Kind.FLAME

private fun MainActivity.bestLabel(mode: Arcade.Mode): String {
    val best =
        if (mode == Arcade.Mode.TIME)
            Arcade.MINUTES.maxOf { store.prefs.getInt("best.time.$it", 0) }
        else store.prefs.getInt("best.endless", 0)
    return if (best > 0) String.format(Locale.US, "Best %,d", best) else "No runs yet"
}

internal fun MainActivity.addArcadeCards(body: LinearLayout) {
    ui.fill(body, ui.caption("Arcade").apply { setPadding(dp(4), 0, 0, dp(10)) })
    val cards = ui.row()
    for ((i, mode) in Arcade.Mode.values().withIndex()) {
        if (i > 0) ui.gap(cards, 12)
        val card = ui.column().apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            ui.pressable(
                this,
                GradientDrawable(GradientDrawable.Orientation.TL_BR, modeColors(mode)).apply {
                    cornerRadius = dp(20).toFloat()
                },
                20,
            )
            contentDescription = mode.title
            setOnClickListener { showArcade(mode) }
        }
        card.addView(ui.icon(modeGlyph(mode), Palette.TEXT, 28), LinearLayout.LayoutParams(-2, -2))
        ui.gap(card, 18)
        card.addView(ui.text(mode.title, 17f, Palette.TEXT, ui.heavy))
        ui.gap(card, 4)
        card.addView(ui.oneLine(ui.text(bestLabel(mode), 12.5f, 0xDDFFFFFF.toInt(), ui.medium)))
        ui.weight(cards, card)
    }
    ui.fill(body, cards)
}

/** Every song from every playlist, plus the built-in songs. */
private fun MainActivity.arcadePool(): List<Arcade.Entry> = Arcade.pool(library.readPlaylists() + store.demo())

private fun MainActivity.showArcade(mode: Arcade.Mode) {
    Preparation.wake(this)
    var minutes = store.prefs.getInt("arcade.minutes", 2).takeIf { it in Arcade.MINUTES } ?: 2
    val start = ui.button("Start", Ui.Style.PRIMARY, modeGlyph(mode)) { startArcade(mode, minutes) }
    val body = page(Screen.ARCADE, bar(null) { showHome() }, ui.column().apply { ui.fill(this, start) }, centered = true)
    val medal = ui.icon(modeGlyph(mode), Palette.TEXT, 46).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, modeColors(mode)).apply {
            shape = GradientDrawable.OVAL
        }
    }
    body.addView(medal, LinearLayout.LayoutParams(dp(104), dp(104)))
    ui.gap(body, 20)
    body.addView(ui.title(mode.title, 30f))
    ui.gap(body, 8)
    body.addView(
        ui.text(
            if (mode == Arcade.Mode.TIME) "Score as much as you can before the clock runs out."
            else "Keep your lives as long as you can. Every song is faster.",
            15f,
            Palette.MUTED,
        ).apply { gravity = Gravity.CENTER },
    )
    ui.gap(body, 28)
    val best = ui.text("", 26f, Palette.GOLD, ui.heavy).apply { gravity = Gravity.CENTER }
    val bestDetail = ui.text("", 13f, Palette.MUTED).apply { gravity = Gravity.CENTER }
    fun refreshBest() {
        val key = if (mode == Arcade.Mode.TIME) "best.time.$minutes" else "best.endless"
        val value = store.prefs.getInt(key, 0)
        best.text = if (value > 0) String.format(Locale.US, "%,d", value) else "—"
        bestDetail.text =
            if (mode == Arcade.Mode.ENDLESS && value > 0) "${store.prefs.getInt("best.endless.songs", 0)} songs survived"
            else if (mode == Arcade.Mode.TIME) "Best in $minutes min" else "Best run"
    }
    if (mode == Arcade.Mode.TIME) {
        body.addView(ui.caption("Duration"))
        ui.gap(body, 10)
        body.addView(
            ui.segmented(Arcade.MINUTES.map { "$it min" }, Arcade.MINUTES.indexOf(minutes), Palette.SURFACE) {
                minutes = Arcade.MINUTES[it]
                store.prefs.edit().putInt("arcade.minutes", minutes).apply()
                refreshBest()
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        ui.gap(body, 20)
    }
    val card = ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL }
    card.addView(ui.icon(Glyph.Kind.STAR, Palette.GOLD, 22))
    ui.gap(card, 6)
    card.addView(best)
    ui.gap(card, 2)
    card.addView(bestDetail)
    body.addView(card, LinearLayout.LayoutParams(-1, -2))
    refreshBest()
    ui.gap(body, 20)
    body.addView(ui.text("Random songs from the levels you've reached", 13f, Palette.MUTED))
}

private fun MainActivity.startArcade(mode: Arcade.Mode, minutes: Int) {
    if (library.savedPlaylists().isEmpty()) {
        AlertDialog.Builder(this)
            .setTitle("Import a playlist first?")
            .setMessage(
                "Arcade modes play random songs from your playlists. Without one, only the 3 built-in songs repeat.",
            )
            .setPositiveButton("Import playlist") { _, _ -> showAddPlaylist() }
            .setNegativeButton("Play anyway") { _, _ -> beginRun(mode, minutes) }
            .show()
    } else beginRun(mode, minutes)
}

private fun MainActivity.beginRun(mode: Arcade.Mode, minutes: Int) {
    run = Arcade.Run(mode, minutes)
    nextStage()
}

private fun MainActivity.nextStage() {
    val r = run ?: return
    val op = ++operation
    // Catalog reads and readiness checks are disk work, including when advancing mid-run.
    io.execute {
        val offline = store.offlineCheck()
        val ready = arcadePool().filter { offline(it.song) }
        val entry = arcadeSongs.pick(ready, r.recent, kotlin.random.Random.Default)
            ?.also { arcadeSongs.played(it.id) }
        if (entry == null) {
            handler.post { if (operation == op && run === r && !isDestroyed) finishRun() }
            return@execute
        }
        val song = entry.song
        runCatching { if (entry.demo) store.prepareDemo(song) }.onFailure { error ->
            handler.post { if (operation == op && !isDestroyed) { finishRun(); showError(error) } }
            return@execute
        }
        val durationMs = (song.optDouble("duration", 0.0) * 1000).toInt()
        val (start, end) = Arcade.segment(durationMs, kotlin.random.Random.Default)
        handler.post {
            if (operation != op || isDestroyed || run !== r) return@post
            r.remember(entry.id)
            // Each song played goes stale, so Arcade asks for a fresh one.
            Preparation.wake(this)
            launchGame(song, -1, Stage(start, end))
        }
    }
}

internal fun MainActivity.stageFinished(session: GameSession, timeLeft: Long) {
    val r = run ?: return
    stopGame()
    r.absorb(session, timeLeft)
    if (session.failed() || (r.mode == Arcade.Mode.TIME && r.timeLeftMs <= 0)) finishRun()
    else nextStage()
}

internal fun MainActivity.finishRun() {
    val r = run ?: return showHome()
    run = null
    stopGame()
    val previous = store.prefs.getInt(r.bestKey, 0)
    val edit = store.prefs.edit()
    if (r.score > previous) {
        edit.putInt(r.bestKey, r.score)
        if (r.mode == Arcade.Mode.ENDLESS) edit.putInt("best.endless.songs", r.songs)
    }
    edit.apply()
    val xpBefore = store.xp
    val gained = r.score / 60 + r.songs * 15
    store.prefs.edit().putInt("xp", xpBefore + gained).apply()
    store.markPlayedToday()
    val footer = ui.column()
    ui.fill(footer, ui.button("Play again", Ui.Style.PRIMARY, modeGlyph(r.mode)) { startArcade(r.mode, r.minutes) })
    ui.gap(footer, 4)
    ui.fill(footer, ui.button("Home", Ui.Style.GHOST) { showHome() })
    val body = page(Screen.RESULTS, bar(null, Glyph.Kind.CLOSE) { showHome() }, footer, centered = true)
    body.addView(
        ui.icon(modeGlyph(r.mode), Palette.TEXT, 46).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, modeColors(r.mode)).apply {
                shape = GradientDrawable.OVAL
            }
        },
        LinearLayout.LayoutParams(dp(96), dp(96)),
    )
    ui.gap(body, 18)
    body.addView(ui.title(if (r.mode == Arcade.Mode.TIME) "Time's up!" else "Run over", 30f))
    ui.gap(body, 6)
    body.addView(
        ui.text(
            if (r.mode == Arcade.Mode.TIME) "${r.mode.title} · ${r.minutes} min" else r.mode.title,
            15f,
            Palette.MUTED,
        ),
    )
    resultsScore(body, r.score, previous)
    resultsXp(body, xpBefore, gained)
    ui.gap(body, 14)
    resultsStats(
        body,
        listOf(
            "${r.songs}" to "Songs",
            "${(r.accuracy() * 100).toInt()}%" to "Accuracy",
            "${r.maxCombo}" to "Best combo",
            "${r.misses}" to "Misses",
        ),
    )
    resultsTiming(body)
    if (r.score > previous && r.score > 0) celebrate()
}

/** A song's title as Arcade shows it: levels the player hasn't reached keep theirs hidden. */
internal fun MainActivity.reachedTitle(song: JSONObject): String {
    val id = song.optString("id")
    val reached = song.optBoolean("demo") || store.completed(song) || library.readPlaylists().any { playlist ->
        val songs = playlist.optJSONArray("songs") ?: return@any false
        val next = (0 until songs.length()).firstOrNull { !store.completed(songs.getJSONObject(it)) } ?: songs.length()
        (0..minOf(next, songs.length() - 1)).any { songs.getJSONObject(it).optString("id") == id }
    }
    return SongTitle.display(song.optString("title"), reached)
}
