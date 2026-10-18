package com.keyflow.pianotiles

import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.keyflow.pianotiles.arcade.Arcade
import com.keyflow.pianotiles.chart.LevelExcerpt
import com.keyflow.pianotiles.chart.PreparedChart
import com.keyflow.pianotiles.game.GameClock
import com.keyflow.pianotiles.game.GameSession
import com.keyflow.pianotiles.game.GameView
import com.keyflow.pianotiles.game.MediaClock
import com.keyflow.pianotiles.game.TileVariety
import com.keyflow.pianotiles.game.TimingCalibration
import com.keyflow.pianotiles.library.LibraryStore
import com.keyflow.pianotiles.library.Preparation
import com.keyflow.pianotiles.ui.Confetti
import com.keyflow.pianotiles.ui.Glyph
import com.keyflow.pianotiles.ui.Palette
import com.keyflow.pianotiles.ui.Ui
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject

private class Rhythm(val beats: DoubleArray, val barPhase: Int, val bpm: Double)
private const val CHORD = "chord"

internal fun MainActivity.prepareSong(song: JSONObject, index: Int) {
    stopGame()
    currentSong = song
    currentIndex = index
    val run = ++operation
    val playlistId = store.playlist!!.optString("id")
    if (song.optBoolean("demo") || store.offline(song)) {
        io.execute {
            runCatching { if (song.optBoolean("demo")) store.prepareDemo(song) }
                .onSuccess {
                    handler.post { if (operation == run && !isDestroyed) launchGame(song, index) }
                }
                .onFailure { error ->
                    handler.post {
                        if (operation == run && !isDestroyed) {
                            showHome()
                            showError(error)
                        }
                    }
                }
        }
        return
    }
    val body =
        page(
            Screen.LOADING,
            bar(null, Glyph.Kind.CLOSE) {
                operation++
                showHome()
            },
            centered = true,
        )
    ui.gap(body, 12)
    val cover = artwork(index, 24)
    cover.addView(
        ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            covers.into(this, song, false, dp(360))
        },
        FrameLayout.LayoutParams(-1, -1),
    )
    body.addView(cover, LinearLayout.LayoutParams(dp(240), dp(240)))
    ui.gap(body, 28)
    body.addView(ui.caption("Level ${index + 1}", Palette.ACCENT))
    ui.gap(body, 8)
    body.addView(
        ui.text(song.optString("title"), 22f, Palette.TEXT, ui.medium).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        },
    )
    ui.gap(body, 4)
    body.addView(ui.oneLine(ui.text(song.optString("artist"), 14f, Palette.MUTED)))
    ui.gap(body, 32)
    val bar = ui.meter(.02f, height = 10)
    body.addView(bar, LinearLayout.LayoutParams(dp(260), -2))
    ui.gap(body, 12)
    val status = ui.text("Preparing song…", 14f, Palette.TEXT, ui.medium)
    body.addView(status)
    // Once the song is downloaded, a quick level lets the player go on before the full one is made.
    val quick = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
    }
    ui.gap(quick, 28)
    quick.addView(ui.button("Play now with a quick level", Ui.Style.PRIMARY, Glyph.Kind.PLAY) {
        playQuickLevel(playlistId, song, index, run, status, quick)
    }, LinearLayout.LayoutParams(dp(280), -2))
    ui.gap(quick, 10)
    quick.addView(ui.text(if (Preparation.fullLevels(this)) "Made from the beat in seconds. The full level replaces it once it's ready."
        else "Made from the beat in seconds.", 12.5f, Palette.MUTED)
        .apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(280), -2))
    body.addView(quick)
    Preparation.request(this, playlistId, song.optString("id"))
    waitForSong(playlistId, song, index, run, status, bar, quick, System.currentTimeMillis())
}

