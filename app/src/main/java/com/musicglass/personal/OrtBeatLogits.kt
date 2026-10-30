package com.musicglass.personal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import android.util.Log
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Beat This! small0 on ONNX Runtime. Only [BeatModel] makes one, and only when the runtime is in this build, so a
 * release build (which has none) never loads this class. Each run is logged with its time.
 *
 * On the owner's phone (30 Oct) a chunk of 1,500 frames took 1.4 to 4 s, but in each of two runs one chunk stalled
 * for minutes (207 s, 454 s) while the phone was short of memory. So a run is stopped after [RUN_LIMIT_MS]; the
 * error that gives makes the window keep stage 2's beats (EnvelopeBuilder).
 */
internal class OrtBeatLogits(model: ByteArray) : BeatLogits {
    // Its telemetry off: Riff sends no usage events (its starting provider is removed from the manifest too).
    private val env = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
    private val session = env.createSession(model, OrtSession.SessionOptions().apply {
        // Two threads: on the PC two were 25 % faster than one, four little more than two.
        setIntraOpNumThreads(2)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })

    override fun run(spect: FloatArray, frames: Int): Pair<FloatArray, FloatArray> {
        val started = SystemClock.elapsedRealtime()
        OrtSession.RunOptions().use { options ->
            val stop = watchdog.schedule({ runCatching { options.setTerminate(true) } }, RUN_LIMIT_MS, TimeUnit.MILLISECONDS)
            try {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(spect), longArrayOf(1, frames.toLong(), MODEL_BANDS.toLong())).use { input ->
                    session.run(mapOf("spect" to input), options).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val beat = (out[0].value as Array<FloatArray>)[0]
                        @Suppress("UNCHECKED_CAST")
                        val downbeat = (out[1].value as Array<FloatArray>)[0]
                        Log.i("RiffBeats", "small0: $frames frames in ${SystemClock.elapsedRealtime() - started} ms")
                        return beat to downbeat
                    }
                }
            } finally {
                stop.cancel(false)
            }
        }
    }

    private companion object {
        const val RUN_LIMIT_MS = 30_000L
        val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "riff-beat-watchdog").apply { isDaemon = true } }
    }
}
