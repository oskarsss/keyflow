package io.github.oskarsss.keyflow.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Shared palette and programmatic view builders for every screen. */
object Palette {
    const val BG = 0xFF0D0C12.toInt()
    const val SURFACE = 0xFF17161F.toInt()
    const val SURFACE_HI = 0xFF221F2D.toInt()
    const val STROKE = 0xFF2C2939.toInt()
    const val TEXT = 0xFFF5F3FA.toInt()
    const val MUTED = 0xFF948FA8.toInt()
    const val FAINT = 0xFF5C576D.toInt()
    const val ACCENT = 0xFFA78BFA.toInt()
    const val ON_ACCENT = 0xFF120D24.toInt()
    const val MINT = 0xFF5EEAD4.toInt()
    const val SKY = 0xFF7DD3FC.toInt()
    const val GOLD = 0xFFFCD34D.toInt()
    const val RED = 0xFFFB7185.toInt()
}

class Ui(val context: Context) {
    private val density = context.resources.displayMetrics.density

    fun dp(value: Int) = (value * density + .5f).toInt()


    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val heavy: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    fun rounded(color: Int, radius: Int, stroke: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    /** Adds a ripple so every tappable surface answers the finger. */
    fun pressable(view: View, content: Drawable?, radius: Int, light: Boolean = false) {
        view.background =
            RippleDrawable(
                ColorStateList.valueOf(if (light) 0x33000000 else 0x24FFFFFF),
                content,
                if (radius < 0) null else rounded(0xFFFFFFFF.toInt(), radius),
            )
        view.isClickable = true
        view.isFocusable = true
    }

    fun text(value: CharSequence, size: Float, color: Int, face: Typeface = regular) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = face
            includeFontPadding = false
        }

    fun title(value: String, size: Float = 26f) =
        text(value, size, Palette.TEXT, medium).apply { letterSpacing = -.02f }

    fun caption(value: String, color: Int = Palette.MUTED) =
        text(value.uppercase(), 11f, color, medium).apply { letterSpacing = .12f }

    fun oneLine(view: TextView) =
        view.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row() =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    fun gap(parent: LinearLayout, size: Int) {
        val vertical = parent.orientation == LinearLayout.VERTICAL
        parent.addView(
            View(context),
            LinearLayout.LayoutParams(if (vertical) 1 else dp(size), if (vertical) dp(size) else 1),
        )
    }

    fun fill(parent: LinearLayout, view: View, height: Int = -2) =
        parent.addView(view, LinearLayout.LayoutParams(-1, height))

    fun weight(parent: LinearLayout, view: View) =
        parent.addView(view, LinearLayout.LayoutParams(0, -2, 1f))

    fun glyph(kind: Glyph.Kind, color: Int, size: Int) = Glyph(kind, color).sized(dp(size))

