package com.keyflow.pianotiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameViewTouchTest {
    private class Clock : GameClock {
        override var positionMs = 2000
        override val durationMs = 10_000
        override var isPlaying = false
        override fun start(speed: Float) { isPlaying = true }
        override fun pause() { isPlaying = false }
    }

    @Test
    fun earlyTouchUsesTheProjectedTileAndRemovesItWithOnePenalty() {
        val near = GameSession.Note(2.1, 2)
        val early = GameSession.Note(3.0, 3)
        val game = GameSession(listOf(near, early, GameSession.Note(4.0, 3)))
        val clock = Clock()
        val view = GameView(
            RuntimeEnvironment.getApplication(), game, clock, GameView.Options(haptics = false),
            object : GameView.Listener {
                override fun finished(session: GameSession) {}
                override fun pauseRequested() {}
            },
        )
        view.layout(0, 0, 400, 800)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        val before = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        val after = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(before))
            // The far-right tile is projected over screen lane 2, above the strike zone.
            val p = ((early.time - 2.0) / 1.7 + .15 / 2).toFloat()
            val depth = 1 + p * 1.1f
            val horizon = (2.1f * 70 - 708) / 1.1f
            val x = 200 + 150 / depth
            val y = horizon + (708 - horizon) / depth
            assertEquals(2, (x / 100).toInt())
            touch(view, x, y)
            assertEquals(3, early.judgement)
            assertEquals(0, near.judgement)
            assertEquals(1, game.misses)
            // Let the miss shake finish while keeping the audio clock fixed for comparison.
            view.draw(Canvas(after))
            ShadowSystemClock.advanceBy(Duration.ofMillis(250))
            view.draw(Canvas(after))
            assertNotEquals("The early tile must disappear", before.getPixel(x.toInt(), y.toInt()), after.getPixel(x.toInt(), y.toInt()))
            clock.positionMs = 2100
            touch(view, 250f, 780f)
            assertEquals(1, game.hits)
            clock.positionMs = 3300
            view.draw(Canvas(after))
            assertEquals(1, game.misses)
            assertEquals(GameSession.MAX_LIVES - 1, game.lives)
        } finally {
            view.stop()
            before.recycle()
            after.recycle()
        }
    }

    private fun touch(view: GameView, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
}
