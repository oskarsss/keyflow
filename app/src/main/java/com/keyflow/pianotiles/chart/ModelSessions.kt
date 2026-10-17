package com.keyflow.pianotiles.chart

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the bundled models loaded while songs are being prepared one after another, instead of
 * loading and optimizing each graph again for every song. Once preparation has been idle for
 * [IDLE_MS], the sessions close so they don't hold memory during play.
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

    fun <T> use(vararg models: File, block: (List<OrtSession>) -> T): T {
        val sessions = synchronized(lock) {
            closing?.cancel(false)
            closing = null
            users++
            try {
                models.map { open.getOrPut(it.path) { OrtEnvironment.getEnvironment().createSession(it.path, options) } }
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

    private fun release() {
        if (--users == 0) closing = timer.schedule(::closeIdle, IDLE_MS, TimeUnit.MILLISECONDS)
    }

    private fun closeIdle() = synchronized(lock) {
        if (users > 0) return@synchronized
        open.values.forEach { it.close() }
        open.clear()
    }
}
