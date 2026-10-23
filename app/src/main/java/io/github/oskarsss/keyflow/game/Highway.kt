package io.github.oskarsss.keyflow.game

import android.graphics.*

/**
 * The playfield's perspective highway: its geometry, scenery and the drawing primitives the
 * notes, feedback and HUD share. Tiles appear at a glowing horizon and grow as they approach
 * the strike line.
 */
internal class Highway(val density: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val heavy: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    val path = Path()
    val rect = RectF()
    val matrix = Matrix()

    // Geometry, refreshed on size change.
    var top = 0f
        private set
    var line = 0f
        private set
    var horizon = 0f
        private set
    var cx = 0f
        private set
    var w = 0f
        private set

    var sky: Shader? = null
        private set
    var feverFloor: Shader? = null
        private set
    var strikeGlow: Shader? = null
        private set
    var danger: Shader? = null
        private set
    private var backdrop: Shader? = null
    private var floor: Shader? = null
    private var gloss: LinearGradient? = null
    private val unitShaders = HashMap<Int, LinearGradient>()

    /** Projects the flat playfield (lanes at full width, y linear in time) onto the highway. */
    val field = Matrix()
    var stage: Bitmap? = null
        private set

    fun dp(n: Float) = n * density

    val hud
        get() = dp(64f)

    val keys
        get() = dp(92f)

    /** Depth for field progress p: 0 at the strike line, 1 at the horizon edge of the field. */
    fun depth(p: Float) = maxOf(.35f, 1 + p * (FAR - 1))

    fun yAt(p: Float) = horizon + (line - horizon) / depth(p)

    /** Screen x of a lane position (0..4) at progress p. */
    fun xAt(lane: Float, p: Float) = cx + (lane * w / 4 - cx) / depth(p)

    /** World y for field progress p (0 at the strike line, 1 at the far edge). */
    fun worldY(p: Float) = line - p * (line - top)

    fun fog(p: Float) = 1 - ((p - .78f) / .3f).coerceIn(0f, 1f)

    fun resize(width: Int, height: Int) {
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

    fun release() {
        stage?.recycle()
        stage = null
    }

    /** A vertical gradient over 0..1, positioned per tile with a matrix instead of reallocating. */
    fun faceShader(key: Int, colors: IntArray) =
        unitShaders.getOrPut(key) {
            LinearGradient(0f, 0f, 0f, 1f, colors, null, Shader.TileMode.CLAMP)
        }

    fun text(
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

    class Look(val colors: IntArray, val side: Int)

    /** A thick tile: a side face below gives it height, the top face is lit toward the player. */
    fun block(canvas: Canvas, l: Float, r: Float, yb: Float, yt: Float, look: Look, a: Float, thick: Float = dp(11f)) {
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

    companion object {
        /** How much smaller the far end of the field is than the strike line. */
        const val FAR = 2.1f

        /** Tile length as a fraction of the field. */
        const val TILE = .15f
    }
}

/** [color] with its alpha scaled by [fraction]. */
internal fun alpha(color: Int, fraction: Float) =
    (color and 0xFFFFFF) or ((fraction.coerceIn(0f, 1f) * (color ushr 24)).toInt() shl 24)
