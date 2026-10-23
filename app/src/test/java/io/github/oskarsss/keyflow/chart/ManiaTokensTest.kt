package io.github.oskarsss.keyflow.chart

import java.nio.FloatBuffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManiaTokensTest {
    private fun vocabulary() = RuntimeEnvironment.getApplication().assets.open("chart/tokenizer.json")
        .bufferedReader().use { ManiaTokens(JSONObject(it.readText())) }

    @Test
    fun promptMatchesTheHumanTrainedModelsConditioningAndPreservesMusicalHistory() {
        val tokens = vocabulary()
        val rhythm = MusicalRhythm(doubleArrayOf(.12, .58), doubleArrayOf(.12))
        val prompt = tokens.prompt(0, 32000, rhythm, emptyList(), 1.8)
        assertArrayEquals(longArrayOf(4100, 4104, 11731, 11771, 11825, 11830, 12141, 12158,
            12170, 12137, 2995, 11888, 1, 3, 21, 4083, 67, 4081, 4, 5), prompt)
        val history = listOf(ManiaTokens.Event(tokens.encode("t", 123), 12345),
            ManiaTokens.Event(tokens.encode("column", 2), 12345), ManiaTokens.Event(tokens.encode("circle", 0), 12345))
        val next = tokens.prompt(9825, 32000, rhythm, history, 1.8)
        assertArrayEquals(longArrayOf(tokens.timeToken(2520).toLong(), tokens.encode("column", 2).toLong(),
            tokens.encode("circle", 0).toLong()), next.takeLast(3).toLongArray())
    }

    @Test
    fun cachedDecoderCannotGoBackInTimeOrAskForMoreThanFourKeys() {
        val tokens = vocabulary()
        val logits = FloatArray(4097) { -10f }
        logits[tokens.encode("t", 100)] = 20f
        logits[tokens.encode("column", 12)] = 30f
        logits[tokens.encode("t", 400)] = 5f
        assertEquals(tokens.encode("t", 400), tokens.choose(FloatBuffer.wrap(logits), longArrayOf(1, 5),
            listOf(tokens.encode("t", 350)), 327))
    }

    @Test
    fun weakImmediateStopsDoNotDiscardRhythmicIntrosWhileSilenceAndConfidentStopsRemainAllowed() {
        val tokens = vocabulary()
        val logits = FloatArray(4097) { -20f }
        val timestamp = tokens.timeToken(1600)
        logits[6] = -1f; logits[timestamp] = -2.3f
        val prompt = longArrayOf(1, 5)
        assertEquals(timestamp, tokens.choose(FloatBuffer.wrap(logits), prompt, emptyList(), 0, true))
        assertEquals(6, tokens.choose(FloatBuffer.wrap(logits), prompt, emptyList(), 0, false))
        assertEquals(6, tokens.choose(FloatBuffer.wrap(logits), prompt, listOf(timestamp), 0, true))
        logits[6] = 5f
        assertEquals(6, tokens.choose(FloatBuffer.wrap(logits), prompt, emptyList(), 0, true))
    }

    @Test
    fun simultaneousHoldsArePairedByTheirOwnLaneAndUnclosedTailsDoNotInventDurations() {
        val tokens = vocabulary()
        val events = mutableListOf<ManiaTokens.Event>()
        fun event(time: Int, lane: Int, kind: String) {
            events.add(ManiaTokens.Event(tokens.timeToken(time), time))
            events.add(ManiaTokens.Event(tokens.encode("column", lane), time))
            events.add(ManiaTokens.Event(tokens.encode(kind, 0), time))
        }
        event(2000, 0, "hold_note"); event(2500, 2, "hold_note")
        event(3500, 2, "hold_note_end"); event(4000, 0, "hold_note_end")
        event(5000, 1, "hold_note")
        val notes = tokens.notes(events, 10.0)
        assertEquals(3, notes.size)
        assertEquals(2.0, notes[0].duration, 1e-9)
        assertEquals(1.0, notes[1].duration, 1e-9)
        assertEquals("tap", notes[2].kind)
    }
}
