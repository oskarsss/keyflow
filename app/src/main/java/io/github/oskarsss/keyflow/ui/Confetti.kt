package io.github.oskarsss.keyflow.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** A short celebratory burst of falling paper, drawn over the results screen. */
class Confetti(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = intArrayOf(Palette.ACCENT, Palette.MINT, Palette.GOLD, Palette.SKY, 0xFFF0ABFC.toInt())
    private val count = 90
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val spin = FloatArray(count)
    private val size = FloatArray(count)
    private val color = IntArray(count)
    private var start = 0L
    private var last = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val d = resources.displayMetrics.density
        for (i in 0 until count) {
            x[i] = w / 2f + (Random.nextFloat() - .5f) * w * .3f
            y[i] = h * .28f
            val angle = (-90 + (Random.nextFloat() - .5f) * 140) * Math.PI.toFloat() / 180
            val speed = (5 + Random.nextFloat() * 9) * d
            vx[i] = cos(angle) * speed
            vy[i] = sin(angle) * speed
            spin[i] = Random.nextFloat() * 360
            size[i] = (4 + Random.nextFloat() * 5) * d
            color[i] = colors[i % colors.size]
        }
        start = SystemClock.uptimeMillis()
        last = start
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val age = now - start
        if (age > 3200) return
        val dt = minOf(40L, now - last) / 16f
        last = now
        val d = resources.displayMetrics.density
        for (i in 0 until count) {
            vy[i] += .32f * d * dt
            vx[i] *= .985f
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            spin[i] += 9 * dt
            paint.color = color[i]
            paint.alpha = (255 * (1 - ((age - 2200) / 1000f).coerceIn(0f, 1f))).toInt()
            canvas.save()
            canvas.rotate(spin[i], x[i], y[i])
            canvas.drawRect(x[i] - size[i] / 2, y[i] - size[i] / 4, x[i] + size[i] / 2, y[i] + size[i] / 4, paint)
            canvas.restore()
        }
        postInvalidateOnAnimation()
    }
}
