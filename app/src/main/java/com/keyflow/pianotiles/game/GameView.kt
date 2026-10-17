package com.keyflow.pianotiles.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.keyflow.pianotiles.ui.Palette
import kotlin.math.sin

/**
 * The game screen: it runs the countdown, clock and session each frame and draws them on the
 * [Highway] with [NoteRenderer], [HitFeedback] and [GameHud]. Touch lanes are measured at the
 * strike line, where the field is full width, so the perspective never changes what a tap means.
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

    private val highway = Highway(resources.displayMetrics.density)
    private val feedback = HitFeedback(highway, session, options.haptics, ::performHapticFeedback)
    private val notes = NoteRenderer(highway, session, options, feedback)
    private val gameHud = GameHud(highway, session, clock, options, feedback)
    // The offset is device latency in real time, so it covers more of the song at higher speeds.
    private val offset = options.offsetMs / 1000.0 * options.speed
    private val handover = Handover(options.speed.toDouble())

    private var countdownFrom = SystemClock.uptimeMillis()
    private var countdownLength = INTRO_MS
    private var lastFrame = 0L
    private var running = true
    private var started = false
    private var rolling = false
    private var ended = false
    private var endingAt = 0L
    private val pointerLanes = SparseIntArray()
    private val touchField = Matrix()
    private val touchPoint = FloatArray(2)

    init {
        contentDescription = "Four piano lanes. Tap tiles as they reach the line."
        isFocusable = true
    }

    private fun dp(n: Float) = highway.dp(n)

    fun pause() {
        if (!running || ended) return
        running = false
        session.suspendGestures()
        pointerLanes.clear()
        if (rolling) clock.pause()
        rolling = false
        handover.settle()
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

    private var songTime = 0.0

    override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) = highway.resize(width, height)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        highway.release()
    }

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
            if (started) handover.time(clock.seconds + offset, clock.audible, options.startMs / 1000.0, System.nanoTime())
            else options.startMs / 1000.0 - (1 - countdown.coerceIn(0f, 1f)) * INTRO_MS / 1000.0 * options.speed
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
        feedback.observe(now, options.baseScore)
        val fever = session.multiplier >= 4 && endingAt == 0L

        with(highway) {
            canvas.drawColor(0xFF07060B.toInt())
            canvas.save()
            val shake = now - feedback.shakeAt
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
            notes.draw(canvas, time, now)
            drawStrike(canvas, now, h)
            feedback.drawChordEffect(canvas, now)

            feedback.particles.draw(
                canvas,
                paint,
                if (lastFrame == 0L) 0f else minOf(40L, now - lastFrame).toFloat(),
                density,
            )
            lastFrame = now
            feedback.draw(canvas, now)
            canvas.restore()

            gameHud.draw(canvas, time, now, fever, timeLeftMs)
            if (endingAt > 0) gameHud.drawEnding(canvas, h, now, endingAt, timedOut)
            else if (!started || (running && countdown < 1f))
                gameHud.drawCountdown(canvas, h, countdown, if (started) RESUME_MS else INTRO_MS, started)
            else if (!running) {
                paint.color = 0x99000000.toInt()
                canvas.drawRect(0f, hud, w, h, paint)
            }
        }
        if (!ended && (running || endingAt > 0)) postInvalidateOnAnimation()
    }

    private fun drawField(canvas: Canvas, now: Long, time: Double, fever: Boolean) {
        with(highway) {
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
                val age = now - feedback.laneGlowAt[i]
                if (age > 280) continue
                val color = feedback.laneGlowColor[i]
                val shader = faceShader(color, intArrayOf(0, alpha(color, .6f)))
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
                val pop = ((now - feedback.comboAt) / 140f).coerceIn(0f, 1f)
                val y = yAt(.45f)
                text(canvas, "${session.combo}", 58f + 10f * (1 - pop), 0x24FFFFFF, cx, y, face = heavy)
                text(canvas, "COMBO", 12f, 0x40FFFFFF, cx, y + dp(22f))
            }
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

    private var lastSlideHaptic = 0L

    private fun drawStrike(canvas: Canvas, now: Long, h: Float) {
        with(highway) {
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
                val glow = (1 - (now - feedback.laneGlowAt[i]) / 240f).coerceIn(0f, 1f)
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
                    paint.color = alpha(feedback.laneGlowColor[i], .55f * glow)
                    canvas.drawRoundRect(l, t, r, b, dp(16f), dp(16f), paint)
                }
                paint.color = if (pressed) 0x66FFFFFF else 0x14FFFFFF
                canvas.drawRoundRect(l + dp(1f), t + dp(1f), r - dp(1f), t + dp(3f), dp(2f), dp(2f), paint)
                paint.color = if (pressed) Palette.ACCENT else alpha(Palette.ACCENT, .25f + .55f * beat)
                canvas.drawCircle((l + r) / 2, (t + b) / 2, dp(4f + 1.5f * beat), paint)
            }
        }
    }

    /** Use the same projected tile heads that are drawn, rather than a full-width lane guess. */
    private fun tileAt(x: Float, y: Float, time: Double): GameSession.Note? {
        with(highway) {
            if (y < top - dp(2f) || y > line + dp(60f) || !field.invert(touchField)) return null
            touchPoint[0] = x
            touchPoint[1] = y
            touchField.mapPoints(touchPoint)
            val lane = w / 4
            for (i in notes.first until session.notes.size) {
                val note = session.notes[i]
                if (note.time - time > options.travel * 1.08) break
                if (note.judgement != 0) continue
                val p = ((note.time - time) / options.travel).toFloat()
                if (p < -.45f || fog(p) <= 0f) continue
                if (
                    touchPoint[0] >= (note.lane + .07f) * lane &&
                        touchPoint[0] <= (note.lane + .93f) * lane &&
                        touchPoint[1] >= worldY(p + Highway.TILE) &&
                        touchPoint[1] <= worldY(p) + dp(11f)
                ) return note
            }
            return null
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val x = event.getX(index)
        val y = event.getY(index)
        val down =
            event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        if (down && y < highway.hud && x > width - dp(80f)) {
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
        val time = clock.secondsAt(event.eventTime) + offset + handover.lead
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

        fun travelForTempo(base: Double, bpm: Double): Double =
            if (bpm.isFinite() && bpm > 0) base * (120 / bpm).coerceIn(.8, 1.3) else base

        /** Song-seconds a tile spends falling, for the Slow / Normal / Fast note speeds. */
        val TRAVEL = doubleArrayOf(2.2, 1.7, 1.3)
    }
}
