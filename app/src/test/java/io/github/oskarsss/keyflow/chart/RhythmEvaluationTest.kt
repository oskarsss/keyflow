package io.github.oskarsss.keyflow.chart

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opt-in export of the Kotlin beat-model frontend, which tools/evaluate_rhythm.py scores against human annotations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RhythmEvaluationTest {
    @Test
    fun exportEvaluationFeatures() {
        val path = System.getenv("KEYFLOW_RHYTHM_EVALUATION")
        assumeTrue("Set KEYFLOW_RHYTHM_EVALUATION to a directory of .pcm22 files", path != null)
        val root = File(path!!)
        for (input in root.listFiles().orEmpty().filter { it.extension == "pcm22" }.sortedBy { it.name }) {
            val name = input.nameWithoutExtension
            val features = input.inputStream().use(MusicFeatures::readPcm)
            val buffer = ByteBuffer.allocate(features.mel.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            features.mel.forEach(buffer::putFloat)
            File(root, "$name.mel.f32").writeBytes(buffer.array())
        }
    }
}
