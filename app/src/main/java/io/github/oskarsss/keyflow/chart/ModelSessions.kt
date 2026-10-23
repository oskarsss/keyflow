package io.github.oskarsss.keyflow.chart

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the bundled models loaded while songs are being prepared one after another, instead of
 * loading and optimizing each graph again for every song. Once preparation has been idle for
 * [IDLE_MS], the sessions close so they don't hold memory during play.
 *
 * Models are stored uncompressed in the APK and read from it in place, so the phone needs no
 * second copy of them.
 */
internal object ModelSessions {
    private const val IDLE_MS = 60_000L
    private val lock = Any()
    private val open = HashMap<String, OrtSession>()
    private var users = 0
    private var closing: ScheduledFuture<*>? = null
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "model-sessions").apply { isDaemon = true }
    }

    // Two workers without spinning leave the rest of the phone, including the game, responsive.
    private val options by lazy {
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addConfigEntry("session.inter_op.allow_spinning", "0")
        }
    }

    /** Runs [block] with a session for each asset, which must have its [Model.bytes]. */
    fun <T> use(context: Context, vararg models: Model, block: (List<OrtSession>) -> T): T {
        val sessions = synchronized(lock) {
            closing?.cancel(false)
            closing = null
            users++
            try {
                models.map { model -> open.getOrPut(model.asset) { load(context, model) } }
            } catch (error: Throwable) {
                release()
                throw error
            }
        }
        try {
            return block(sessions)
        } finally {
            synchronized(lock) { release() }
        }
    }

    class Model(val asset: String, val bytes: Long)

    /** The session keeps its own copy of the weights, so the mapped file is let go once it is built. */
    private fun load(context: Context, model: Model): OrtSession =
        context.assets.openFd(model.asset).use { file ->
            check(file.declaredLength == model.bytes) { "The app's ${model.asset} is damaged. Reinstall Keyflow." }
            val bytes = FileInputStream(file.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, file.startOffset, file.declaredLength) }
            OrtEnvironment.getEnvironment().createSession(bytes, options)
        }

    private fun release() {
        if (--users == 0) closing = timer.schedule(::closeIdle, IDLE_MS, TimeUnit.MILLISECONDS)
    }

    private fun closeIdle() = synchronized(lock) {
        if (users > 0) return@synchronized
        open.values.forEach { it.close() }
        open.clear()
    }
}