    fun icon(kind: Glyph.Kind, color: Int, size: Int = 24) =
        ImageView(context).apply {
            setImageDrawable(glyph(kind, color, size))
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    /** A 48dp circular icon button with a borderless ripple. */
    fun iconButton(kind: Glyph.Kind, label: String, color: Int = Palette.TEXT, action: () -> Unit) =
        icon(kind, color).apply {
            contentDescription = label
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            pressable(this, null, -1)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { action() }
        }

    enum class Style { PRIMARY, SECONDARY, GHOST, DANGER }

    /** A pill whose icon and label stay centred together. */
    class Pill(context: Context, val label: TextView) : FrameLayout(context)

    fun button(label: String, style: Style, glyph: Glyph.Kind? = null, action: () -> Unit): Pill {
        val color =
            when (style) {
                Style.PRIMARY -> Palette.ON_ACCENT
                Style.GHOST -> Palette.ACCENT
                Style.DANGER -> Palette.RED
                else -> Palette.TEXT
            }
        val text =
            text(label, 15f, color, medium).apply {
                gravity = Gravity.CENTER
                if (glyph != null) {
                    setCompoundDrawablesRelative(glyph(glyph, color, 18), null, null, null)
                    compoundDrawablePadding = dp(8)
                }
            }
        return Pill(context, text).apply {
            minimumHeight = dp(52)
            setPadding(dp(20), 0, dp(20), 0)
            addView(text, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            pressable(
                this,
                when (style) {
                    Style.PRIMARY -> gradient(26)
                    Style.SECONDARY -> rounded(Palette.SURFACE_HI, 26)
                    else -> null
                },
                26,
                style == Style.PRIMARY,
            )
            setOnClickListener { action() }
        }
    }

    /** Wraps content so a centred button hugs its label instead of stretching edge to edge. */
    fun centered(view: View) =
        FrameLayout(context).apply {
            addView(view, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        }

    /** Primary fill: violet to orchid, with a soft top highlight. */
    fun gradient(radius: Int) =
        GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Palette.ACCENT, 0xFFC4A1FF.toInt(), 0xFFE0A6F5.toInt()),
            )
            .apply { cornerRadius = dp(radius).toFloat() }

    /** Page backdrop: near-black with a violet glow from the top. */
    fun backdrop(): Drawable =
        android.graphics.drawable.LayerDrawable(
            arrayOf(
                android.graphics.drawable.ColorDrawable(Palette.BG),
                GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    gradientRadius = context.resources.displayMetrics.widthPixels * .9f
                    setGradientCenter(.5f, 0f)
                    colors = intArrayOf(0x386D4AE8, 0x146D4AE8, 0x00000000)
                },
            ),
        )

    /** A progress bar whose fill can be animated. */
    class Meter(context: Context, private val heightPx: Int, private val color: Int) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        var fraction = 0f
            set(value) {
                field = value
                invalidate()
            }

        init {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) =
            setMeasuredDimension(getDefaultSize(0, widthSpec), heightPx)