/** Makes the song's quick level and plays it; the full level keeps being made afterwards. */
private fun MainActivity.playQuickLevel(playlistId: String, song: JSONObject, index: Int, run: Int, status: TextView, quick: View) {
    quick.visibility = View.GONE
    quick.tag = QUICK_STARTED
    status.text = "Making a quick level…"
    kotlin.concurrent.thread(name = "quick-level") {
        val result = runCatching {
            library.quickLevel(song) { fraction ->
                handler.post { if (operation == run && quick.tag == QUICK_STARTED) status.text = "Making a quick level · ${(fraction * 100).toInt()}%" }
            }
            library.snapshot(playlistId) ?: error("Playlist not found. Import it again.")
        }
        handler.post {
            if (operation != run || isDestroyed) return@post
            result.onSuccess { playlist ->
                val songs = playlist.getJSONArray("songs")
                val updated = (0 until songs.length()).map(songs::getJSONObject).firstOrNull { it.optString("id") == song.optString("id") }
                    ?: return@onSuccess showError(IllegalStateException("Song not found. Import the playlist again."))
                store.save(playlist)
                launchGame(updated, index)
            }.onFailure { error ->
                quick.tag = null
                showError(error)
            }
        }
    }
}

private const val QUICK_STARTED = "started"

private fun MainActivity.waitForSong(
    playlistId: String,
    song: JSONObject,
    index: Int,
    run: Int,
    status: TextView,
    bar: Ui.Meter,
    quick: View,
    openedAt: Long,
) {
    // Leaving the loading screen lets the song drop back to its place among the wanted songs.
    if (operation != run || isDestroyed) return Preparation.release(song.optString("id"))
    handler.postDelayed(
        {
            if (operation != run || isDestroyed) return@postDelayed Preparation.release(song.optString("id"))
            io.execute {
                val playlist = library.snapshot(playlistId)
                val songs = playlist?.optJSONArray("songs")
                val updated = songs?.let { values ->
                    (0 until values.length())
                        .map { values.getJSONObject(it) }
                        .firstOrNull { it.optString("id") == song.optString("id") }
                }
                handler.post {
                    if (operation != run || isDestroyed) return@post
                    if (updated == null) {
                        // YouTube wouldn't serve it, so it left the playlist; home says so.
                        Preparation.release(song.optString("id"))
                        if (playlist != null) store.save(playlist)
                        showHome()
                        if (!store.unavailable(song.optString("id")))
                            showError(IllegalStateException("Song not found. Import the playlist again."))
                        return@post
                    }
                    val downloaded = store.downloaded(updated)
                    // An error from before the player opened the song is being retried now.
                    val failed = updated.optString("status") == "error" && updated.optLong("failedAt") >= openedAt
                    val making = quick.tag == QUICK_STARTED
                    when {
                        store.offline(updated) -> {
                            store.save(playlist!!)
                            launchGame(updated, index)
                        }
                        failed && !downloaded -> {
                            store.save(playlist!!)
                            showHome()
                            showError(IllegalStateException("Couldn't download this song. Try again when you have an internet connection."))
                        }
                        else -> {
                            if (downloaded && !making) quick.visibility = View.VISIBLE
                            val target = downloadFraction(updated)
                            if (target != null && target > bar.fraction)
                                android.animation.ValueAnimator.ofFloat(bar.fraction, target).apply {
                                    duration = 500
                                    addUpdateListener { bar.fraction = it.animatedValue as Float }
                                    start()
                                }
                            if (!making) status.text = when {
                                failed -> "Couldn't make the full level"
                                !downloaded && !Preparation.connected(this) -> "Waiting for an internet connection"
                                else -> downloadStage(updated)
                            }
                            waitForSong(playlistId, song, index, run, status, bar, quick, openedAt)
                        }
                    }
                }
            }
        },
        600,
    )
}

