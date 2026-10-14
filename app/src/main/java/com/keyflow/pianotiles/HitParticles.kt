package com.keyflow.pianotiles

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Canvas adaptation of sandeepsj/piano-falling-tiles, ParticleSystem.kt (MIT). Copyright (c) 2025
 * sandeepsj. See THIRD_PARTY_NOTICES.md.
 */
class HitParticles {
    private class Particle {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var life = 0f
        var color = 0
    }

    private val particles = Array(50) { Particle() }

    fun emit(x: Float, y: Float, color: Int, density: Float) {
        repeat(12) {
            val particle = particles.firstOrNull { it.life <= 0 } ?: return
            val angle = Random.nextFloat() * Math.PI.toFloat() * 2
            val speed = (1 + Random.nextFloat() * 3) * density
            particle.x = x
            particle.y = y
            particle.vx = cos(angle) * speed
            particle.vy = sin(angle) * speed - 2 * density
            particle.life = 1f
            particle.color = color
        }
    }

    fun draw(canvas: Canvas, paint: Paint, elapsedMs: Float, density: Float) {
        for (particle in particles) {
            if (particle.life <= 0) continue
            particle.x += particle.vx * elapsedMs * .1f
            particle.y += particle.vy * elapsedMs * .1f
            particle.life = maxOf(0f, particle.life - elapsedMs / 500)
            paint.color = (particle.color and 0xFFFFFF) or ((particle.life * 255).toInt() shl 24)
            canvas.drawCircle(particle.x, particle.y, density * (1 + particle.life * 2), paint)
        }
    }
}
