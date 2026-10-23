package io.github.oskarsss.keyflow.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.oskarsss.keyflow.ui.Glyph
import io.github.oskarsss.keyflow.ui.Palette
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sin

/** The bar above the field and the screens laid over it: countdown, pause and ending. */
internal class GameHud(
    private val highway: Highway,
    private val session: GameSession,
    private val clock: GameClock,
    private val options: GameView.Options,
    private val feedback: HitFeedback,
) {
    private val pauseGlyph = Glyph(Glyph.Kind.PAUSE, Palette.TEXT)
    private val heart = Glyph(Glyph.Kind.HEART, Palette.RED)
    private val heartEmpty = Glyph(Glyph.Kind.HEART, 0xFF2C2939.toInt())
    private val starGlyph = Glyph(Glyph.Kind.STAR, 0xFF7A4E0E.toInt())
    private val bombGlyph = Glyph(Glyph.Kind.CLOSE, Palette.RED)
    private val scoreFormat = java.text.NumberFormat.getIntegerInstance(Locale.US)
    private var hudScore = -1
    private var hudScoreText = "0"
    private var timerSeconds = -1
    private var timerText = ""

    fun draw(canvas: Canvas, time: Double, now: Long, fever: Boolean, timeLeftMs: Long) {
        with(highway) {
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
            val value = feedback.displayedScore.toInt()
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
                val color = HitFeedback.tier(m)
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
    }

    private fun drawPause(canvas: Canvas) {
        with(highway) {
            paint.color = 0xFF221F2D.toInt()
            canvas.drawCircle(w - dp(34f), dp(34f), dp(19f), paint)
            pauseGlyph.setBounds((w - dp(44f)).toInt(), dp(24f).toInt(), (w - dp(24f)).toInt(), dp(44f).toInt())
            pauseGlyph.draw(canvas)
        }
    }

    /** The count in before play, and before it carries on after a pause once [started]. */
    fun drawCountdown(canvas: Canvas, h: Float, progress: Float, length: Long, started: Boolean) {
        with(highway) {
            paint.color = 0xA6000000.toInt()
            canvas.drawRect(0f, hud, w, h, paint)
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
                text(canvas, fit(options.caption, 18f, heavy), 18f, Palette.TEXT, cx, y, face = heavy)
                y += dp(26f)
            }
            if (options.detail.isNotEmpty()) {
                text(canvas, fit(options.detail, 14f, medium), 14f, Palette.MUTED, cx, y)
                y += dp(22f)
            }
            if (options.caption.isNotEmpty() || options.detail.isNotEmpty()) y += dp(26f)
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
    }

    private val fitted = HashMap<String, String>()

    /** [value] shortened with an ellipsis to fit the screen's width at [size]. */
    private fun fit(value: String, size: Float, face: android.graphics.Typeface): String {
        fitted["$size $value"]?.let { return it }
        with(highway) {
            paint.textSize = dp(size)
            paint.typeface = face
            val room = w - dp(48f)
            var shown = value
            if (paint.measureText(shown) > room) {
                while (shown.length > 1 && paint.measureText("$shown…") > room) shown = shown.dropLast(1)
                shown = shown.trimEnd() + "…"
            }
            fitted["$size $value"] = shown
            return shown
        }
    }

    fun drawEnding(canvas: Canvas, h: Float, now: Long, endingAt: Long, timedOut: Boolean) {
        with(highway) {
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
    }

    companion object {
        private val MULTIPLIERS = arrayOf("×1", "×2", "×3", "×4")
    }
}
