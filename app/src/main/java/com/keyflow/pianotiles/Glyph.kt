package com.keyflow.pianotiles

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Small vector icons drawn on a 24-unit grid, so the app needs no icon assets. */
class Glyph(private val kind: Kind, color: Int) : Drawable() {
    enum class Kind {
        PLAY, PAUSE, LOCK, CHECK, GEAR, BACK, CHEVRON_DOWN, CHEVRON_RIGHT, PLUS, MINUS,
        DOWNLOAD, STAR, RETRY, CLOSE, PASTE, MUSIC, ALERT, TRASH, REFRESH, HEART, FLAME, BOLT,
    }

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val path = Path()
    private var size = 0

    fun sized(px: Int) = apply {
        size = px
        setBounds(0, 0, px, px)
    }

    override fun getIntrinsicWidth() = size

    override fun getIntrinsicHeight() = size

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.exactCenterX() - 12 * s, b.exactCenterY() - 12 * s)
        canvas.scale(s, s)
        paint.strokeWidth = 2f
        draw(canvas, kind)
        canvas.restore()
    }

    private fun stroke() = paint.apply { style = Paint.Style.STROKE }

    private fun fill() = paint.apply { style = Paint.Style.FILL }

    private fun draw(c: Canvas, kind: Kind) {
        path.reset()
        when (kind) {
            Kind.PLAY -> {
                path.moveTo(8f, 5.5f)
                path.lineTo(19f, 12f)
                path.lineTo(8f, 18.5f)
                path.close()
                c.drawPath(path, fill())
                c.drawPath(path, stroke().apply { strokeWidth = 1.5f })
            }
            Kind.PAUSE -> {
                fill()
                c.drawRoundRect(6.5f, 5f, 10f, 19f, 1.2f, 1.2f, paint)
                c.drawRoundRect(14f, 5f, 17.5f, 19f, 1.2f, 1.2f, paint)
            }
            Kind.LOCK -> {
                c.drawRoundRect(5f, 10.5f, 19f, 20f, 2.5f, 2.5f, fill())
                path.moveTo(8f, 10.5f)
                path.lineTo(8f, 8f)
                path.arcTo(8f, 4f, 16f, 12f, 180f, 180f, false)
                path.lineTo(16f, 10.5f)
                c.drawPath(path, stroke())
            }
            Kind.CHECK -> {
                path.moveTo(5f, 12.5f)
                path.lineTo(10f, 17.5f)
                path.lineTo(19f, 7f)
                c.drawPath(path, stroke().apply { strokeWidth = 2.4f })
            }
            Kind.GEAR -> {
                fill()
                for (i in 0 until 8) {
                    c.save()
                    c.rotate(i * 45f, 12f, 12f)
                    c.drawRoundRect(10.4f, 2.4f, 13.6f, 6f, .8f, .8f, paint)
                    c.restore()
                }
                c.drawCircle(12f, 12f, 6.6f, stroke().apply { strokeWidth = 3f })
                c.drawCircle(12f, 12f, 2.4f, stroke().apply { strokeWidth = 1.8f })
            }
            Kind.BACK -> {
                path.moveTo(15f, 5f)
                path.lineTo(8f, 12f)
                path.lineTo(15f, 19f)
                c.drawPath(path, stroke().apply { strokeWidth = 2.2f })
            }
            Kind.CHEVRON_DOWN -> {
                path.moveTo(7f, 10f)
                path.lineTo(12f, 15f)
                path.lineTo(17f, 10f)
                c.drawPath(path, stroke().apply { strokeWidth = 2.2f })
            }
            Kind.CHEVRON_RIGHT -> {
                path.moveTo(10f, 7f)
                path.lineTo(15f, 12f)
                path.lineTo(10f, 17f)
                c.drawPath(path, stroke().apply { strokeWidth = 2.2f })
            }
            Kind.PLUS -> {
                stroke().strokeWidth = 2.2f
                c.drawLine(12f, 5.5f, 12f, 18.5f, paint)
                c.drawLine(5.5f, 12f, 18.5f, 12f, paint)
            }
            Kind.MINUS -> c.drawLine(5.5f, 12f, 18.5f, 12f, stroke().apply { strokeWidth = 2.2f })
            Kind.DOWNLOAD -> {
                stroke().strokeWidth = 2f
                c.drawLine(12f, 4.5f, 12f, 15f, paint)
                path.moveTo(7.5f, 10.5f)
                path.lineTo(12f, 15f)
                path.lineTo(16.5f, 10.5f)
                c.drawPath(path, paint)
                c.drawLine(5.5f, 19.5f, 18.5f, 19.5f, paint)
            }
            Kind.STAR -> {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 9.5f else 4.2f
                    val a = Math.toRadians(-90.0 + i * 36).toFloat()
                    val x = 12 + r * cos(a)
                    val y = 12.8f + r * sin(a)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, fill())
                c.drawPath(path, stroke().apply { strokeWidth = 1.4f })
            }
            Kind.RETRY, Kind.REFRESH -> {
                stroke().strokeWidth = 2.1f
                c.drawArc(5f, 5f, 19f, 19f, -60f, 300f, false, paint)
                path.moveTo(18.2f, 3.6f)
                path.lineTo(18.6f, 8.6f)
                path.lineTo(13.6f, 8.4f)
                c.drawPath(path, paint)
            }
            Kind.CLOSE -> {
                stroke().strokeWidth = 2.2f
                c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, paint)
                c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, paint)
            }
            Kind.PASTE -> {
                stroke().strokeWidth = 1.9f
                c.drawRoundRect(5.5f, 5f, 18.5f, 20.5f, 2.2f, 2.2f, paint)
                c.drawRoundRect(9f, 3f, 15f, 7f, 1.2f, 1.2f, fill())
                stroke()
                c.drawLine(9f, 12f, 15f, 12f, paint)
                c.drawLine(9f, 16f, 13.5f, 16f, paint)
            }
            Kind.MUSIC -> {
                fill()
                c.drawCircle(7.5f, 17.5f, 3f, paint)
                c.drawCircle(17f, 15.5f, 3f, paint)
                stroke().strokeWidth = 2f
                path.moveTo(10.5f, 17.5f)
                path.lineTo(10.5f, 6f)
                path.lineTo(20f, 4f)
                path.lineTo(20f, 15.5f)
                c.drawPath(path, paint)
            }
            Kind.ALERT -> {
                c.drawCircle(12f, 12f, 9f, stroke().apply { strokeWidth = 2f })
                c.drawLine(12f, 7.5f, 12f, 13f, paint)
                c.drawCircle(12f, 16.5f, 1.2f, fill())
            }
            Kind.HEART -> {
                path.moveTo(12f, 20.5f)
                path.cubicTo(5f, 15.5f, 2.5f, 12f, 2.5f, 8.5f)
                path.cubicTo(2.5f, 5.5f, 4.8f, 3.5f, 7.4f, 3.5f)
                path.cubicTo(9.4f, 3.5f, 11f, 4.6f, 12f, 6.4f)
                path.cubicTo(13f, 4.6f, 14.6f, 3.5f, 16.6f, 3.5f)
                path.cubicTo(19.2f, 3.5f, 21.5f, 5.5f, 21.5f, 8.5f)
                path.cubicTo(21.5f, 12f, 19f, 15.5f, 12f, 20.5f)
                path.close()
                c.drawPath(path, fill())
            }
            Kind.FLAME -> {
                path.moveTo(12f, 2.5f)
                path.cubicTo(13f, 6.5f, 19f, 9f, 19f, 14.5f)
                path.cubicTo(19f, 18.6f, 15.9f, 21.5f, 12f, 21.5f)
                path.cubicTo(8.1f, 21.5f, 5f, 18.6f, 5f, 14.5f)
                path.cubicTo(5f, 11.5f, 6.6f, 9.6f, 8.2f, 8.2f)
                path.cubicTo(8.2f, 10.5f, 9.2f, 12f, 10.6f, 12.6f)
                path.cubicTo(10f, 9f, 11f, 5f, 12f, 2.5f)
                path.close()
                c.drawPath(path, fill())
            }
            Kind.BOLT -> {
                path.moveTo(13.5f, 2.5f)
                path.lineTo(5f, 13.5f)
                path.lineTo(11f, 13.5f)
                path.lineTo(10f, 21.5f)
                path.lineTo(19f, 10f)
                path.lineTo(13f, 10f)
                path.close()
                c.drawPath(path, fill())
            }
            Kind.TRASH -> {
                stroke().strokeWidth = 1.9f
                c.drawLine(4.5f, 6.5f, 19.5f, 6.5f, paint)
                path.moveTo(6.5f, 6.5f)
                path.lineTo(7.5f, 20f)
                path.lineTo(16.5f, 20f)
                path.lineTo(17.5f, 6.5f)
                c.drawPath(path, paint)
                c.drawLine(9.5f, 6.5f, 10f, 3.8f, paint)
                c.drawLine(10f, 3.8f, 14f, 3.8f, paint)
                c.drawLine(14f, 3.8f, 14.5f, 6.5f, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