internal fun MainActivity.launchGame(song: JSONObject, index: Int, stage: Stage? = null) {
    stopGame()
    currentSong = song
    currentIndex = index
    val op = ++operation
    val r = run
    val seed = random.nextInt()
    val id = song.optString("id")
    library.protectPlayback(id)
    protectedSongId = id
    val previousLayout = store.prefs.getInt("layout.$id", -1)
    io.execute {
        runCatching {
            val chart = store.chart(song)
            // Each play of a long journey level takes one of its best cuts, never the last one twice.
            val excerpt = if (r == null && stage == null && store.prefs.getBoolean("shortLevels", true)) {
                val key = "song.$id.highlight"
                LevelExcerpt.pick(PreparedChart.highlights(chart), store.prefs.getFloat(key, -1f).toDouble().takeIf { it >= 0 },
                    kotlin.random.Random(seed + 1))?.also { store.prefs.edit().putFloat(key, it.start.toFloat()).apply() }
            } else null
            val playStage = stage ?: excerpt?.let { Stage((it.start * 1000).toInt(), (it.end * 1000).toInt()) }
            val raw = chart.getJSONArray("notes")
            val list = ArrayList<GameSession.Note>(raw.length())
            var previous = -1.0
            for (i in 0 until raw.length()) {
                val note = raw.getJSONObject(i)
                val time = note.getDouble("time")
                val lane = note.getInt("lane")
                val kind = note.optString("kind", "tap")
                val duration = note.optDouble("duration", 0.0)
                val endLane = note.optInt("endLane", lane)
                check(time.isFinite() && time >= 0 && time >= previous && lane in 0..3 &&
                    kind in listOf("tap", "hold", "slide") && duration.isFinite() && duration >= 0 &&
                    endLane in 0..3 && (kind == "tap" || duration > 0)) { "This song's chart could not be read. Try preparing it again in Settings." }
                list.add(GameSession.Note(time, lane, kind, duration, endLane))
                previous = time
            }
            val playable = if (playStage != null) Arcade.clip(list, playStage.startMs, playStage.endMs, if (r == null) 1.3 else .4) else list
            check(playable.isNotEmpty()) { "This song has no playable notes." }
            val decorated = TileVariety.decorate(playable, r?.variety ?: index, id.hashCode())
            val layout = TileVariety.shuffleLanes(decorated, kotlin.random.Random(seed), previousLayout)
            val session = if (r == null) GameSession(layout.notes)
                else GameSession(layout.notes, r.lives, r.mode != Arcade.Mode.TIME, r.combo)
            val beats = chart.optJSONArray("beats")?.let { values -> DoubleArray(values.length()) { values.getDouble(it) } }
                ?: song.optDouble("bpm", 0.0).takeIf { it > 0 }?.let { bpm ->
                    val first = list.firstOrNull()?.time ?: 0.0
                    DoubleArray(((chart.optDouble("duration", 0.0) - first) * bpm / 60).toInt().coerceAtLeast(0)) {
                        first + it * 60 / bpm
                    }
                } ?: DoubleArray(0)
            val rhythm = Rhythm(beats, chart.optInt("barPhase", 0), chart.optDouble("bpm", 0.0).takeIf { it > 0 } ?: song.optDouble("bpm", 0.0))
            val path = (if (song.optBoolean("demo")) store.songFile(song, "wav") else store.audioFile(song)).path
            handler.post {
                if (operation != op || isDestroyed || run !== r) return@post
                store.prefs.edit().putInt("layout.$id", layout.key).apply()
                val prepared = MediaPlayer()
                player = prepared
                runCatching {
                    prepared.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    prepared.setDataSource(path)
                    prepared.setOnPreparedListener {
                        if (player !== prepared) return@setOnPreparedListener
                        if (playStage != null && playStage.startMs > 0) {
                            prepared.setOnSeekCompleteListener {
                                if (player === prepared) {
                                    prepared.setOnSeekCompleteListener(null)
                                    showGame(song, session, MediaClock(prepared), playStage, rhythm)
                                }
                            }
                            prepared.seekTo(playStage.startMs)
                        } else showGame(song, session, MediaClock(prepared), playStage, rhythm)
                    }
                    prepared.setOnErrorListener { _, _, _ ->
                        if (player === prepared) {
                            stopGame(); run = null; showHome()
                            showError(IllegalStateException("This song couldn't play. Try another song."))
                        }
                        true
                    }
                    prepared.prepareAsync()
                }.onFailure { error -> stopGame(); run = null; showHome(); showError(error) }
            }
        }.onFailure { error -> handler.post {
            if (operation == op && !isDestroyed) { stopGame(); run = null; showHome(); showError(error) }
        } }
    }
}


