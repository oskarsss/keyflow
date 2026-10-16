package com.keyflow.pianotiles

import android.content.Context
import android.graphics.*
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/** Audio position, so rendering and judgement share one clock. */
interface GameClock {
    val positionMs: Int

    /** Song position for rendering and judgement, finer than [positionMs] where the player allows. */
    val seconds: Double
        get() = positionMs / 1000.0

    /** Song position when an input event happened at [uptimeMs] ([SystemClock.uptimeMillis]). */
    fun secondsAt(uptimeMs: Long): Double = seconds

    val durationMs: Int
    val isPlaying: Boolean

    /** True once playback reached the end, even if the reported position falls short of it. */
    val completed: Boolean
        get() = false

    fun start(speed: Float)

    fun volume(level: Float) {}

    fun pause()
}

class MediaClock(val player: MediaPlayer) : GameClock {
    @Volatile override var completed = false
        private set

    init {
        // VBR MP3 durations are estimates, so the position alone cannot tell when a song is over.
        player.setOnCompletionListener { completed = true }
    }

    override val positionMs
        get() = player.currentPosition

    private val steady = SteadyPosition()

    override val seconds: Double
        get() = steady.update(player.currentPosition / 1000.0, player.isPlaying, System.nanoTime())

    override fun secondsAt(uptimeMs: Long): Double {
        val position = seconds
        if (!steady.running) return position
        val age = (SystemClock.uptimeMillis() - uptimeMs).coerceIn(0L, MAX_INPUT_AGE_MS)
        return position - age / 1000.0 * steady.rate
    }

    override val durationMs
        get() = player.duration

    override val isPlaying
        get() = player.isPlaying

    override fun start(speed: Float) {
        steady.rate = speed.toDouble()
        player.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
        player.start()
    }

    private companion object {
        const val MAX_INPUT_AGE_MS = 100L
    }

    override fun pause() {
        if (player.isPlaying) player.pause()
    }

    override fun volume(level: Float) = player.setVolume(level, level)
}

/**
 * MediaPlayer reports its position in coarse, uneven steps. This follows it with a steady clock
 * that runs at the playback speed and only eases toward the reported position.
 */
class SteadyPosition {
    var rate = 1.0
    private var smoothed = 0.0
    private var smoothedAt = 0L
    private var held = Double.NaN

    /** True once the reported position moves, so extrapolation follows audible output. */
    val running
        get() = smoothedAt != 0L && held.isNaN()

    fun update(reported: Double, playing: Boolean, nanos: Long): Double {
        if (!playing) {
            smoothedAt = 0L
            return reported
        }
        when {
            smoothedAt == 0L -> held = reported
            // Output has not started yet; extrapolating now would run ahead of the music.
            held == reported -> {}
            !held.isNaN() -> held = Double.NaN
            else -> {
                val predicted = smoothed + (nanos - smoothedAt) / 1e9 * rate
                val drift = reported - predicted
                smoothed = if (abs(drift) > RESYNC) reported else predicted + drift * EASE
                smoothedAt = nanos
                return smoothed
            }
        }
        smoothed = reported
        smoothedAt = nanos
        return reported
    }

    private companion object {
        const val RESYNC = .060
        const val EASE = .06
    }
}

/**
 * The playfield is a perspective highway: tiles appear at a glowing horizon and grow as they
 * approach the strike line. Touch lanes are measured at the strike line, where the field is full
 * width, so the perspective never changes what a tap means.
 */
