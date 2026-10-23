package io.github.oskarsss.keyflow.chart

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class ChartAudioTest {
    @Test
    fun powerMelMatchesTheTrainedFrontendAtBothWindowEdgesAndInTheMiddle() {
        val audio = FloatArray(ChartAudio.SAMPLES) { i ->
            val t = i.toDouble() / ChartAudio.RATE
            val waveform = (.35 * sin(2 * PI * 220 * t) + .2 * sin(2 * PI * 330 * t)) * exp(-((t * 2) % 1) * 7)
            (waveform * 30000).toInt().toShort() / 32768f
        }
        val actual = ChartAudio.frontend(audio)
        val bytes = javaClass.getResourceAsStream("/rhythm/chart-frontend-reference.f32")!!.use { it.readBytes() }
        val expected = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        for (frame in listOf(0, 1, 256, 512, 1024, 2047)) for (band in 0 until ChartAudio.BINS)
            assertEquals("Frame $frame band $band", expected.get(), actual[band * ChartAudio.FRAMES + frame], .0002f)
    }

    @Test
    fun onlyAudibleFreshAudioEnablesTheIntroCoverageGuard() {
        val file = kotlin.io.path.createTempFile("chart-audibility", ".pcm").toFile()
        val pcm = ByteBuffer.allocate(ChartAudio.RATE * 18 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until ChartAudio.RATE * 18) pcm.putShort(if (i < ChartAudio.RATE * 7)
            (10000 * sin(2 * PI * 220 * i / ChartAudio.RATE)).toInt().toShort() else 0)
        ChartAudio.readPcm(ByteArrayInputStream(pcm.array()), file).use {
            assertTrue(it.input(0).audible)
            assertFalse(it.input(1).audible)
        }
        assertFalse(file.exists())
    }

    @Test
    fun temporaryPcmNormalizesWholeSongAndDeletesItselfAfterPreparation() {
        val file = kotlin.io.path.createTempFile("chart-audio-test", ".pcm").toFile()
        val bytes = ByteArray(ChartAudio.RATE * 18 * 2)
        // A read split between the low/high byte must not change the peak or sample count.
        bytes[ChartAudio.RATE * 2] = 0; bytes[ChartAudio.RATE * 2 + 1] = 0x80.toByte()
        val split = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(length, 333))
        }
        ChartAudio.readPcm(split, file).use {
            assertEquals(18.0, it.duration, 1e-9)
            assertEquals(2, it.windows)
            assertEquals(9825, it.startMs(1).toInt())
            assertTrue(it.mel(1).all { value -> value == 0f })
            assertTrue(file.exists())
        }
        assertFalse(file.exists())
    }
}