/** Long silent or tile-free outros fade out a few seconds after the last tile instead. */
private fun MainActivity.outroEnd(session: GameSession, clock: GameClock): Int {
    val last = session.notes.maxOfOrNull { it.time + it.duration } ?: return 0
    val end = (last * 1000).toInt() + 3500
    return if (clock.durationMs > 0 && end < clock.durationMs - 1500) end else 0
}

private fun MainActivity.showGame(song: JSONObject, session: GameSession, clock: GameClock, stage: Stage?, rhythm: Rhythm) {
    screen = Screen.GAME
    rows.clear()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
    applyInsets(root, false)
    val prefs = store.prefs
    val r = run
    // Every level names its song; Arcade keeps the titles of levels not reached yet hidden.
    val caption = if (r == null) song.optString("title") else reachedTitle(song)
    val detail =
        when {
            r == null -> if (stage != null) "Highlights · up to 2½ minutes" else ""
            r.mode == Arcade.Mode.ENDLESS ->
                "Song ${r.songs + 1}  ·  Speed ×" + String.format(Locale.US, "%.2f", r.speed ?: 1f)
            else -> "Song ${r.songs + 1}"
        }
    val speed = r?.speed ?: prefs.getFloat("speed", 1f)
    lateinit var view: GameView
    view =
        GameView(
            this,
            session,
            clock,
            GameView.Options(
                speed = speed,
                offsetMs = prefs.getInt("timingOffset", 0),
                travel = r?.travel ?: GameView.travelForTempo(GameView.TRAVEL[prefs.getInt("noteSpeed", 1).coerceIn(0, 2)], rhythm.bpm),
                haptics = prefs.getBoolean("haptics", true),
                hint = r == null && !prefs.getBoolean("tutorialSeen", false),
                introduce = newKinds(session),
                startMs = stage?.startMs ?: 0,
                endMs = stage?.endMs?.takeIf { it > 0 } ?: outroEnd(session, clock),
                timeLimitMs = if (r?.mode == Arcade.Mode.TIME) r.timeLeftMs else 0,
                baseScore = r?.score ?: 0,
                caption = caption,
                detail = detail,
                segmentDone = if (r != null) "Next song!" else "Song complete!",
                beats = rhythm.beats,
                barPhase = rhythm.barPhase,
            ),
            object : GameView.Listener {
                override fun finished(session: GameSession) {
                    handler.post {
                        learnTiming(session, speed)
                        if (run != null) stageFinished(session, view.timeLeftMs)
                        else finishGame(song, session)
                    }
                }

                override fun pauseRequested() {
                    showPause()
                }
            },
        )
    game = view
    store.markPlayed(song)
    root.addView(view, FrameLayout.LayoutParams(-1, -1))
    setContentView(root)
    if (!foreground) showPause()
}

/** Moves the timing offset toward where this run's hits landed; see [TimingCalibration]. */
private fun MainActivity.learnTiming(session: GameSession, speed: Float) {
    timingNote = null
    val prefs = store.prefs
    if (!prefs.getBoolean("autoTiming", true)) return
    val errors = session.timingErrorsMs()
    val lean = TimingCalibration.lean(errors, speed) ?: return
    val before = prefs.getInt("timingOffset", 0)
    val after = TimingCalibration.adjust(before, lean)
    android.util.Log.i("Keyflow", "Timing: ${errors.size} hits leaned ${lean.roundToInt()} ms; offset $before -> $after ms")
    if (after == before) return
    prefs.edit().putInt("timingOffset", after).apply()
    val side = if (lean > 0) "late" else "early"
    timingNote = "Hits leaned ${abs(lean).roundToInt()} ms $side · timing offset now ${signedMs(after)}"
}

