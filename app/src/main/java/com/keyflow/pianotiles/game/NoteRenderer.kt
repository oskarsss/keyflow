package com.keyflow.pianotiles.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.keyflow.pianotiles.game.Highway.Companion.TILE
import com.keyflow.pianotiles.game.Highway.Look
import com.keyflow.pianotiles.ui.Glyph
import com.keyflow.pianotiles.ui.Palette
import kotlin.math.abs
import kotlin.math.sin

/** Draws the falling tiles, holds and slides on the [Highway], nearest on top. */
internal class NoteRenderer(
    private val highway: Highway,
    private val session: GameSession,
    private val options: GameView.Options,
    private val feedback: HitFeedback,
) {
    /** The first note that can still be seen; earlier ones have passed. */
    var first = 0
        private set
    private var songTime = 0.0
    private var lastSpark = 0L
    private val lineBuffer = FloatArray(16 * 4)
    private val bodyShaders = HashMap<Int, LinearGradient>()
    val starGlyph = Glyph(Glyph.Kind.STAR, 0xFF7A4E0E.toInt())
    val bombGlyph = Glyph(Glyph.Kind.CLOSE, Palette.RED)

    private fun progress(time: Double) = ((time - songTime) / options.travel).toFloat()

    fun draw(canvas: Canvas, time: Double, now: Long) {
        songTime = time
        with(highway) {
            val travel = options.travel
            while (
                first < session.notes.size &&
                    session.notes[first].time + session.notes[first].duration < time - .6
            ) first++
            var last = first
            while (last < session.notes.size && session.notes[last].time - time <= travel * 1.08) last++
            canvas.save()
            canvas.clipRect(0f, top - dp(2f), w, line + dp(60f))
            canvas.save()
            canvas.concat(field)
            // Far notes first so near tiles overlap them.
            for (i in last - 1 downTo first) {
                val note = session.notes[i]
                if (!visible(note, time)) continue
                if (note.kind == "hold" || note.kind == "slide") drawRail(canvas, note, time, now)
                else drawTile(canvas, note, time, now, i)
            }
            canvas.restore()
            // Icons stay upright, so they are drawn in screen space on top.
            for (i in first until last) {
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
    }

    private fun visible(note: GameSession.Note, time: Double) =
        (note.judgement == 0 || note.judgement == 4) &&
            note.time + note.duration - time >= -.6

    private fun drawTile(canvas: Canvas, note: GameSession.Note, time: Double, now: Long, index: Int) {
        with(highway) {
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
    }

    /**
     * Holds and slides: a tile to press, a glowing ribbon to follow and a cap where it ends. While
     * held, the grip stays on the strike line and the ribbon is consumed into it.
     */
    private fun drawRail(canvas: Canvas, note: GameSession.Note, time: Double, now: Long) {
        with(highway) {
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
                val body = bodyShaders.getOrPut(color) {
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
                feedback.laneGlowAt[l] = now - 120
                feedback.laneGlowColor[l] = color
                if (now - lastSpark > 55) {
                    lastSpark = now
                    feedback.particles.emit(x, line, color, density, 3)
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
    }

    companion object {
        /** Beat-line spacing in song seconds. */
        private const val BEAT = .5

        private val TAP = Look(intArrayOf(0xFF4A2FC0.toInt(), 0xFF7C5CF2.toInt(), 0xFFC9BBFF.toInt()), 0xFF2B1B78.toInt())
        private val GOLD = Look(intArrayOf(0xFFB7791F.toInt(), 0xFFF6C744.toInt(), 0xFFFFF1B8.toInt()), 0xFF7A4E0E.toInt())
        private val BOMB = Look(intArrayOf(0xFF1C0A12.toInt(), 0xFF3B1022.toInt(), 0xFF6B1530.toInt()), 0xFF12060B.toInt())
        private val MISSED = Look(intArrayOf(0xFF3A1A2A.toInt(), 0xFF6B2A3F.toInt(), 0xFF9F3550.toInt()), 0xFF2A0F18.toInt())
        private val HOLD = Look(intArrayOf(0xFF0F5F59.toInt(), 0xFF14B8A6.toInt(), 0xFFB5F5EA.toInt()), 0xFF0A3D3A.toInt())
        private val SLIDE = Look(intArrayOf(0xFF0B4A7A.toInt(), 0xFF0EA5E9.toInt(), 0xFFC7ECFF.toInt()), 0xFF082F4F.toInt())
    }
}