        override fun onDraw(canvas: android.graphics.Canvas) {
            val r = height / 2f
            paint.shader = null
            paint.color = Palette.STROKE
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            val f = fraction.coerceIn(0f, 1f)
            if (f <= 0f) return
            paint.color = color
            canvas.drawRoundRect(0f, 0f, maxOf(height.toFloat(), width * f), height.toFloat(), r, r, paint)
            paint.color = 0x33FFFFFF
            canvas.drawRoundRect(0f, 0f, maxOf(height.toFloat(), width * f), r, r, r, paint)
        }
    }

    /** Circular download progress; a null fraction spins while the size is still unknown. */
    class Ring(context: Context, private val color: Int) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        var fraction: Float? = null
            set(value) {
                field = value
                invalidate()
            }

        init {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val stroke = minOf(width, height) * .12f
            paint.strokeWidth = stroke
            val inset = stroke / 2 + paddingLeft
            val box = android.graphics.RectF(inset, inset, width - inset, height - inset)
            paint.color = Palette.STROKE
            canvas.drawOval(box, paint)
            paint.color = color
            val f = fraction
            if (f == null) {
                val start = (android.os.SystemClock.uptimeMillis() % 1000) * .36f
                canvas.drawArc(box, start, 90f, false, paint)
                postInvalidateOnAnimation()
            } else canvas.drawArc(box, -90f, 360f * f.coerceIn(.02f, 1f), false, paint)
        }
    }

    fun ring(fraction: Float?, color: Int = Palette.ACCENT) = Ring(context, color).apply { this.fraction = fraction }

    fun meter(fraction: Float, color: Int = Palette.ACCENT, height: Int = 8) =
        Meter(context, dp(height), color).apply { this.fraction = fraction }

    /** Player level: number inside a ring that fills toward the next level. */
    fun levelBadge(level: Int, fraction: Float, size: Int = 40): View =
        object : View(context) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

            override fun onDraw(canvas: android.graphics.Canvas) {
                val stroke = dp(3).toFloat()
                val r = width / 2f - stroke
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = Palette.SURFACE_HI
                canvas.drawCircle(width / 2f, height / 2f, r, paint)
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.strokeCap = android.graphics.Paint.Cap.ROUND
                paint.color = Palette.STROKE
                canvas.drawCircle(width / 2f, height / 2f, r, paint)
                paint.color = Palette.GOLD
                canvas.drawArc(stroke, stroke, width - stroke, height - stroke, -90f, 360f * fraction, false, paint)
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = Palette.TEXT
                paint.typeface = heavy
                paint.textAlign = android.graphics.Paint.Align.CENTER
                paint.textSize = dp(if (level >= 100) 11 else 14).toFloat()
                canvas.drawText("$level", width / 2f, height / 2f + paint.textSize * .36f, paint)
            }
        }.apply {
            minimumWidth = dp(size)
            minimumHeight = dp(size)
            contentDescription = "Player level $level"
        }

    fun card(radius: Int = 20) =
        column().apply {
            background = rounded(Palette.SURFACE, radius)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

    fun progress(fraction: Float, color: Int = Palette.ACCENT, height: Int = 6): View =
        object : View(context) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

            override fun onMeasure(widthSpec: Int, heightSpec: Int) =
                setMeasuredDimension(getDefaultSize(0, widthSpec), dp(height))

            override fun onDraw(canvas: android.graphics.Canvas) {
                val r = this.height / 2f
                paint.color = Palette.STROKE
                canvas.drawRoundRect(0f, 0f, width.toFloat(), this.height.toFloat(), r, r, paint)
                val f = fraction.coerceIn(0f, 1f)
                if (f <= 0f) return
                paint.color = color
                canvas.drawRoundRect(
                    0f,
                    0f,
                    maxOf(this.height.toFloat(), width * f),
                    this.height.toFloat(),
                    r,
                    r,
                    paint,
                )
            }
        }.apply {
            minimumHeight = dp(height)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    /** Three stars; earned ones in gold. */
    fun stars(count: Int, size: Int, spacing: Int = 2, empty: Int = Palette.STROKE) =
        row().apply {
            contentDescription = "$count of 3 stars"
            for (i in 1..3) {
                if (i > 1) gap(this, spacing)
                addView(icon(Glyph.Kind.STAR, if (i <= count) Palette.GOLD else empty, size))
            }
        }

    /** Pill-shaped single-choice control; returns the container. */
    fun segmented(
        labels: List<String>,
        selected: Int,
        track: Int = Palette.BG,
        onSelect: (Int) -> Unit,
    ): LinearLayout {
        val group = row().apply {
            background = rounded(track, 22)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val options = ArrayList<TextView>()
        fun paint(index: Int) =
            options.forEachIndexed { i, view ->
                val active = i == index
                view.setTextColor(if (active) Palette.ON_ACCENT else Palette.MUTED)
                pressable(view, if (active) rounded(Palette.ACCENT, 18) else null, 18, active)
                view.isSelected = active
            }
        labels.forEachIndexed { index, label ->
            val option =
                text(label, 14f, Palette.MUTED, medium).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(40)
                    setOnClickListener {
                        paint(index)
                        onSelect(index)
                    }
                }
            options.add(option)
            weight(group, option)
        }
        paint(selected)
        return group
    }

    /** − value + control. [format] renders the value; [step] is applied per tap. */
    fun stepper(
        value: Int,
        range: IntRange,
        step: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit,
    ): LinearLayout {
        var current = value
        val group = row().apply { background = rounded(Palette.BG, 22) }
        val label = text(format(current), 15f, Palette.TEXT, medium).apply {
            gravity = Gravity.CENTER
            minWidth = dp(76)
        }
        lateinit var minus: ImageView
        lateinit var plus: ImageView
        fun refresh() {
            label.text = format(current)
            minus.alpha = if (current - step >= range.first) 1f else .3f
            plus.alpha = if (current + step <= range.last) 1f else .3f
        }
        fun change(delta: Int) {
            val next = (current + delta).coerceIn(range.first, range.last)
            if (next == current) return
            current = next
            refresh()
            onChange(current)
        }
        minus = iconButton(Glyph.Kind.MINUS, "Decrease") { change(-step) }
        plus = iconButton(Glyph.Kind.PLUS, "Increase") { change(step) }
        group.addView(minus, LinearLayout.LayoutParams(dp(44), dp(44)))
        group.addView(label)
        group.addView(plus, LinearLayout.LayoutParams(dp(44), dp(44)))
        refresh()
        return group
    }
}
