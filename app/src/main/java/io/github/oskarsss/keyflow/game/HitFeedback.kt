package io.github.oskarsss.keyflow.game

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.HapticFeedbackConstants
import io.github.oskarsss.keyflow.ui.Palette
import kotlin.math.abs

/**
 * Turns score and life changes into flashes, particles, banners and haptics, and draws the
 * judgement and banner text.
 */
internal class HitFeedback(
    private val highway: Highway,
    private val session: GameSession,
    private val haptics: Boolean,
    private val haptic: (Int) -> Unit,
) {
    val particles = HitParticles()
    val laneGlowAt = LongArray(4)
    val laneGlowColor = IntArray(4)
    var comboAt = 0L
        private set
    var shakeAt = 0L
        private set
    var displayedScore = 0f
        private set

    private var observedHits = 0
    private var observedMisses = 0
    private var observedBonuses = 0
    private var observedMultiplier = 1
    private var observedChords = 0
    private var chordAt = -1000L
    private var judgement = ""
    private var judgementColor = 0
    private var judgementAt = 0L
    private var bannerText = ""
    private var bannerColor = 0
    private var bannerAt = 0L

    fun observe(now: Long, baseScore: Int) {
        with(highway) {
            val lane = w / 4
            if (session.hits > observedHits) {
                observedHits = session.hits
                if (haptics && (session.lastCompletedKind == "hold" || session.lastCompletedKind == "slide"))
                    haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                val perfect = session.lastQuality == 1
                val color = if (perfect) Palette.MINT else Palette.ACCENT
                judgement = if (perfect) "PERFECT" else if (session.lastTimingErrorMs < 0) "GOOD · EARLY" else "GOOD · LATE"
                judgementColor = color
                judgementAt = now
                comboAt = now
                val l = session.lastCompletedLane.coerceIn(0, 3)
                laneGlowAt[l] = now
                laneGlowColor[l] = color
                particles.emit((l + .5f) * lane, line, color, density)
                val combo = session.combo
                if (combo == 25 || combo == 50 || (combo >= 100 && combo % 50 == 0))
                    banner("$combo COMBO!", Palette.MINT, now)
            }
            if (session.bonuses > observedBonuses) {
                observedBonuses = session.bonuses
                judgement = "+${GameSession.GOLD_BONUS}  ♥"
                judgementColor = Palette.GOLD
                val l = session.lastCompletedLane.coerceIn(0, 3)
                laneGlowColor[l] = Palette.GOLD
                particles.emit((l + .5f) * lane, line, Palette.GOLD, density)
                particles.emit((l + .5f) * lane, line, 0xFFFFF1B8.toInt(), density)
            }
            if (session.multiplier != observedMultiplier) {
                if (session.multiplier > observedMultiplier)
                    banner(
                        if (session.multiplier >= 4) "FEVER ×4" else "×${session.multiplier}",
                        tier(session.multiplier),
                        now,
                    )
                observedMultiplier = session.multiplier
            }
            if (session.misses > observedMisses) {
                observedMisses = session.misses
                judgement = "MISS"
                judgementColor = Palette.RED
                judgementAt = now
                shakeAt = now
                val l = session.lastMissLane.coerceIn(0, 3)
                laneGlowAt[l] = now
                laneGlowColor[l] = Palette.RED
                if (haptics)
                    haptic(
                        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
                        else HapticFeedbackConstants.LONG_PRESS,
                    )
            }
            if (session.chordBonuses > observedChords) {
                observedChords = session.chordBonuses
                chordAt = now
                val name = when (session.lastChordLanes.size) { 2 -> "DOUBLE!"; 3 -> "TRIPLE!"; else -> "QUAD!" }
                banner("$name +${session.lastChordBonus}", Palette.GOLD, now)
                for (l in session.lastChordLanes) {
                    laneGlowAt[l] = now
                    laneGlowColor[l] = Palette.GOLD
                    particles.emit((l + .5f) * lane, line, Palette.GOLD, density, 14)
                }
            }
            val target = (baseScore + session.score).toFloat()
            displayedScore =
                if (abs(target - displayedScore) < 1) target
                else displayedScore + (target - displayedScore) * .25f
        }
    }

    private fun banner(value: String, color: Int, now: Long) {
        bannerText = value
        bannerColor = color
        bannerAt = now
    }

    fun drawChordEffect(canvas: Canvas, now: Long) {
        with(highway) {
            val age = (now - chordAt) / 650f
            if (age !in 0f..1f) return
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f) * (1 - age)
            paint.color = alpha(Palette.GOLD, 1 - age)
            for (l in session.lastChordLanes) {
                canvas.drawCircle((l + .5f) * w / 4, line, dp(14f + 54f * age), paint)
            }
            val lanes = session.lastChordLanes
            if (lanes.size >= 2) {
                canvas.drawLine((lanes.min() + .5f) * w / 4, line - dp(10f + age * 28),
                    (lanes.max() + .5f) * w / 4, line - dp(10f + age * 28), paint)
            }
            paint.style = Paint.Style.FILL
        }
    }

    fun draw(canvas: Canvas, now: Long) {
        with(highway) {
            val age = now - judgementAt
            if (age < 450 && judgement.isNotEmpty()) {
                val pop = (age / 110f).coerceIn(0f, 1f)
                text(
                    canvas,
                    judgement,
                    20f + 7f * (1 - pop),
                    alpha(judgementColor, 1 - ((age - 250) / 200f).coerceIn(0f, 1f)),
                    cx,
                    line - dp(64f) - dp(6f) * pop,
                    face = heavy,
                )
            }
            val b = now - bannerAt
            if (b < 1000 && bannerText.isNotEmpty()) {
                val pop = (b / 160f).coerceIn(0f, 1f)
                val scale = 1.4f - .4f * pop
                val fade = 1 - ((b - 700) / 300f).coerceIn(0f, 1f)
                val y = yAt(.62f)
                text(canvas, bannerText, 34f * scale, alpha(0x66000000, fade), cx + dp(2f), y + dp(2f), face = heavy)
                text(canvas, bannerText, 34f * scale, alpha(bannerColor, fade), cx, y, face = heavy)
            }
        }
    }

    companion object {
        /** The colour of a score multiplier. */
        fun tier(multiplier: Int) =
            when (multiplier) {
                2 -> Palette.SKY
                3 -> Palette.MINT
                4 -> Palette.GOLD
                else -> Palette.MUTED
            }
    }
}