internal fun MainActivity.signedMs(ms: Int) = if (ms > 0) "+$ms ms" else "$ms ms"

/** Tile kinds in this level the player has never seen; each is introduced once. */
private fun MainActivity.newKinds(session: GameSession): List<String> {
    val notes = session.notes
    val present = ArrayList<String>()
    if (notes.any { it.kind == GameSession.GOLD }) present.add(GameSession.GOLD)
    if ((1 until notes.size).any { abs(notes[it].time - notes[it - 1].time) < .001 && notes[it].kind != GameSession.AVOID && notes[it - 1].kind != GameSession.AVOID })
        present.add(CHORD)
    if (notes.any { it.kind == GameSession.AVOID }) present.add(GameSession.AVOID)
    val fresh = present.filter { !store.prefs.getBoolean("seen.$it", false) }
    if (fresh.isNotEmpty())
        store.prefs.edit().apply { fresh.forEach { putBoolean("seen.$it", true) } }.apply()
    return fresh
}

internal fun MainActivity.showPause() {
    val view = game ?: return
    if (pauseOverlay != null) return
    view.pause()
    val root = view.parent as? ViewGroup ?: return
    val overlay = FrameLayout(this).apply {
        setBackgroundColor(0xD90D0C12.toInt())
        isClickable = true
    }
    val panel = ui.column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(32), dp(32), dp(32), dp(32))
    }
    panel.addView(ui.title("Paused", 30f), LinearLayout.LayoutParams(-2, -2))
    ui.gap(panel, 8)
    panel.addView(
        ui.oneLine(ui.text(currentSong?.let { if (run != null) reachedTitle(it) else it.optString("title") } ?: "", 15f, Palette.MUTED)).apply {
            gravity = Gravity.CENTER
        },
    )
    ui.gap(panel, 32)
    fun add(button: View) {
        panel.addView(button, LinearLayout.LayoutParams(dp(240), -2))
        ui.gap(panel, 10)
    }
    add(ui.button("Resume", Ui.Style.PRIMARY, Glyph.Kind.PLAY) { hidePause() })
    if (run == null) {
        add(ui.button("Restart", Ui.Style.SECONDARY, Glyph.Kind.RETRY) {
            currentSong?.let { prepareSong(it, currentIndex) }
        })
        add(ui.button("Quit", Ui.Style.GHOST) {
            stopGame()
            showHome()
        })
    } else
        add(ui.button("End run", Ui.Style.SECONDARY) {
            val session = view.session
            val left = view.timeLeftMs
            stopGame()
            run?.absorb(session, left)
            finishRun()
        })
    overlay.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    pauseOverlay = overlay
}

internal fun MainActivity.hidePause() {
    val overlay = pauseOverlay ?: return
    (overlay.parent as? ViewGroup)?.removeView(overlay)
    pauseOverlay = null
    game?.resume()
}

