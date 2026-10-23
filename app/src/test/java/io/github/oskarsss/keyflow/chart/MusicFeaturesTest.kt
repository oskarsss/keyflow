package io.github.oskarsss.keyflow.chart

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class MusicFeaturesTest {
    @Test
    fun centeredLogMelMatchesIndependentSlaneyReferenceIncludingBothPaddedEdges() {
        val rate = MusicFeatures.RATE
        val pcm = ByteArray((rate * 1.2).toInt() * 2)
        for (i in 0 until pcm.size / 2) {
            val t = i.toDouble() / rate
            val wave = .35 * sin(2 * PI * 220 * t) + .15 * sin(2 * PI * 731 * t) + .25 * exp(-((t - .35) / .006).pow(2))
            val value = (wave * 30000).toInt()
            pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
        }
        val features = MusicFeatures.readPcm(ByteArrayInputStream(pcm))
        val expected = javaClass.getResourceAsStream("/rhythm/frontend-reference.f32")!!.use { it.readBytes() }
        val reference = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        assertEquals(61, features.frames)
        assertEquals(1.2, features.duration, 1e-9)
        for (frame in listOf(0, 1, 7, 25, 59, 60)) for (band in 0 until 128) {
            assertEquals("Frame $frame band $band", reference.get(), features.mel[frame * 128 + band], .00003f)
        }
    }

    @Test
    fun silenceRemainsZeroAndEmptyInputDoesNotCreateFrames() {
        val silence = MusicFeatures.readPcm(ByteArrayInputStream(ByteArray(MusicFeatures.RATE * 2)))
        assertEquals(51, silence.frames)
        assertTrue(silence.mel.all { it == 0f })
        assertTrue(silence.chroma.all { it == 0f })
        assertEquals(0, MusicFeatures.readPcm(ByteArrayInputStream(byteArrayOf())).frames)
    }
}
