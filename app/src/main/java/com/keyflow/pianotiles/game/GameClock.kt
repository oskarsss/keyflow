package com.keyflow.pianotiles.game

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.SystemClock
import kotlin.math.abs

/** Audio position, so rendering and judgement share one clock. */
interface GameClock {
    val positionMs: Int

    /** Song position for rendering and judgement, finer than [positionMs] where the player allows. */
    val seconds: Double
        get() = positionMs / 1000.0

    /** Song position when an input event happened at [uptimeMs] ([SystemClock.uptimeMillis]). */
    fun secondsAt(uptimeMs: Long): Double = seconds

    val durationMs: Int
    val isPlaying: Boolean

    /** True once playback can be heard, read after [seconds]. */
    val audible: Boolean
        get() = isPlaying

    /** True once playback reached the end, even if the reported position falls short of it. */
    val completed: Boolean
        get() = false

    fun start(speed: Float)

    fun volume(level: Float) {}

    fun pause()
}

class MediaClock(val player: MediaPlayer) : GameClock {
    @Volatile override var completed = false
        private set

    init {
        // VBR MP3 durations are estimates, so the position alone cannot tell when a song is over.
        player.setOnCompletionListener { completed = true }
    }

    override val positionMs
        get() = player.currentPosition

    private val steady = SteadyPosition()

    override val seconds: Double
        get() = steady.update(player.currentPosition / 1000.0, player.isPlaying, System.nanoTime())

    override fun secondsAt(uptimeMs: Long): Double {
        val position = seconds
        if (!steady.running) return position
        val age = (SystemClock.uptimeMillis() - uptimeMs).coerceIn(0L, MAX_INPUT_AGE_MS)
        return position - age / 1000.0 * steady.rate
    }

    override val durationMs
        get() = player.duration

    override val isPlaying
        get() = player.isPlaying

    override val audible
        get() = steady.running

    override fun start(speed: Float) {
        steady.rate = speed.toDouble()
        player.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
        player.start()
    }

    private companion object {
        const val MAX_INPUT_AGE_MS = 100L
    }

    override fun pause() {
        if (player.isPlaying) player.pause()
    }

    override fun volume(level: Float) = player.setVolume(level, level)
}

/**
 * MediaPlayer reports its position in coarse, uneven steps. This follows it with a steady clock
 * that runs at the playback speed and only eases toward the reported position.
 */
class SteadyPosition {
    var rate = 1.0
    private var smoothed = 0.0
    private var smoothedAt = 0L
    private var held = Double.NaN

    /** True once the reported position moves, so extrapolation follows audible output. */
    val running
        get() = smoothedAt != 0L && held.isNaN()

    fun update(reported: Double, playing: Boolean, nanos: Long): Double {
        if (!playing) {
            smoothedAt = 0L
            return reported
        }
        when {
            smoothedAt == 0L -> held = reported
            // Output has not started yet; extrapolating now would run ahead of the music.
            held == reported -> {}
            !held.isNaN() -> held = Double.NaN
            else -> {
                val predicted = smoothed + (nanos - smoothedAt) / 1e9 * rate
                val drift = reported - predicted
                smoothed = if (abs(drift) > RESYNC) reported else predicted + drift * EASE
                smoothedAt = nanos
                return smoothed
            }
        }
        smoothed = reported
        smoothedAt = nanos
        return reported
    }

    private companion object {
        const val RESYNC = .060
        const val EASE = .06
    }
}

/**
 * Joins the countdown's timeline to the music's. Tiles keep moving while the audio output starts,
 * then any difference closes by easing their speed, so they never jump or step back.
 */
class Handover(private val rate: Double) {
    private var startedAt = 0L
    private var gap = Double.NaN
    private var gapAt = 0L
    private var closing = 0.0

    /** Ends the handover at once, for a pause, where the jump is not seen. */
    fun settle() {
        if (startedAt != 0L) gap = 0.0
    }

    /** Song time shown minus [audio] at the last [time] call; touches are judged on the same timeline. */
    var lead = 0.0
        private set

    fun time(audio: Double, audible: Boolean, countdownEnd: Double, nanos: Long): Double {
        if (startedAt == 0L) startedAt = nanos
        val shown = when {
            gap.isNaN() && !audible -> countdownEnd + (nanos - startedAt) / 1e9 * rate
            gap.isNaN() -> {
                val continued = countdownEnd + (nanos - startedAt) / 1e9 * rate
                // Output delay only leaves the music behind, and a cold first start can take over
                // half a second. Music far ahead, or very far behind, is a real jump in the song.
                gap = (continued - audio).takeIf { it in -MAX_AHEAD..MAX_DELAY } ?: 0.0
                gapAt = nanos
                // Slow enough that tiles never run slower than half speed or move backwards.
                closing = maxOf(MIN_CLOSE, 3 * abs(gap) / rate)
                audio + gap
            }
            gap == 0.0 -> audio
            else -> {
                val t = ((nanos - gapAt) / 1e9 / closing).coerceIn(0.0, 1.0)
                audio + gap * (1 - t * t * (3 - 2 * t))
            }
        }
        lead = shown - audio
        return shown
    }

    private companion object {
        const val MAX_AHEAD = .5
        const val MAX_DELAY = 1.5
        const val MIN_CLOSE = .8
    }
}