private fun MainActivity.finishGame(song: JSONObject, session: GameSession) {
    stopGame()
    val previousBest = store.score(song)
    val xpBefore = store.xp
    store.record(song, session)
    // The window of prepared levels moves on as soon as a level is cleared.
    Preparation.wake(this)
    val gained = store.award(session)
    store.markPlayedToday()
    store.prefs.edit().putBoolean("tutorialSeen", true).apply()
    val passed = session.passed()
    val hasNext = currentIndex + 1 < store.songs().length()
    val footer = ui.column()
    if (passed && hasNext) {
        ui.fill(
            footer,
            ui.button("Next level", Ui.Style.PRIMARY, Glyph.Kind.PLAY) {
                prepareSong(store.songs().getJSONObject(currentIndex + 1), currentIndex + 1)
            },
        )
        ui.gap(footer, 8)
        ui.fill(footer, ui.button("Retry", Ui.Style.SECONDARY, Glyph.Kind.RETRY) {
            prepareSong(song, currentIndex)
        })
    } else
        ui.fill(footer, ui.button("Retry", Ui.Style.PRIMARY, Glyph.Kind.RETRY) {
            prepareSong(song, currentIndex)
        })
    ui.gap(footer, 4)
    ui.fill(footer, ui.button("Levels", Ui.Style.GHOST) { showHome() })
    val body = page(Screen.RESULTS, bar(null, Glyph.Kind.CLOSE) { showHome() }, footer, centered = true)
    resultsHeader(
        body,
        session.grade(),
        session.stars(),
        when {
            passed -> "Level cleared"
            session.failed() -> "Out of lives"
            else -> "Almost there"
        },
        song.optString("title"),
    )
    if (!passed && !session.failed()) {
        ui.gap(body, 14)
        val hit = if (session.scored == 0) 0 else session.hits * 100 / session.scored
        body.addView(
            ui.text("Hit 75% of notes to clear · you hit $hit%", 13f, Palette.TEXT).apply {
                background = ui.rounded(Palette.SURFACE_HI, 14)
                setPadding(dp(14), dp(8), dp(14), dp(8))
            },
        )
    }
    resultsScore(body, session.score, previousBest)
    resultsXp(body, xpBefore, gained)
    ui.gap(body, 14)
    resultsStats(
        body,
        listOf(
            "${(session.accuracy() * 100).toInt()}%" to "Accuracy",
            "${session.maxCombo}" to "Best combo",
            "${session.perfect}" to "Perfect",
            "${session.misses}" to "Misses",
        ),
    )
    resultsTiming(body)
    if (passed) celebrate()
}

/** Grade medallion, stars popping in one by one, and the headline. */
private fun MainActivity.resultsHeader(body: LinearLayout, grade: String, stars: Int, title: String, subtitle: String) {
    val colors =
        when (grade) {
            "S" -> intArrayOf(0xFFFDE68A.toInt(), 0xFFF59E0B.toInt())
            "A" -> intArrayOf(0xFF99F6E4.toInt(), 0xFF14B8A6.toInt())
            "B" -> intArrayOf(0xFFC4B5FD.toInt(), 0xFF7C5CF2.toInt())
            "C", "D" -> intArrayOf(0xFFBAE6FD.toInt(), 0xFF3B82F6.toInt())
            else -> intArrayOf(0xFF4B4560.toInt(), 0xFF2C2939.toInt())
        }
    val medal =
        ui.text(grade, 46f, if (grade == "F") Palette.MUTED else 0xFF1A1030.toInt(), ui.heavy).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(4), 0x33FFFFFF)
            }
            contentDescription = "Grade $grade"
            scaleX = 0f
            scaleY = 0f
        }
    body.addView(medal, LinearLayout.LayoutParams(dp(104), dp(104)))
    medal.animate().scaleX(1f).scaleY(1f).setStartDelay(80).setDuration(420)
        .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
    ui.gap(body, 16)
    val row = ui.row().apply { contentDescription = "$stars of 3 stars" }
    for (i in 1..3) {
        val star = ui.icon(Glyph.Kind.STAR, if (i <= stars) Palette.GOLD else Palette.STROKE, 40)
        if (i > 1) ui.gap(row, 8)
        row.addView(star)
        if (i <= stars) {
            star.scaleX = 0f
            star.scaleY = 0f
            star.animate().scaleX(1f).scaleY(1f).setStartDelay(380L + i * 200).setDuration(360)
                .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
        }
    }
    body.addView(row)
    ui.gap(body, 16)
    body.addView(ui.title(title, 30f))
    ui.gap(body, 6)
    body.addView(ui.oneLine(ui.text(subtitle, 15f, Palette.MUTED)))
}

