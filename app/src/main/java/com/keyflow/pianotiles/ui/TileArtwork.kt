package com.keyflow.pianotiles.ui

import android.content.Context
import android.graphics.*
import android.view.View

/** Original vector artwork, rendered without external bitmap assets. */
class TileArtwork(context: Context, private val variant: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backdrop: LinearGradient? = null
    private val tileShaders = arrayOfNulls<LinearGradient>(7)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val colors =
            when (variant % 3) {
                0 -> intArrayOf(0xFF302753.toInt(), 0xFF171A2A.toInt())
                1 -> intArrayOf(0xFF503361.toInt(), 0xFF252036.toInt())
                else -> intArrayOf(0xFF1F5556.toInt(), 0xFF14272E.toInt())
            }
        backdrop =
            LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), colors, null, Shader.TileMode.CLAMP)
        for (i in 0..6) {
            val y = (i * .29f - .45f) * h
            tileShaders[i] =
                LinearGradient(
                    0f,
                    y,
                    0f,
                    y + h * .29f,
                    intArrayOf(
                        if (i % 2 == 0) 0xFFB7A0FF.toInt() else 0xFF7ADCCF.toInt(),
                        0xFF6956AA.toInt(),
                    ),
                    null,
                    Shader.TileMode.CLAMP,
                )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = Color.WHITE
        paint.shader = backdrop
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.color = 0x227CBDE0
        for (i in 1..3) canvas.drawRect(i * w / 4 - 1, 0f, i * w / 4 + 1, h, paint)
        for (i in 0..6) {
            val x = (i * 3 + variant) % 4 * w / 4 + w * .022f
            val y = (i * .29f - .45f) * h
            paint.color = Color.WHITE
            paint.shader = tileShaders[i]
            canvas.drawRoundRect(x, y, x + w * .205f, y + h * .245f, w * .025f, w * .025f, paint)
        }
        paint.shader = null
        paint.color = 0x4496EFE0
        canvas.drawRect(0f, h * .79f, w, h * .79f + 2, paint)
        paint.color = 0xFFADFFF0.toInt()
        canvas.drawCircle(w * .63f, h * .79f, w * .038f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = 0x6685E8D1
        canvas.drawCircle(w * .63f, h * .79f, w * .07f, paint)
        paint.style = Paint.Style.FILL
    }
}
