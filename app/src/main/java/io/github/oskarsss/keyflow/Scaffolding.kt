package io.github.oskarsss.keyflow

import android.content.res.ColorStateList
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import io.github.oskarsss.keyflow.ui.Glyph
import io.github.oskarsss.keyflow.ui.Palette
import io.github.oskarsss.keyflow.ui.TileArtwork

internal fun MainActivity.applyInsets(view: View, keyboard: Boolean) {
    view.setOnApplyWindowInsetsListener { target, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val types =
                WindowInsets.Type.systemBars() or if (keyboard) WindowInsets.Type.ime() else 0
            val safe = insets.getInsets(types)
            target.setPadding(safe.left, safe.top, safe.right, safe.bottom)
        } else
            @Suppress("DEPRECATION")
            target.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
        insets
    }
}

/** Builds a screen: optional fixed top bar, scrolling body, optional fixed footer. */
internal fun MainActivity.page(
    screen: Screen,
    bar: View?,
    footer: View? = null,
    centered: Boolean = false,
): LinearLayout {
    this.screen = screen
    rows.clear()
    hero = null
    heroQuick = null
    val root = ui.column().apply { background = ui.backdrop() }
    applyInsets(root, true)
    if (bar != null) root.addView(bar, LinearLayout.LayoutParams(-1, dp(64)))
    val scroller =
        ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    scroll = scroller
    root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
    // Centred pages default children to wrap_content so they actually sit in the middle.
    val body =
        (if (centered)
                object : LinearLayout(this) {
                    override fun generateDefaultLayoutParams() = LayoutParams(-2, -2)
                }
            else LinearLayout(this))
            .apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(if (bar == null) 32 else 4), dp(20), dp(24))
                if (centered) gravity = Gravity.CENTER_HORIZONTAL
            }
    scroller.addView(body)
    if (footer != null)
        root.addView(
            footer.apply { setPadding(dp(20), dp(8), dp(20), dp(16)) },
            LinearLayout.LayoutParams(-1, -2),
        )
    setContentView(root)
    return body
}

internal fun MainActivity.bar(title: String?, back: Glyph.Kind = Glyph.Kind.BACK, onBack: (() -> Unit)?) =
    ui.row().apply {
        setPadding(dp(8), 0, dp(8), 0)
        if (onBack != null)
            addView(
                ui.iconButton(back, if (back == Glyph.Kind.CLOSE) "Close" else "Back") {
                    onBack()
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
        ui.weight(
            this,
            ui.oneLine(ui.text(title ?: "", 19f, Palette.TEXT, ui.medium)).apply {
                setPadding(dp(if (onBack == null) 12 else 4), 0, 0, 0)
            },
        )
    }

internal fun MainActivity.section(parent: LinearLayout, label: String): LinearLayout {
    ui.gap(parent, 20)
    ui.fill(parent, ui.caption(label).apply { setPadding(dp(4), 0, 0, dp(10)) })
    val card = ui.column().apply { background = ui.rounded(Palette.SURFACE, 20) }
    ui.fill(parent, card)
    return card
}

internal fun MainActivity.divider(parent: LinearLayout) {
    parent.addView(
        View(this).apply { setBackgroundColor(Palette.STROKE) },
        LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(18), 0, dp(18), 0) },
    )
}

internal fun MainActivity.labels(title: String, subtitle: String?) =
    ui.column().apply {
        addView(ui.text(title, 15f, Palette.TEXT, ui.medium))
        if (subtitle != null) {
            ui.gap(this, 4)
            addView(ui.text(subtitle, 12.5f, Palette.MUTED))
        }
    }

internal fun MainActivity.artwork(variant: Int, radius: Int) =
    FrameLayout(this).apply {
        background = ui.rounded(Palette.SURFACE_HI, radius)
        clipToOutline = true
        addView(TileArtwork(this@artwork, variant), FrameLayout.LayoutParams(-1, -1))
    }

internal fun MainActivity.spinner(indeterminate: Boolean = true) =
    ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = indeterminate
        max = 100
        val tint = ColorStateList.valueOf(Palette.ACCENT)
        progressTintList = tint
        indeterminateTintList = tint
        progressBackgroundTintList = ColorStateList.valueOf(Palette.STROKE)
    }