/** Score counts up; a gold ribbon marks a new best. */
internal fun MainActivity.resultsScore(body: LinearLayout, score: Int, previousBest: Int) {
    ui.gap(body, 26)
    val label = ui.text("0", 50f, Palette.TEXT, ui.heavy)
    body.addView(label)
    android.animation.ValueAnimator.ofInt(0, score).apply {
        duration = 900
        startDelay = 300
        interpolator = android.view.animation.DecelerateInterpolator()
        addUpdateListener { label.text = String.format(Locale.US, "%,d", it.animatedValue as Int) }
        start()
    }
    ui.gap(body, 8)
    body.addView(
        if (score > previousBest && previousBest > 0)
            ui.text("NEW BEST", 12f, 0xFF1A1030.toInt(), ui.heavy).apply {
                letterSpacing = .12f
                background = ui.rounded(Palette.GOLD, 12)
                setPadding(dp(12), dp(5), dp(12), dp(5))
            }
        else if (previousBest > 0)
            ui.text(String.format(Locale.US, "Best %,d", previousBest), 13f, Palette.MUTED)
        else ui.caption("Score"),
    )
}

/** Experience bar that fills with what this run earned, flagging level-ups. */
internal fun MainActivity.resultsXp(body: LinearLayout, before: Int, gained: Int) {
    ui.gap(body, 24)
    val card = ui.card()
    val head = ui.row()
    val levelBefore = LibraryStore.level(before)
    val levelAfter = LibraryStore.level(before + gained)
    val levelLabel = ui.text("Level $levelBefore", 14f, Palette.TEXT, ui.medium)
    ui.weight(head, levelLabel)
    head.addView(ui.text("+$gained XP", 14f, Palette.GOLD, ui.heavy))
    ui.fill(card, head)
    ui.gap(card, 10)
    val meter = ui.meter(LibraryStore.levelFraction(before), Palette.GOLD, 10)
    ui.fill(card, meter)
    body.addView(card, LinearLayout.LayoutParams(-1, -2))
    val target = if (levelAfter > levelBefore) 1f else LibraryStore.levelFraction(before + gained)
    android.animation.ValueAnimator.ofFloat(meter.fraction, target).apply {
        duration = 800
        startDelay = 700
        addUpdateListener { meter.fraction = it.animatedValue as Float }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (levelAfter <= levelBefore) return
                meter.fraction = LibraryStore.levelFraction(before + gained)
                levelLabel.text = "Level $levelAfter  ·  LEVEL UP!"
                levelLabel.setTextColor(Palette.GOLD)
                levelLabel.animate().scaleX(1.08f).scaleY(1.08f).setDuration(160).withEndAction {
                    levelLabel.animate().scaleX(1f).scaleY(1f).setDuration(160)
                }
            }
        })
        start()
    }
}

internal fun MainActivity.resultsStats(body: LinearLayout, values: List<Pair<String, String>>) {
    val stats = ui.row().apply {
        background = ui.rounded(Palette.SURFACE, 20)
        setPadding(dp(8), dp(16), dp(8), dp(16))
    }
    for ((value, label) in values)
        ui.weight(
            stats,
            ui.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ui.text(value, 20f, Palette.TEXT, ui.medium), LinearLayout.LayoutParams(-2, -2))
                ui.gap(this, 4)
                addView(ui.text(label, 11.5f, Palette.MUTED), LinearLayout.LayoutParams(-2, -2))
            },
        )
    body.addView(stats, LinearLayout.LayoutParams(-1, -2))
}

internal fun MainActivity.resultsTiming(body: LinearLayout) {
    val note = timingNote ?: return
    timingNote = null
    ui.gap(body, 10)
    body.addView(ui.text(note, 12.5f, Palette.MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
}

internal fun MainActivity.celebrate() {
    findViewById<FrameLayout>(android.R.id.content)
        .addView(Confetti(this), FrameLayout.LayoutParams(-1, -1))
}

internal fun MainActivity.stopGame() {
    protectedSongId?.let(library::releasePlayback)
    protectedSongId = null
    game?.stop()
    game = null
    player?.release()
    player = null
    pauseOverlay = null
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