class GameView(
    context: Context,
    val session: GameSession,
    val clock: GameClock,
    private val options: Options,
    private val listener: Listener,
) : View(context) {
    /**
     * [travel] is how many song-seconds a tile takes to fall; [hint] shows first-run instructions
     * and [introduce] lists tile kinds the player has not met yet.
     */
    class Options(
        val speed: Float = 1f,
        val offsetMs: Int = 0,
        val travel: Double = 1.7,
        val haptics: Boolean = true,
        val hint: Boolean = false,
        val introduce: List<String> = emptyList(),
        /** Arcade segments: play from [startMs] until [endMs] (0 = the song's end). */
        val startMs: Int = 0,
        val endMs: Int = 0,
        /** Time Attack clock; 0 disables it. */
        val timeLimitMs: Long = 0,
        /** Score already earned earlier in an arcade run. */
        val baseScore: Int = 0,
        /** Shown under the countdown, such as "Song 3 · Speed ×1.10". */
        val caption: String = "",
        val segmentDone: String = "Song complete!",
        /** Beat times in song seconds, used to pulse the stationary hit targets. */
        val beats: DoubleArray = DoubleArray(0),
        val barPhase: Int = 0,
    )

    interface Listener {
        fun finished(session: GameSession)

        fun pauseRequested()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val heavy = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val density = resources.displayMetrics.density
    // The offset is device latency in real time, so it covers more of the song at higher speeds.
    private val offset = options.offsetMs / 1000.0 * options.speed
    private val particles = HitParticles()
    private val pauseGlyph = Glyph(Glyph.Kind.PAUSE, Palette.TEXT)
    private val heart = Glyph(Glyph.Kind.HEART, Palette.RED)
    private val heartEmpty = Glyph(Glyph.Kind.HEART, 0xFF2C2939.toInt())
    private val starGlyph = Glyph(Glyph.Kind.STAR, 0xFF7A4E0E.toInt())
    private val bombGlyph = Glyph(Glyph.Kind.CLOSE, Palette.RED)

    private var countdownFrom = SystemClock.uptimeMillis()
    private var countdownLength = INTRO_MS
    private var lastFrame = 0L
    private var running = true
    private var started = false
    private var rolling = false
    private var ended = false
    private var endingAt = 0L
    private var renderIndex = 0
    private val path = Path()

    private var observedHits = 0
    private var observedMisses = 0
    private var observedBonuses = 0
    private var observedMultiplier = 1
    private var displayedScore = 0f
    private val scoreFormat = java.text.NumberFormat.getIntegerInstance(Locale.US)
    private var hudScore = -1
    private var hudScoreText = "0"
    private var timerSeconds = -1
    private var timerText = ""
    private var observedChords = 0
    private var chordAt = -1000L
    private var judgement = ""
    private var judgementColor = 0
    private var judgementAt = 0L
    private var bannerText = ""
    private var bannerColor = 0
    private var bannerAt = 0L
    private var comboAt = 0L
    private var shakeAt = 0L
    private val laneGlowAt = LongArray(4)
    private val laneGlowColor = IntArray(4)
    private val pointerLanes = SparseIntArray()

    // Geometry, refreshed on size change.
    private var top = 0f
    private var line = 0f
    private var horizon = 0f
    private var cx = 0f
    private var w = 0f

    private var backdrop: Shader? = null
    private var sky: Shader? = null
    private var floor: Shader? = null
    private var feverFloor: Shader? = null
    private var strikeGlow: Shader? = null
    private var danger: Shader? = null
    private val unitShaders = HashMap<Int, LinearGradient>()
    private var gloss: LinearGradient? = null
    private val matrix = Matrix()

    /** Projects the flat playfield (lanes at full width, y linear in time) onto the highway. */
    private val field = Matrix()
    private val touchField = Matrix()
    private val touchPoint = FloatArray(2)
    private var stage: Bitmap? = null
    private val rect = RectF()
    private val lineBuffer = FloatArray(16 * 4)

    init {
        contentDescription = "Four piano lanes. Tap tiles as they reach the line."
        isFocusable = true
    }

    private fun dp(n: Float) = n * density

    private val hud
        get() = dp(64f)

    private val keys
        get() = dp(92f)

    fun pause() {
        if (!running || ended) return
        running = false
        session.suspendGestures()
        pointerLanes.clear()
        if (rolling) clock.pause()
        rolling = false
        invalidate()
    }

    /** Resumes after a short countdown so fingers can find the lanes again. */
    fun resume() {
        if (ended || running) return
        running = true
        countdownFrom = SystemClock.uptimeMillis()
        countdownLength = if (started) RESUME_MS else INTRO_MS
        lastFrame = 0
        invalidate()
    }

    fun stop() {
        ended = true
        running = false
    }

    /** Time Attack: milliseconds left on the run clock. */
    var timeLeftMs = options.timeLimitMs
        private set

    val timedOut
        get() = options.timeLimitMs > 0 && timeLeftMs <= 0

    private var lastTick = 0L

    /** When playback stopped on its own; a song that stops for good has ended. */
    private var silentSince = 0L

    // ------------------------------------------------------------------ perspective

    /** Depth for field progress p: 0 at the strike line, 1 at the horizon edge of the field. */
    private fun depth(p: Float) = maxOf(.35f, 1 + p * (FAR - 1))

    private fun yAt(p: Float) = horizon + (line - horizon) / depth(p)

    /** Screen x of a lane position (0..4) at progress p. */
    private fun xAt(lane: Float, p: Float) = cx + (lane * w / 4 - cx) / depth(p)

    private fun progress(time: Double) = ((time - songTime) / options.travel).toFloat()

    private var songTime = 0.0

    override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
        w = width.toFloat()
        cx = w / 2
        top = hud + dp(6f)
        line = height - keys
        horizon = (FAR * top - line) / (FAR - 1)
        backdrop =
            LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                intArrayOf(0xFF140F26.toInt(), 0xFF0B0A12.toInt(), 0xFF07060B.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        sky =
            RadialGradient(
                cx,
                top,
                w * .8f,
                intArrayOf(0x806D4AE8.toInt(), 0x226D4AE8, 0x00000000),
                floatArrayOf(0f, .45f, 1f),
                Shader.TileMode.CLAMP,
            )
        floor =
            LinearGradient(
                0f,
                top,
                0f,
                line,
                intArrayOf(0xFF0C0A16.toInt(), 0xFF161229.toInt(), 0xFF1F1838.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        feverFloor =
            LinearGradient(
                0f,
                top,
                0f,
                line,
                intArrayOf(0x00000000, 0x33FCD34D, 0x55F59E0B),
                null,
                Shader.TileMode.CLAMP,
            )
        strikeGlow =
            LinearGradient(
                0f,
                line - dp(46f),
                0f,
                line,
                intArrayOf(0x00A78BFA, 0x55A78BFA),
                null,
                Shader.TileMode.CLAMP,
            )
        danger =
            RadialGradient(
                cx,
                (top + line) / 2,
                maxOf(w, line - top) * .75f,
                intArrayOf(0x00000000, 0x77FB7185),
                floatArrayOf(.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        gloss =
            LinearGradient(
                0f,
                0f,
                0f,
                1f,
                intArrayOf(0x55FFFFFF, 0x00FFFFFF),
                null,
                Shader.TileMode.CLAMP,
            )
        unitShaders.clear()
        field.setPolyToPoly(
            floatArrayOf(0f, line, w, line, w, top, 0f, top),
            0,
            floatArrayOf(0f, line, w, line, xAt(4f, 1f), top, xAt(0f, 1f), top),
            0,
            4,
        )
        stage = renderStage(width, height)
    }

    /** Static scenery (sky, floor, lane dividers and rails) is drawn once per size. */
    private fun renderStage(width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        paint.shader = backdrop
        paint.color = Color.WHITE
        c.drawRect(0f, 0f, w, height.toFloat(), paint)
        paint.shader = sky
        paint.alpha = 170
        c.drawRect(0f, 0f, w, line, paint)
        paint.alpha = 255
        path.reset()
        path.moveTo(xAt(0f, 1f), top)
        path.lineTo(xAt(4f, 1f), top)
        path.lineTo(w, line)
        path.lineTo(0f, line)
        path.close()
        paint.shader = floor
        c.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        for (k in 0..4) {
            val edge = k == 0 || k == 4
            paint.strokeWidth = if (edge) dp(2.5f) else dp(1f)
            paint.color = if (edge) 0x99A78BFA.toInt() else 0x22FFFFFF
            c.drawLine(xAt(k.toFloat(), 1f), top, xAt(k.toFloat(), 0f), line, paint)
        }
        paint.style = Paint.Style.FILL
        return bitmap
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stage?.recycle()
        stage = null
    }

    /** World y for field progress p (0 at the strike line, 1 at the far edge). */
    private fun worldY(p: Float) = line - p * (line - top)

    /** A vertical gradient over 0..1, positioned per tile with a matrix instead of reallocating. */
    private fun faceShader(key: Int, colors: IntArray) =
        unitShaders.getOrPut(key) {
            LinearGradient(0f, 0f, 0f, 1f, colors, null, Shader.TileMode.CLAMP)
        }

    private fun text(
        canvas: Canvas,
        value: String,
        size: Float,
        color: Int,
        x: Float,
        y: Float,
        align: Paint.Align = Paint.Align.CENTER,
        face: Typeface = medium,
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = dp(size)
        paint.typeface = face
        paint.textAlign = align
        canvas.drawText(value, x, y, paint)
    }

    private fun alpha(color: Int, fraction: Float) =
        (color and 0xFFFFFF) or ((fraction.coerceIn(0f, 1f) * (color ushr 24)).toInt() shl 24)

    // ------------------------------------------------------------------ frame

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val countdown = (now - countdownFrom).toFloat() / countdownLength
        if (running && endingAt == 0L && countdown >= 1f && !rolling) {
            started = true
            rolling = true
            clock.start(options.speed)
        }
        val time =
            if (started) clock.seconds + offset
            else options.startMs / 1000.0 - (1 - countdown.coerceIn(0f, 1f)) * INTRO_MS / 1000.0
        songTime = time
        if (rolling && options.timeLimitMs > 0) {
            if (lastTick > 0) timeLeftMs -= now - lastTick
            lastTick = now
        } else lastTick = 0
        if (rolling && options.endMs > 0) {
            // Fade segments and long outros out instead of cutting the music.
            val position = clock.positionMs
            val fadeIn = if (options.startMs > 0) (position - options.startMs) / 700f else 1f
            clock.volume(minOf(1f, fadeIn, (options.endMs - position) / 1500f).coerceIn(0f, 1f))
        }
        if (rolling && !clock.isPlaying) {
            if (silentSince == 0L) silentSince = now
        } else silentSince = 0
        if (running && started && endingAt == 0L) {
            session.advance(time)
            if (
                session.failed() ||
                    timedOut ||
                    clock.completed ||
                    (options.endMs > 0 && clock.positionMs >= options.endMs) ||
                    (!clock.isPlaying && clock.positionMs >= clock.durationMs - 100) ||
                    (silentSince > 0 && now - silentSince > 900)
            ) {
                endingAt = now
                rolling = false
                clock.pause()
                pointerLanes.clear()
            }
        }
        if (endingAt > 0 && now - endingAt > (if (session.failed()) 1100 else 800)) {
            if (!ended) {
                ended = true
                running = false
                listener.finished(session)
            }
            return
        }
        observe(now)
        val fever = session.multiplier >= 4 && endingAt == 0L

        canvas.drawColor(0xFF07060B.toInt())
        canvas.save()
        val shake = now - shakeAt
        if (shake < 200) canvas.translate(sin(shake / 16f) * dp(5f) * (1 - shake / 200f), 0f)
        stage?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (fever) {
            paint.shader = sky
            paint.alpha = (90 + 60 * sin(now / 160f)).toInt()
            canvas.drawRect(0f, 0f, w, line, paint)
            paint.shader = null
            paint.alpha = 255
        }
        drawField(canvas, now, time, fever)
        drawNotes(canvas, time, now)
        drawStrike(canvas, now, h)
        drawChordEffect(canvas, now)

        particles.draw(
            canvas,
            paint,
            if (lastFrame == 0L) 0f else minOf(40L, now - lastFrame).toFloat(),
            density,
        )
        lastFrame = now
        drawFeedback(canvas, now)
        canvas.restore()

        drawHud(canvas, time, now, fever)
        if (endingAt > 0) drawEnding(canvas, h, now)
        else if (!started || (running && countdown < 1f)) drawCountdown(canvas, h, countdown)
        else if (!running) {
            paint.color = 0x99000000.toInt()
            canvas.drawRect(0f, hud, w, h, paint)
        }
        if (!ended && (running || endingAt > 0)) postInvalidateOnAnimation()
    }

    /** Turns score and life changes into flashes, particles, banners and haptics. */
    private fun observe(now: Long) {
        val lane = w / 4
        if (session.hits > observedHits) {
            observedHits = session.hits
            if (options.haptics && (session.lastCompletedKind == "hold" || session.lastCompletedKind == "slide"))
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            val perfect = session.lastQuality == 1
            val color = if (perfect) Palette.MINT else Palette.ACCENT
            judgement = if (perfect) "PERFECT" else if (session.lastTimingErrorMs < 0) "GOOD · EARLY" else "GOOD · LATE"
            judgementColor = color
            judgementAt = now
            comboAt = now
            val l = session.lastCompletedLane.coerceIn(0, 3)
            laneGlowAt[l] = now
            laneGlowColor[l] = color
            particles.emit((l + .5f) * lane, line, color, density)
            val combo = session.combo
            if (combo == 25 || combo == 50 || (combo >= 100 && combo % 50 == 0))
                banner("$combo COMBO!", Palette.MINT, now)
        }
        if (session.bonuses > observedBonuses) {
            observedBonuses = session.bonuses
            judgement = "+${GameSession.GOLD_BONUS}  ♥"
            judgementColor = Palette.GOLD
            val l = session.lastCompletedLane.coerceIn(0, 3)
            laneGlowColor[l] = Palette.GOLD
            particles.emit((l + .5f) * lane, line, Palette.GOLD, density)
            particles.emit((l + .5f) * lane, line, 0xFFFFF1B8.toInt(), density)
        }
        if (session.multiplier != observedMultiplier) {
            if (session.multiplier > observedMultiplier)
                banner(
                    if (session.multiplier >= 4) "FEVER ×4" else "×${session.multiplier}",
                    tier(session.multiplier),
                    now,
                )
            observedMultiplier = session.multiplier
        }
        if (session.misses > observedMisses) {
            observedMisses = session.misses
            judgement = "MISS"
            judgementColor = Palette.RED
            judgementAt = now
            shakeAt = now
            val l = session.lastMissLane.coerceIn(0, 3)
            laneGlowAt[l] = now
            laneGlowColor[l] = Palette.RED
            if (options.haptics)
                performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
                    else HapticFeedbackConstants.LONG_PRESS,
                )
        }
        if (session.chordBonuses > observedChords) {
            observedChords = session.chordBonuses
            chordAt = now
            val name = when (session.lastChordLanes.size) { 2 -> "DOUBLE!"; 3 -> "TRIPLE!"; else -> "QUAD!" }
            banner("$name +${session.lastChordBonus}", Palette.GOLD, now)
            for (l in session.lastChordLanes) {
                laneGlowAt[l] = now
                laneGlowColor[l] = Palette.GOLD
                particles.emit((l + .5f) * lane, line, Palette.GOLD, density, 14)
            }
        }
        val target = (options.baseScore + session.score).toFloat()
        displayedScore =
            if (abs(target - displayedScore) < 1) target
            else displayedScore + (target - displayedScore) * .25f
    }

    private fun banner(value: String, color: Int, now: Long) {
        bannerText = value
        bannerColor = color
        bannerAt = now
    }

    private fun tier(multiplier: Int) =
        when (multiplier) {
            2 -> Palette.SKY
            3 -> Palette.MINT
            4 -> Palette.GOLD
            else -> Palette.MUTED
        }

    private fun drawField(canvas: Canvas, now: Long, time: Double, fever: Boolean) {
        canvas.save()
        canvas.concat(field)
        val lane = w / 4
        if (fever) {
            paint.shader = feverFloor
            paint.alpha = (150 + 100 * sin(now / 140f)).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, top, w, line, paint)
            paint.alpha = 255
        }
        // Lane glows after hits and misses.
        for (i in 0..3) {
            val age = now - laneGlowAt[i]
            if (age > 280) continue
            val shader = faceShader(laneGlowColor[i], intArrayOf(0, alpha(laneGlowColor[i], .6f)))
            val from = worldY(.55f)
            matrix.setScale(1f, line - from)
            matrix.postTranslate(0f, from)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            paint.color = Color.WHITE
            paint.alpha = ((1 - age / 280f) * 255).toInt()
            canvas.drawRect(i * lane, from, (i + 1) * lane, line, paint)
            paint.shader = null
            paint.alpha = 255
        }
        for (k in 0 until pointerLanes.size()) {
            val i = pointerLanes.valueAt(k)
            paint.color = 0x14FFFFFF
            canvas.drawRect(i * lane, worldY(.3f), (i + 1) * lane, line, paint)
        }
        // Keep the musical pulse for the fixed receptors. Moving road stripes obscure timing.
        val beats = options.beats
        while (beatIndex > 0 && beats[beatIndex - 1] >= time) beatIndex--
        while (beatIndex < beats.size && beats[beatIndex] < time) beatIndex++
        canvas.restore()
        if (session.lives <= 2 && endingAt == 0L) {
            paint.shader = danger
            paint.color = Color.WHITE
            paint.alpha = ((.55f + .45f * sin(now / 150f)) * 255).toInt()
            canvas.drawRect(0f, top, w, line, paint)
            paint.shader = null
            paint.alpha = 255
        }
        if (session.combo >= 10) {
            val pop = ((now - comboAt) / 140f).coerceIn(0f, 1f)
            val y = yAt(.45f)
            text(canvas, "${session.combo}", 58f + 10f * (1 - pop), 0x24FFFFFF, cx, y, face = heavy)
            text(canvas, "COMBO", 12f, 0x40FFFFFF, cx, y + dp(22f))
        }
    }

    private var beatIndex = 0

    /** 1 right on a beat, fading to 0 shortly after, so the strike line pulses with the music. */
    private fun pulse(time: Double): Float {
        val beats = options.beats
        if (beats.isEmpty() || beatIndex == 0) return 0f
        val since = time - beats[beatIndex - 1]
        return (1 - since / .22).toFloat().coerceIn(0f, 1f)
    }

    private fun drawNotes(canvas: Canvas, time: Double, now: Long) {
        val travel = options.travel
        while (
            renderIndex < session.notes.size &&
                session.notes[renderIndex].time + session.notes[renderIndex].duration < time - .6
        ) renderIndex++
        var last = renderIndex
        while (last < session.notes.size && session.notes[last].time - time <= travel * 1.08) last++
        canvas.save()
        canvas.clipRect(0f, top - dp(2f), w, line + dp(60f))
        canvas.save()
        canvas.concat(field)
        // Far notes first so near tiles overlap them.
        for (i in last - 1 downTo renderIndex) {
            val note = session.notes[i]
            if (!visible(note, time)) continue
            if (note.kind == "hold" || note.kind == "slide") drawRail(canvas, note, time, now)
            else drawTile(canvas, note, time, now, i)
        }
        canvas.restore()
        // Icons stay upright, so they are drawn in screen space on top.
        for (i in renderIndex until last) {
            val note = session.notes[i]
            if (!visible(note, time) || note.judgement == 3) continue
            val icon =
                when (note.kind) {
                    GameSession.GOLD -> starGlyph
                    GameSession.AVOID -> bombGlyph
                    else -> continue
                }
            val pb = progress(note.time)
            val fog = 1 - ((pb - .78f) / .3f).coerceIn(0f, 1f)
            val mx = xAt(note.lane + .5f, pb + TILE / 2)
            val my = yAt(pb + TILE / 2)
            val size = (dp(26f) / depth(pb)).toInt()
            icon.setBounds((mx - size / 2).toInt(), (my - size / 2).toInt(), (mx + size / 2).toInt(), (my + size / 2).toInt())
            icon.alpha = (fog * 255).toInt()
            icon.draw(canvas)
        }
        canvas.restore()
    }

    private fun visible(note: GameSession.Note, time: Double) =
        (note.judgement == 0 || note.judgement == 4) &&
            note.time + note.duration - time >= -.6

    private class Look(val colors: IntArray, val side: Int)

    private fun fog(p: Float) = 1 - ((p - .78f) / .3f).coerceIn(0f, 1f)

    /** A thick tile: a side face below gives it height, the top face is lit toward the player. */
    private fun block(canvas: Canvas, l: Float, r: Float, yb: Float, yt: Float, look: Look, a: Float, thick: Float = dp(11f)) {
        val radius = minOf(dp(9f), (yb - yt) / 2)
        paint.shader = null
        paint.color = alpha(look.side, a)
        canvas.drawRoundRect(l + dp(1f), yb - radius, r - dp(1f), yb + thick, radius, radius, paint)
        val shader = faceShader(look.colors[1] xor look.colors[2], look.colors)
        matrix.setScale(1f, yb - yt)
        matrix.postTranslate(0f, yt)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        paint.color = Color.WHITE
        paint.alpha = (a * 255).toInt()
        rect.set(l, yt, r, yb)
        canvas.drawRoundRect(rect, radius, radius, paint)
        gloss?.let {
            matrix.setScale(1f, (yb - yt) * .55f)
            matrix.postTranslate(0f, yt)
            it.setLocalMatrix(matrix)
            paint.shader = it
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        paint.shader = null
        paint.alpha = 255
    }

    private fun drawTile(canvas: Canvas, note: GameSession.Note, time: Double, now: Long, index: Int) {
        val pb = progress(note.time)
        val missed = note.judgement == 3
        val fade = if (missed) (1 + pb / .3f).coerceIn(0f, 1f) else 1f
        val a = fog(pb) * fade
        if (a <= 0f || pb < -.45f) return
        val lane = w / 4
        val l = note.lane * lane + lane * .07f
        val r = (note.lane + 1) * lane - lane * .07f
        val yb = worldY(pb)
        val yt = worldY(pb + TILE)
        val radius = dp(9f)
        val look =
            when {
                missed -> MISSED
                note.kind == GameSession.GOLD -> GOLD
                note.kind == GameSession.AVOID -> BOMB
                else -> TAP
            }
        block(canvas, l, r, yb, yt, look, a)
        val delta = note.time - time
        val danger = note.kind == GameSession.AVOID
        if (!missed && (danger || abs(delta) <= GameSession.PERFECT_WINDOW)) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = if (danger) alpha(Palette.RED, (.55f + .45f * sin(now / 90f)) * a) else 0xFF81F5CA.toInt()
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
        if (!missed && note.kind == "tap") {
            paint.color = alpha(0x99FFFFFF.toInt(), a)
            canvas.drawRoundRect(l + lane * .2f, yb - dp(14f), r - lane * .2f, yb - dp(10f), dp(2f), dp(2f), paint)
        }
        // Chords: link tiles that share a moment so several fingers feel natural.
        val next = session.notes.getOrNull(index + 1)
        if (
            next != null && abs(next.time - note.time) < .001 && next.kind != GameSession.AVOID &&
                note.kind != GameSession.AVOID && next.judgement == 0 && note.judgement == 0
        ) {
            val from = (minOf(note.lane, next.lane) + 1) * lane - lane * .07f
            val to = maxOf(note.lane, next.lane) * lane + lane * .07f
            paint.color = alpha(0xCCE9E3FF.toInt(), a)
            canvas.drawRoundRect(from, yb - dp(10f), to, yb - dp(4f), dp(3f), dp(3f), paint)
        }
    }

    private var lastSpark = 0L
    private var lastSlideHaptic = 0L

    /**
     * Holds and slides: a tile to press, a glowing ribbon to follow and a cap where it ends. While
     * held, the grip stays on the strike line and the ribbon is consumed into it.
     */
    private fun drawRail(canvas: Canvas, note: GameSession.Note, time: Double, now: Long) {
        val active = note.judgement == 4
        val missed = note.judgement == 3
        val slide = note.kind == "slide"
        val color = if (missed) 0xFF6B4252.toInt() else if (slide) Palette.SKY else Palette.MINT
        val look = if (missed) MISSED else if (slide) SLIDE else HOLD
        val lane = w / 4
        val end = note.time + note.duration
        val pHead = progress(note.time)
        val pEnd = progress(end)
        val fade = if (missed) (1 + pEnd / .3f).coerceIn(0f, 1f) * .7f else 1f
        if (fade <= 0f) return
        val p0 = if (active) 0f else maxOf(pHead, -.45f)
        val p1 = minOf(pEnd, 1.1f)
        if (p1 > p0) {
            val x0 = note.laneAt(time + p0 * options.travel) * lane
            val x1 = note.laneAt(time + p1 * options.travel) * lane
            val y0 = worldY(p0)
            val y1 = worldY(p1)
            val half = lane * (if (slide) .3f else .26f)
            path.reset()
            path.moveTo(x0 - half, y0)
            path.lineTo(x0 + half, y0)
            path.lineTo(x1 + half, y1)
            path.lineTo(x1 - half, y1)
            path.close()
            val body = unitShaders.getOrPut(color xor 0x5A5A5A) {
                LinearGradient(0f, 0f, 0f, 1f,
                    intArrayOf(alpha(color, .04f), alpha(color, .3f), alpha(color, .55f)), null, Shader.TileMode.CLAMP)
            }
            matrix.setScale(1f, maxOf(1f, y0 - y1))
            matrix.postTranslate(0f, y1)
            body.setLocalMatrix(matrix)
            paint.shader = body
            paint.color = Color.WHITE
            paint.alpha = ((if (active) 1f else .8f) * fade * 255).toInt()
            canvas.drawPath(path, paint)
            paint.shader = null
            paint.alpha = 255
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = dp(2f)
            paint.color = alpha(color, .85f * fade)
            canvas.drawLine(x0 - half, y0, x1 - half, y1, paint)
            canvas.drawLine(x0 + half, y0, x1 + half, y1, paint)
            paint.strokeWidth = dp(if (active) 6f else 3.5f)
            paint.color =
                if (active) alpha(Color.WHITE, .7f + .3f * sin(now / 60f)) else alpha(color, .9f * fade)
            canvas.drawLine(x0, y0, x1, y1, paint)
            if (!missed) {
                // Slides point the way with chevrons; holds tick on the song's beats.
                paint.strokeWidth = dp(3f)
                paint.color = alpha(Color.WHITE, .85f * fade)
                var n = 0
                if (slide && note.endLane != note.lane) {
                    val dir = if (note.endLane > note.lane) 1f else -1f
                    for (k in 1..5) {
                        val t = note.time + note.duration * k / 6
                        val p = progress(t)
                        if (p <= p0 || p >= p1) continue
                        val cxw = note.laneAt(t) * lane
                        val cyw = worldY(p)
                        val c = dp(8f)
                        lineBuffer[n++] = cxw - dir * c; lineBuffer[n++] = cyw - c
                        lineBuffer[n++] = cxw + dir * c * .6f; lineBuffer[n++] = cyw
                        lineBuffer[n++] = cxw + dir * c * .6f; lineBuffer[n++] = cyw
                        lineBuffer[n++] = cxw - dir * c; lineBuffer[n++] = cyw + c
                    }
                } else {
                    paint.strokeWidth = dp(2f)
                    paint.color = alpha(Color.WHITE, .45f * fade)
                    val beats = options.beats
                    var k = if (beats.isEmpty()) 0 else beats.binarySearch(note.time).let { if (it < 0) -it - 1 else it + 1 }
                    var t = if (beats.isEmpty()) note.time + BEAT else beats.getOrElse(k) { end }
                    while (t < end - .05 && n + 4 <= lineBuffer.size) {
                        val p = progress(t)
                        if (p > p0 && p < p1) {
                            val cxw = note.laneAt(t) * lane
                            lineBuffer[n++] = cxw - half * .7f; lineBuffer[n++] = worldY(p)
                            lineBuffer[n++] = cxw + half * .7f; lineBuffer[n++] = worldY(p)
                        }
                        t = if (beats.isEmpty()) t + BEAT else beats.getOrElse(++k) { end }
                    }
                }
                if (n > 0) canvas.drawLines(lineBuffer, 0, n, paint)
            }
            paint.style = Paint.Style.FILL
        }
        if (pEnd <= 1.08f) {
            val xe = note.laneAt(end) * lane
            block(canvas, xe - lane * .36f, xe + lane * .36f, worldY(pEnd), worldY(pEnd + TILE * .28f), look, fog(pEnd) * fade, dp(6f))
        }
        if (active) {
            // The grip rides the strike line, sparkling while the finger stays on it.
            val x = note.laneAt(time) * lane
            block(canvas, x - lane * .43f, x + lane * .43f, line, worldY(TILE * .5f), look, 1f)
            paint.color = alpha(Color.WHITE, .25f + .2f * sin(now / 50f))
            canvas.drawRoundRect(x - lane * .43f, worldY(TILE * .5f), x + lane * .43f, line, dp(9f), dp(9f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = Color.WHITE
            canvas.drawCircle(x, line, lane * .26f, paint)
            if (slide) {
                val future = note.laneAt(minOf(end, time + .45)) * lane
                canvas.drawLine(x, line, future, line, paint)
                val dir = if (future > x) 1f else -1f
                canvas.drawLine(future, line, future - dir * dp(8f), line - dp(6f), paint)
                canvas.drawLine(future, line, future - dir * dp(8f), line + dp(6f), paint)
            }
            paint.style = Paint.Style.FILL
            val l = (x / lane).toInt().coerceIn(0, 3)
            laneGlowAt[l] = now - 120
            laneGlowColor[l] = color
            if (now - lastSpark > 55) {
                lastSpark = now
                particles.emit(x, line, color, density, 3)
            }
        } else if (pHead >= -.45f) {
            val l = note.lane * lane + lane * .07f
            val r = (note.lane + 1) * lane - lane * .07f
            val yb = worldY(pHead)
            val yt = worldY(pHead + TILE)
            val a = fog(pHead) * fade
            block(canvas, l, r, yb, yt, look, a)
            if (!missed) {
                val mx = (l + r) / 2
                val my = (yb + yt) / 2
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = dp(3.5f)
                paint.color = alpha(Color.WHITE, .9f * a)
                if (slide) {
                    val dir = if (note.endLane > note.lane) 1f else -1f
                    val c = lane * .14f
                    canvas.drawLine(mx - dir * c, my - c, mx + dir * c * .4f, my, paint)
                    canvas.drawLine(mx + dir * c * .4f, my, mx - dir * c, my + c, paint)
                } else {
                    val c = lane * .16f
                    canvas.drawLine(mx - c, my - dp(5f), mx + c, my - dp(5f), paint)
                    canvas.drawLine(mx - c, my + dp(5f), mx + c, my + dp(5f), paint)
                }
                if (abs(note.time - time) <= GameSession.PERFECT_WINDOW) {
                    paint.strokeWidth = dp(3f)
                    paint.color = 0xFF81F5CA.toInt()
                    canvas.drawRoundRect(rect, dp(9f), dp(9f), paint)
                }
                paint.style = Paint.Style.FILL
            }
        }
    }

    private fun drawStrike(canvas: Canvas, now: Long, h: Float) {
        val beat = if (rolling) pulse(songTime) else 0f
        paint.shader = strikeGlow
        paint.color = Color.WHITE
        paint.alpha = (150 + 105 * beat).toInt()
        canvas.drawRect(0f, line - dp(46f + 14f * beat), w, line, paint)
        paint.shader = null
        paint.alpha = 255
        // The target never moves. The green band is the actual ±80 ms perfect window.
        val early = yAt((GameSession.PERFECT_WINDOW / options.travel).toFloat())
        val late = yAt((-GameSession.PERFECT_WINDOW / options.travel).toFloat())
        paint.color = 0x2581F5CA
        canvas.drawRect(0f, early, w, late, paint)
        paint.color = 0xFFB5FFE5.toInt()
        canvas.drawRect(0f, line - dp(2f), w, line + dp(2f), paint)
        val lane = w / 4
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        for (i in 0..3) {
            val x = (i + .5f) * lane
            canvas.drawRoundRect(x - lane * .34f, early, x + lane * .34f, late, dp(7f), dp(7f), paint)
        }
        paint.style = Paint.Style.FILL
        for (i in 0..3) {
            var pressed = false
            for (k in 0 until pointerLanes.size()) if (pointerLanes.valueAt(k) == i) pressed = true
            val glow = (1 - (now - laneGlowAt[i]) / 240f).coerceIn(0f, 1f)
            val l = i * lane + dp(6f)
            val r = (i + 1) * lane - dp(6f)
            val sink = if (pressed) dp(3f) else 0f
            val t = line + dp(12f) + sink
            val b = h - dp(16f)
            // Raised key: darker base gives it height; pressing sinks the face.
            paint.color = 0xFF07060B.toInt()
            canvas.drawRoundRect(l, line + dp(15f), r, h - dp(10f), dp(16f), dp(16f), paint)
            paint.color = if (pressed) 0xFF2F2752.toInt() else 0xFF1A1727.toInt()
            canvas.drawRoundRect(l, t, r, b, dp(16f), dp(16f), paint)
            if (glow > 0) {
                paint.color = alpha(laneGlowColor[i], .55f * glow)
                canvas.drawRoundRect(l, t, r, b, dp(16f), dp(16f), paint)
            }
            paint.color = if (pressed) 0x66FFFFFF else 0x14FFFFFF
            canvas.drawRoundRect(l + dp(1f), t + dp(1f), r - dp(1f), t + dp(3f), dp(2f), dp(2f), paint)
            paint.color = if (pressed) Palette.ACCENT else alpha(Palette.ACCENT, .25f + .55f * beat)
            canvas.drawCircle((l + r) / 2, (t + b) / 2, dp(4f + 1.5f * beat), paint)
        }
    }

    private fun drawChordEffect(canvas: Canvas, now: Long) {
        val age = (now - chordAt) / 650f
        if (age !in 0f..1f) return
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f) * (1 - age)
        paint.color = alpha(Palette.GOLD, 1 - age)
        for (l in session.lastChordLanes) {
            canvas.drawCircle((l + .5f) * w / 4, line, dp(14f + 54f * age), paint)
        }
        val lanes = session.lastChordLanes
        if (lanes.size >= 2) {
            canvas.drawLine((lanes.min() + .5f) * w / 4, line - dp(10f + age * 28),
                (lanes.max() + .5f) * w / 4, line - dp(10f + age * 28), paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawFeedback(canvas: Canvas, now: Long) {
        val age = now - judgementAt
        if (age < 450 && judgement.isNotEmpty()) {
            val pop = (age / 110f).coerceIn(0f, 1f)
            text(
                canvas,
                judgement,
                20f + 7f * (1 - pop),
                alpha(judgementColor, 1 - ((age - 250) / 200f).coerceIn(0f, 1f)),
                cx,
                line - dp(64f) - dp(6f) * pop,
                face = heavy,
            )
        }
        val b = now - bannerAt
        if (b < 1000 && bannerText.isNotEmpty()) {
            val pop = (b / 160f).coerceIn(0f, 1f)
            val scale = 1.4f - .4f * pop
            val fade = 1 - ((b - 700) / 300f).coerceIn(0f, 1f)
            val y = yAt(.62f)
            text(canvas, bannerText, 34f * scale, alpha(0x66000000, fade), cx + dp(2f), y + dp(2f), face = heavy)
            text(canvas, bannerText, 34f * scale, alpha(bannerColor, fade), cx, y, face = heavy)
        }
    }

    private fun drawHud(canvas: Canvas, time: Double, now: Long, fever: Boolean) {
        paint.shader = null
        paint.color = 0xF20B0A12.toInt()
        canvas.drawRect(0f, 0f, w, hud, paint)
        val duration = clock.durationMs / 1000.0
        paint.color = 0xFF221F2D.toInt()
        canvas.drawRect(0f, 0f, w, dp(3f), paint)
        val from = options.startMs / 1000.0
        val to = if (options.endMs > 0) options.endMs / 1000.0 else duration
        if (to > from) {
            paint.color = if (fever) Palette.GOLD else Palette.ACCENT
            canvas.drawRect(0f, 0f, w * ((time - from) / (to - from)).coerceIn(0.0, 1.0).toFloat(), dp(3f), paint)
        }
        val value = displayedScore.toInt()
        if (value != hudScore) {
            hudScore = value
            hudScoreText = scoreFormat.format(value)
        }
        val score = hudScoreText
        text(canvas, score, 24f, Palette.TEXT, dp(18f), dp(42f), Paint.Align.LEFT, heavy)
        val m = session.multiplier
        if (m > 1) {
            paint.textSize = dp(24f)
            paint.typeface = heavy
            val x = dp(18f) + paint.measureText(score) + dp(10f)
            val color = tier(m)
            val pulse = if (m >= 4) .75f + .25f * sin(now / 110f) else 1f
            paint.color = alpha(color, .22f * pulse)
            canvas.drawRoundRect(x, dp(22f), x + dp(40f), dp(46f), dp(12f), dp(12f), paint)
            text(canvas, MULTIPLIERS[m - 1], 14f, color, x + dp(20f), dp(39f), face = heavy)
        }
        if (options.timeLimitMs > 0) {
            val left = maxOf(0L, timeLeftMs)
            val seconds = ceil(left / 1000.0).toInt()
            val urgent = left < 10_000
            if (seconds != timerSeconds) {
                timerSeconds = seconds
                timerText = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
            }
            val label = timerText
            paint.color = if (urgent) alpha(Palette.RED, .25f + .15f * sin(now / 120f)) else 0xFF221F2D.toInt()
            canvas.drawRoundRect(w - dp(160f), dp(18f), w - dp(66f), dp(50f), dp(16f), dp(16f), paint)
            text(canvas, label, 18f, if (urgent) Palette.RED else Palette.TEXT, w - dp(113f), dp(41f), face = heavy)
            drawPause(canvas)
            return
        }
        val size = dp(13f)
        val gap = dp(3f)
        val total = GameSession.MAX_LIVES * size + (GameSession.MAX_LIVES - 1) * gap
        val start = w - dp(70f) - total
        for (i in 0 until GameSession.MAX_LIVES) {
            val x = start + i * (size + gap)
            val glyph = if (i < session.lives) heart else heartEmpty
            glyph.setBounds(x.toInt(), dp(27f).toInt(), (x + size).toInt(), (dp(27f) + size).toInt())
            glyph.draw(canvas)
        }
        drawPause(canvas)
    }

    private fun drawPause(canvas: Canvas) {
        paint.color = 0xFF221F2D.toInt()
        canvas.drawCircle(w - dp(34f), dp(34f), dp(19f), paint)
        pauseGlyph.setBounds((w - dp(44f)).toInt(), dp(24f).toInt(), (w - dp(24f)).toInt(), dp(44f).toInt())
        pauseGlyph.draw(canvas)
    }

    private fun drawCountdown(canvas: Canvas, h: Float, progress: Float) {
        paint.color = 0xA6000000.toInt()
        canvas.drawRect(0f, hud, w, h, paint)
        val length = if (started) RESUME_MS else INTRO_MS
        val remaining = ceil((1 - progress.coerceIn(0f, 1f)) * length / 600f).toInt().coerceAtLeast(1)
        val cy = (hud + line) / 2 - (if (!started && options.introduce.isNotEmpty()) dp(70f) else 0f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(4f)
        paint.color = 0x33FFFFFF
        canvas.drawCircle(cx, cy, dp(54f), paint)
        paint.color = Palette.ACCENT
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(cx - dp(54f), cy - dp(54f), cx + dp(54f), cy + dp(54f), -90f, 360f * progress.coerceIn(0f, 1f), false, paint)
        paint.style = Paint.Style.FILL
        text(canvas, "$remaining", 56f, Palette.TEXT, cx, cy + dp(20f), face = heavy)
        if (started) return
        var y = cy + dp(100f)
        if (options.caption.isNotEmpty()) {
            text(canvas, options.caption, 16f, Palette.TEXT, cx, y, face = heavy)
            y += dp(48f)
        }
        if (options.hint) {
            text(canvas, "Tap when the tile edge reaches the green line", 15f, Palette.TEXT, cx, y)
            text(canvas, "Hold green tiles · slide with the white ring", 13f, Palette.MUTED, cx, y + dp(22f))
            y += dp(60f)
        }
        for (kind in options.introduce) {
            val (title, detail, colors) =
                when (kind) {
                    GameSession.GOLD -> Triple("Gold tile", "Bonus points and a life back", intArrayOf(0xFFF6C744.toInt(), 0xFFFFF1B8.toInt()))
                    GameSession.AVOID -> Triple("Bomb tile", "Don't touch it", intArrayOf(0xFF3B1022.toInt(), 0xFF6B1530.toInt()))
                    else -> Triple("Chord", "Linked tiles: tap them all together", intArrayOf(0xFF7C5CF2.toInt(), 0xFFC9BBFF.toInt()))
                }
            val left = cx - dp(140f)
            paint.color = 0xE61A1727.toInt()
            canvas.drawRoundRect(left, y - dp(30f), cx + dp(140f), y + dp(30f), dp(16f), dp(16f), paint)
            paint.shader = faceShader(colors[0] xor 7, colors)
            matrix.setScale(1f, dp(36f))
            matrix.postTranslate(0f, y - dp(18f))
            paint.shader.setLocalMatrix(matrix)
            paint.color = Color.WHITE
            canvas.drawRoundRect(left + dp(14f), y - dp(18f), left + dp(54f), y + dp(18f), dp(6f), dp(6f), paint)
            paint.shader = null
            val icon = if (kind == GameSession.GOLD) starGlyph else if (kind == GameSession.AVOID) bombGlyph else null
            icon?.let {
                it.alpha = 255
                it.setBounds((left + dp(22f)).toInt(), (y - dp(12f)).toInt(), (left + dp(46f)).toInt(), (y + dp(12f)).toInt())
                it.draw(canvas)
            }
            text(canvas, "NEW · $title", 14f, Palette.TEXT, left + dp(68f), y - dp(3f), Paint.Align.LEFT, heavy)
            text(canvas, detail, 12.5f, Palette.MUTED, left + dp(68f), y + dp(16f), Paint.Align.LEFT)
            y += dp(72f)
        }
    }

    private fun drawEnding(canvas: Canvas, h: Float, now: Long) {
        val fade = ((now - endingAt) / 400f).coerceIn(0f, 1f)
        paint.color = alpha(0xCC0D0C12.toInt(), fade)
        canvas.drawRect(0f, hud, w, h, paint)
        val failed = session.failed()
        val scale = 1.3f - .3f * fade
        text(
            canvas,
            when {
                failed -> "Out of lives"
                timedOut -> "Time's up!"
                else -> options.segmentDone
            },
            30f * scale,
            alpha(if (failed || timedOut) Palette.RED else Palette.GOLD, fade),
            cx,
            (hud + h) / 2,
            face = heavy,
        )
    }

    /** Use the same projected tile heads that are drawn, rather than a full-width lane guess. */
    private fun tileAt(x: Float, y: Float, time: Double): GameSession.Note? {
        if (y < top - dp(2f) || y > line + dp(60f) || !field.invert(touchField)) return null
        touchPoint[0] = x
        touchPoint[1] = y
        touchField.mapPoints(touchPoint)
        val lane = w / 4
        for (i in renderIndex until session.notes.size) {
            val note = session.notes[i]
            if (note.time - time > options.travel * 1.08) break
            if (note.judgement != 0) continue
            val p = ((note.time - time) / options.travel).toFloat()
            if (p < -.45f || fog(p) <= 0f) continue
            if (
                touchPoint[0] >= (note.lane + .07f) * lane &&
                    touchPoint[0] <= (note.lane + .93f) * lane &&
                    touchPoint[1] >= worldY(p + TILE) &&
                    touchPoint[1] <= worldY(p) + dp(11f)
            ) return note
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val x = event.getX(index)
        val y = event.getY(index)
        val down =
            event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        if (down && y < hud && x > width - dp(80f)) {
            if (endingAt == 0L) {
                pause()
                listener.pauseRequested()
            }
            return true
        }
        if (!rolling || ended || endingAt > 0) return true
        val laneWidth = width / 4f
        val lane = (x / laneWidth).toInt().coerceIn(0, 3)
        // Judge the moment the finger landed, not when the batched event reached the view.
        val time = clock.secondsAt(event.eventTime) + offset
        var result = 0
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerLanes.put(event.getPointerId(index), lane)
                val target = tileAt(x, y, time)
                result = session.press(
                    target?.lane ?: lane, time, event.getPointerId(index), x / laneWidth,
                    target = target,
                )
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val nextLane = (event.getX(i) / laneWidth).toInt().coerceIn(0, 3)
                    val now = SystemClock.uptimeMillis()
                    if (options.haptics && pointerLanes.get(id, nextLane) != nextLane &&
                        session.activeKind(id) == "slide" && now - lastSlideHaptic > 90) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        lastSlideHaptic = now
                    }
                    pointerLanes.put(id, nextLane)
                    session.move(id, event.getX(i) / laneWidth, time)
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                pointerLanes.delete(event.getPointerId(index))
                session.release(event.getPointerId(index), time)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) session.release(event.getPointerId(i), time)
                pointerLanes.clear()
            }
        }
        if (options.haptics && result != 0 && result != 3 && down)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (down) performClick()
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val INTRO_MS = 1800L
        private const val RESUME_MS = 1800L

        /** How much smaller the far end of the field is than the strike line. */
        private const val FAR = 2.1f

        /** Tile length as a fraction of the field. */
        private const val TILE = .15f

        /** Beat-line spacing in song seconds. */
        private const val BEAT = .5
        private val MULTIPLIERS = arrayOf("×1", "×2", "×3", "×4")

        private val TAP = Look(intArrayOf(0xFF4A2FC0.toInt(), 0xFF7C5CF2.toInt(), 0xFFC9BBFF.toInt()), 0xFF2B1B78.toInt())
        private val GOLD = Look(intArrayOf(0xFFB7791F.toInt(), 0xFFF6C744.toInt(), 0xFFFFF1B8.toInt()), 0xFF7A4E0E.toInt())
        private val BOMB = Look(intArrayOf(0xFF1C0A12.toInt(), 0xFF3B1022.toInt(), 0xFF6B1530.toInt()), 0xFF12060B.toInt())
        private val MISSED = Look(intArrayOf(0xFF3A1A2A.toInt(), 0xFF6B2A3F.toInt(), 0xFF9F3550.toInt()), 0xFF2A0F18.toInt())
        private val HOLD = Look(intArrayOf(0xFF0F5F59.toInt(), 0xFF14B8A6.toInt(), 0xFFB5F5EA.toInt()), 0xFF0A3D3A.toInt())
        private val SLIDE = Look(intArrayOf(0xFF0B4A7A.toInt(), 0xFF0EA5E9.toInt(), 0xFFC7ECFF.toInt()), 0xFF082F4F.toInt())

        fun travelForTempo(base: Double, bpm: Double): Double =
            if (bpm.isFinite() && bpm > 0) base * (120 / bpm).coerceIn(.8, 1.3) else base

        /** Song-seconds a tile spends falling, for the Slow / Normal / Fast note speeds. */
        val TRAVEL = doubleArrayOf(2.2, 1.7, 1.3)
    }
}
