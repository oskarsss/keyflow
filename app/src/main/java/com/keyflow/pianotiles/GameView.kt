package com.keyflow.pianotiles

import android.content.Context
import android.graphics.*
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale

class GameView(
    context: Context,
    private val title: String,
    val session: GameSession,
    private val player: MediaPlayer,
    private val speed: Float,
    timingOffsetMs: Int,
    private val listener: Listener,
) : View(context) {
    interface Listener {
        fun finished(session: GameSession)

        fun pauseRequested()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val face = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val density = resources.displayMetrics.density
    private val offset = timingOffsetMs / 1000.0
    private val particles = HitParticles()
    private var startTime = SystemClock.uptimeMillis()
    private var lastFrame = 0L
    private var running = true
    private var started = false
    private var ended = false
    private var feedback = ""
    private var feedbackUntil = 0L
    private var flashLane = -1
    private var flashUntil = 0L
    private var renderIndex = 0
    private val railPath = Path()
    private val trailX = FloatArray(32)
    private val trailY = FloatArray(32)
    private val trailTime = LongArray(32)
    private var trailCursor = 0
    private var observedHits = 0
    private var observedMisses = 0
    private var backdrop: LinearGradient? = null
    private val tileShaders = arrayOfNulls<LinearGradient>(3)
    private val shaderMatrix = Matrix()

    init {
        contentDescription =
            "Four piano lanes. Tap tiles as they cross the glowing line. Eight misses ends the level."
        isFocusable = true
    }

    private fun dp(n: Float) = n * density

    fun pause() {
        if (running && !ended) {
            running = false
            session.suspendGestures()
            if (started) player.pause()
        }
    }

    fun resume() {
        if (ended) return
        running = true
        if (started) player.start() else startTime = SystemClock.uptimeMillis()
        lastFrame = 0
        invalidate()
    }

    fun stop() {
        ended = true
        running = false
    }

    private fun finish() {
        if (ended) return
        ended = true
        running = false
        if (player.isPlaying) player.pause()
        listener.finished(session)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        backdrop =
            LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                intArrayOf(0xFF231B35.toInt(), 0xFF111823.toInt(), 0xFF151122.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        val height = minOf(dp(122f), (h - dp(263f)) * .25f)
        val colors = intArrayOf(0xFFAA8BEF.toInt(), 0xFF72DCCF.toInt(), 0xFF7C365A.toInt())
        for (i in colors.indices) tileShaders[i] =
            LinearGradient(
                0f,
                0f,
                0f,
                height,
                intArrayOf(0xFF242035.toInt(), colors[i]),
                null,
                Shader.TileMode.CLAMP,
            )
    }

    private fun text(
        canvas: Canvas,
        value: String,
        size: Float,
        color: Int,
        x: Float,
        y: Float,
        align: Paint.Align,
    ) {
        paint.shader = null
        paint.color = color
        paint.textSize = dp(size)
        paint.typeface = face
        paint.textAlign = align
        canvas.drawText(value, x, y, paint)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = dp(145f)
        val line = h - dp(118f)
        val lane = w / 4
        val now = SystemClock.uptimeMillis()
        val countdown = (now - startTime) / 1000.0
        if (running && !started && countdown >= 3) {
            started = true
            player.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
            player.start()
        }
        val time =
            if (started) player.currentPosition / 1000.0 + offset else -maxOf(0.0, 3 - countdown)
        if (running && started) {
            session.advance(time)
            if (
                session.failed() ||
                    (!player.isPlaying && player.currentPosition >= player.duration - 100)
            ) {
                finish()
                return
            }
        }
        paint.color = Color.WHITE
        paint.shader = backdrop
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        for (i in 0..3) {
            paint.color = if (i % 2 == 0) 0x161B3042 else 0x162F2443
            canvas.drawRect(i * lane, top, (i + 1) * lane, h, paint)
            paint.color = 0x224D4963
            canvas.drawRect(i * lane, top, i * lane + dp(1f), h, paint)
        }
        val tileHeight = minOf(dp(122f), (line - top) * .25f)
        while (
            renderIndex < session.notes.size &&
                session.notes[renderIndex].time + session.notes[renderIndex].duration < time - .6
        ) renderIndex++
        for (i in renderIndex until session.notes.size) {
            val note = session.notes[i]
            if (note.judgement == 1 || note.judgement == 2) continue
            val delta = note.time - time
            if (delta > TRAVEL + .6) break
            if (delta + note.duration < -.6) continue
            val bottom = TileScheduler.bottom(note.time, time, top, line, TRAVEL)
            if (note.kind != "tap") {
                val endBottom =
                    TileScheduler.bottom(note.time + note.duration, time, top, line, TRAVEL)
                val active = note.judgement == 4
                val startX = (if (active) note.laneAt(time) else note.lane + .5f) * lane
                val startY = if (active) line else bottom
                val endX = (note.endLane + .5f) * lane
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                railPath.reset()
                railPath.moveTo(startX, startY)
                railPath.lineTo(endX, endBottom)
                paint.color = if (note.judgement == 3) 0x66683C52 else 0x5549BBAE
                paint.strokeWidth = dp(38f)
                canvas.drawPath(railPath, paint)
                paint.color = if (active) 0xFF85E8D1.toInt() else 0xFFB7A0FF.toInt()
                paint.strokeWidth = dp(7f)
                canvas.drawPath(railPath, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(startX, startY, dp(15f), paint)
                canvas.drawCircle(endX, endBottom, dp(9f), paint)
                if (active) {
                    paint.color = 0x4485E8D1
                    canvas.drawCircle(startX, line, dp(29f), paint)
                    paint.color = Color.WHITE
                    canvas.drawCircle(startX, line, dp(7f), paint)
                } else
                    text(
                        canvas,
                        if (note.kind == "hold") "HOLD" else "SLIDE ↔",
                        11f,
                        Color.WHITE,
                        startX,
                        bottom - dp(24f),
                        Paint.Align.CENTER,
                    )
                continue
            }
            val x = note.lane * lane + dp(5f)
            val shader = tileShaders[if (note.judgement == 3) 2 else note.lane % 2]
            shaderMatrix.setTranslate(0f, bottom - tileHeight)
            shader?.setLocalMatrix(shaderMatrix)
            paint.color = Color.WHITE
            paint.shader = shader
            canvas.drawRoundRect(
                x,
                bottom - tileHeight,
                x + lane - dp(10f),
                bottom,
                dp(8f),
                dp(8f),
                paint,
            )
            paint.shader = null
            paint.color = 0x55FFFFFF
            canvas.drawRoundRect(
                x + dp(12f),
                bottom - dp(10f),
                x + lane - dp(22f),
                bottom - dp(7f),
                dp(2f),
                dp(2f),
                paint,
            )
        }
        if (now < flashUntil) {
            paint.color = 0x3385E8D1
            canvas.drawRect(flashLane * lane, top, (flashLane + 1) * lane, h, paint)
        }
        paint.color = 0xFF85E8D1.toInt()
        canvas.drawRect(0f, line, w, line + dp(2f), paint)
        paint.color = 0x1185E8D1
        canvas.drawRect(0f, line - dp(8f), w, line + dp(8f), paint)
        for (i in trailTime.indices) {
            val age = now - trailTime[i]
            if (age in 0..350 && trailTime[i] > 0) {
                paint.color = (0x85E8D1 or (((1f - age / 350f) * 95).toInt() shl 24))
                canvas.drawCircle(trailX[i], trailY[i], dp(6f + (1f - age / 350f) * 10), paint)
            }
        }
        if (session.hits > observedHits) {
            observedHits = session.hits
            feedback = if (session.lastQuality == 1) "PERFECT" else "GOOD"
            feedbackUntil = now + 420
            particles.emit(
                (session.lastCompletedLane + .5f) * lane,
                line,
                0xFF85E8D1.toInt(),
                density,
            )
        }
        if (session.misses > observedMisses) {
            observedMisses = session.misses
            feedback = "MISS"
            feedbackUntil = now + 420
        }
        particles.draw(
            canvas,
            paint,
            if (lastFrame == 0L) 0f else minOf(40L, now - lastFrame).toFloat(),
            density,
        )
        lastFrame = now
        paint.color = 0xFF111019.toInt()
        canvas.drawRect(0f, 0f, w, top, paint)
        val white = 0xFFF6F2FF.toInt()
        text(canvas, "‹", 34f, white, dp(24f), dp(43f), Paint.Align.LEFT)
        text(
            canvas,
            if (title.length > 25) title.take(23) + "…" else title,
            15f,
            white,
            w / 2,
            dp(36f),
            Paint.Align.CENTER,
        )
        text(canvas, "Ⅱ", 23f, white, w - dp(26f), dp(40f), Paint.Align.RIGHT)
        text(
            canvas,
            String.format(Locale.US, "%05d", session.score),
            36f,
            white,
            w / 2,
            dp(90f),
            Paint.Align.CENTER,
        )
        text(
            canvas,
            if (session.combo > 1) "${session.combo} COMBO" else "FIND YOUR RHYTHM",
            10f,
            0xFFB7A0FF.toInt(),
            w / 2,
            dp(115f),
            Paint.Align.CENTER,
        )
        for (i in 0..7) {
            paint.color = if (i < 8 - session.misses) 0xFF85E8D1.toInt() else 0xFF3C354C.toInt()
            canvas.drawCircle(w / 2 + (i - 3.5f) * dp(13f), dp(132f), dp(3f), paint)
        }
        paint.color = 0xFF262237.toInt()
        canvas.drawRect(0f, top - dp(3f), w, top, paint)
        paint.color = 0xFFB7A0FF.toInt()
        canvas.drawRect(
            0f,
            top - dp(3f),
            w * (maxOf(0.0, time) / (player.duration / 1000.0)).coerceIn(0.0, 1.0).toFloat(),
            top,
            paint,
        )
        text(
            canvas,
            "TAP ON THE LINE",
            10f,
            0xFF86829B.toInt(),
            w / 2,
            line + dp(36f),
            Paint.Align.CENTER,
        )
        for (i in 0..3) text(
            canvas,
            "${i + 1}",
            12f,
            0xFF555368.toInt(),
            (i + .5f) * lane,
            h - dp(25f),
            Paint.Align.CENTER,
        )
        if (now < feedbackUntil)
            text(
                canvas,
                feedback,
                21f,
                if (feedback == "MISS") 0xFFFF94B3.toInt() else 0xFFADFFF0.toInt(),
                w / 2,
                line - dp(44f),
                Paint.Align.CENTER,
            )
        if (!started) {
            paint.color = 0x550F0C19
            canvas.drawRect(0f, top, w, h, paint)
            text(
                canvas,
                "${maxOf(1, 3 - countdown.toInt())}",
                72f,
                white,
                w / 2,
                h * .49f,
                Paint.Align.CENTER,
            )
            text(
                canvas,
                "Tap · hold · slide at the glowing line",
                14f,
                0xFFCDC6DF.toInt(),
                w / 2,
                h * .49f + dp(40f),
                Paint.Align.CENTER,
            )
        }
        if (running && !ended) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val x = event.getX(index)
        val y = event.getY(index)
        val down =
            event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        if (down && y < dp(65f)) {
            pause()
            listener.pauseRequested()
            return true
        }
        if (!started || !running || ended) return true
        val lane = (x / (width / 4f)).toInt().coerceIn(0, 3)
        val time = player.currentPosition / 1000.0 + offset
        var result = 0
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN ->
                result = session.press(lane, time, event.getPointerId(index), x / (width / 4f))
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val judgement =
                        session.move(event.getPointerId(i), event.getX(i) / (width / 4f), time)
                    if (judgement > 0) result = judgement
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> session.release(event.getPointerId(index), time)
            MotionEvent.ACTION_CANCEL ->
                for (i in 0 until event.pointerCount) session.release(event.getPointerId(i), time)
        }
        if (
            event.actionMasked != MotionEvent.ACTION_UP &&
                event.actionMasked != MotionEvent.ACTION_POINTER_UP
        ) {
            val slot = trailCursor++ % trailTime.size
            trailX[slot] = x
            trailY[slot] = y
            trailTime[slot] = SystemClock.uptimeMillis()
        }
        if (result == 0) {
            invalidate()
            return true
        }
        feedback =
            when (result) {
                1 -> "PERFECT"
                2 -> "GOOD"
                4 -> session.activeKind(event.getPointerId(index))?.uppercase() ?: "FLOW"
                else -> "MISS"
            }
        feedbackUntil = SystemClock.uptimeMillis() + 420
        flashLane = lane
        flashUntil = SystemClock.uptimeMillis() + 100
        if (result != 3 && down) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        performClick()
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val TRAVEL = 1.7
    }
}
