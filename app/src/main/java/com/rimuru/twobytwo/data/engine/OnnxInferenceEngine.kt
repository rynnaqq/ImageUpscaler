package com.rimuru.twobytwo.data.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.ModelProvider
import com.rimuru.twobytwo.domain.engine.TensorCodec
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.ModelProfile
import kotlinx.coroutines.CancellationException
import java.nio.FloatBuffer

/**
 * ONNX Runtime Mobile engine (PRD TC-2). Models load from assets → filesDir cache
 * with sha256 verification (PRD §5.5). HW-3: any session-creation failure falls
 * back to CPU — no crash mid-job.
 *
 * ponytail: CPU-only sessions for now; NNAPI EP via SessionOptions.addNnapi()
 * (present in newer ORT builds) is the upgrade path when we benchmark it.
 */
class OnnxInferenceEngine(
    private val modelProvider: ModelProvider,
    private val environmentFactory: () -> OrtEnvironment = { OrtEnvironment.getEnvironment() },
) : InferenceEngine {

    constructor(context: Context, manifest: ModelManifest) : this(ModelRegistry(context, manifest))

    private val env: OrtEnvironment by lazy(environmentFactory)

    // Tiles are inferred concurrently, so every map the engine touches during a run
    // must tolerate parallel writes. A plain HashMap can corrupt itself here, which
    // would surface as a phantom "model unavailable" rather than a crash.
    private val sessions = java.util.concurrent.ConcurrentHashMap<InferenceEngine.ModelKey, OrtSession>()
    private val sessionBackends = java.util.concurrent.ConcurrentHashMap<InferenceEngine.ModelKey, String>()
    private val backendStatus = java.util.concurrent.ConcurrentHashMap<InferenceEngine.ModelKey, String>()

    @Volatile
    private var activeModelKey: InferenceEngine.ModelKey? = null
    private val sessionLock = Any()
    private var closed = false

    override val backendName: String
        get() = activeModelKey?.let { backendStatus[it] } ?: "Model not loaded"

    private var lastRequestedAccelerator = Accelerator.AUTO
    private var profile = ModelProfile.ULTRA

    /** The registry backing this engine, so callers can share one materialisation. */
    internal val provider: ModelProvider get() = modelProvider

    fun withProfile(profile: ModelProfile): OnnxInferenceEngine {
        this.profile = profile
        return this
    }

    fun withAccelerator(accelerator: Accelerator): OnnxInferenceEngine {
        lastRequestedAccelerator = accelerator
        return this
    }

    companion object {
        private val sharedLock = Any()

        /**
         * Engines are expensive to build and were being thrown away after every job:
         * a fresh registry meant re-hashing the 64 MB asset, and a fresh engine meant
         * re-parsing the graph, so the second photo in a session paid for both again.
         * One engine per profile is kept for the life of the process instead.
         *
         * Keyed by profile rather than reconfigured per job: [withProfile] mutates in
         * place, so a shared instance would let a newly enqueued job change the profile
         * under a still-unwinding previous one.
         */
        private val shared = mutableMapOf<ModelProfile, OnnxInferenceEngine>()

        fun shared(context: Context, profile: ModelProfile): OnnxInferenceEngine =
            synchronized(sharedLock) {
                shared.getOrPut(profile) {
                    OnnxInferenceEngine(
                        ModelRegistry(context.applicationContext, ModelManifest.BUNDLED),
                    ).withProfile(profile)
                }
            }
    }

    override fun isAvailable(accelerator: Accelerator): Boolean = when (accelerator) {
        Accelerator.CPU, Accelerator.AUTO -> true
        // GPU/NPU availability is decided at session creation (probing NNAPI support
        // from the Java API is unreliable); failures fall back to CPU per HW-3.
        else -> false
    }

    override fun upscaleTile(
        input: FloatArray,
        tileWidth: Int,
        tileHeight: Int,
        modelKey: InferenceEngine.ModelKey,
    ): FloatArray {
        if (profile == ModelProfile.FAST) {
            activeModelKey = modelKey
            backendStatus[modelKey] = "Bicubic (FAST; local classical path)"
            return bicubicFallback(input, tileWidth, tileHeight, modelKey)
        }
        val session = sessionFor(modelKey)
            ?: return bicubicFallback(input, tileWidth, tileHeight, modelKey)

        return try {
            val scale = InferenceEngine.scaleFor(modelKey)
            val even = evenTileRequired(modelKey)
            val runWidth = if (even) tileWidth + (tileWidth and 1) else tileWidth
            val runHeight = if (even) tileHeight + (tileHeight and 1) else tileHeight
            val source = if (runWidth == tileWidth && runHeight == tileHeight) {
                input
            } else {
                TensorCodec.padChwToEven(input, tileWidth, tileHeight)
            }
            val shape = longArrayOf(1, 3, runHeight.toLong(), runWidth.toLong())
            val tensor = ai.onnxruntime.OnnxTensor.createTensor(env, FloatBuffer.wrap(source), shape)
            tensor.use {
                session.run(mapOf(session.inputNames.first() to it)).use { results ->
                    @Suppress("UNCHECKED_CAST")
                    val outTensor = results[0].value as Array<Array<Array<FloatArray>>>
                    val outH = outTensor[0][0].size
                    val outW = outTensor[0][0][0].size
                    val flat = FloatArray(3 * outH * outW)
                    for (c in 0 until 3) {
                        for (y in 0 until outH) {
                            System.arraycopy(outTensor[0][c][y], 0, flat, c * outH * outW + y * outW, outW)
                        }
                    }
                    // Only a padded (odd) x2 tile grows the output. Callers size their
                    // read from the requested tile, so drop the padded tail; anything
                    // else keeps the existing "return what the model produced" path.
                    cropPaddedTail(flat, outW, outH, tileWidth, tileHeight, scale)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (t: Throwable) {
            markFallback(modelKey, "${sessionBackends[modelKey] ?: "model"} runtime unavailable${reasonSuffix(t)}")
            bicubicFallback(input, tileWidth, tileHeight, modelKey)
        }
    }

    private fun sessionFor(modelKey: InferenceEngine.ModelKey): OrtSession? {
        sessions[modelKey]?.let {
            activateSession(modelKey)
            return it
        }
        // Concurrent tiles race here: without the lock the first window of tiles would
        // each miss the cache and each build a session for the same model, leaking all
        // but one. The lock is only on the cold path, so the hit above stays lock-free.
        synchronized(sessionLock) {
            sessions[modelKey]?.let {
                activateSession(modelKey)
                return it
            }
            val handle = try {
                modelProvider.load(modelKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (t: Throwable) {
                markFallback(modelKey, "model load failed${reasonSuffix(t)}")
                null
            } ?: run {
                markFallback(modelKey, "model unavailable (${modelKey.assetName})")
                return null
            }

            return try {
                val path = handle.modelPath
                if (path.isNullOrBlank()) {
                    markFallback(modelKey, "invalid model handle (${modelKey.assetName})")
                    null
                } else {
                    val session = createSession(path)
                    sessions[modelKey] = session
                    markSession(modelKey, handle.backendName)
                    session
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (t: Throwable) {
                markFallback(modelKey, "${handle.backendName} session unavailable${reasonSuffix(t)}")
                null
            }
        }
    }

    private fun createSession(path: String): OrtSession {
        val options = OrtSession.SessionOptions()
        var session: OrtSession? = null
        return try {
            options.apply {
                // EnhanceImage runs several tiles at once, and the cores are already
                // spoken for. Leaving ORT at its default (one intra-op thread per core
                // per session.run) would give N tiles x N threads and oversubscribe the
                // CPU, which is slower than either setting alone. One thread per run,
                // parallelism at the tile level instead.
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env.createSession(path, options).also { session = it }
        } catch (e: CancellationException) {
            closeSession(session)
            throw e
        } catch (e: OutOfMemoryError) {
            closeSession(session)
            throw e
        } catch (t: Throwable) {
            closeSession(session)
            throw t
        } finally {
            try {
                options.close()
            } catch (e: CancellationException) {
                closeSession(session)
                throw e
            } catch (e: OutOfMemoryError) {
                closeSession(session)
                throw e
            } catch (_: Throwable) {
            }
        }
    }

    private fun closeSession(session: OrtSession?) {
        if (session == null) return
        try {
            session.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (_: Throwable) {
        }
    }

    /**
     * Names the exception class as well as its message. A per-tile failure degrades
     * that one tile to bicubic and the job continues, so without the class name a
     * genuine bug (IndexOutOfBoundsException, ClassCastException) was indistinguishable
     * from a legitimately unavailable model — the status string is the only place it
     * surfaces.
     */
    private fun reasonSuffix(t: Throwable): String {
        val type = t::class.java.simpleName.ifBlank { "Throwable" }
        val detail = t.message?.takeIf { it.isNotBlank() }?.let { ": ${it.take(80)}" }.orEmpty()
        return " ($type)$detail"
    }

    private fun markSession(modelKey: InferenceEngine.ModelKey, backend: String) {
        sessionBackends[modelKey] = backend
        backendStatus[modelKey] = backend
        activeModelKey = modelKey
    }

    private fun activateSession(modelKey: InferenceEngine.ModelKey) {
        backendStatus[modelKey] = sessionBackends[modelKey] ?: "Model not loaded"
        activeModelKey = modelKey
    }

    private fun markFallback(modelKey: InferenceEngine.ModelKey, reason: String) {
        backendStatus[modelKey] = "Bicubic fallback (${reason.take(240)})"
        activeModelKey = modelKey
    }

    /** Catmull-Rom bicubic upscale so a missing model degrades gracefully (FR-1.3/§5.5). */
    private fun bicubicFallback(
        input: FloatArray,
        w: Int,
        h: Int,
        modelKey: InferenceEngine.ModelKey,
    ): FloatArray = com.rimuru.twobytwo.domain.engine.ImageOps.bicubicUpscaleChw(
        input,
        w,
        h,
        InferenceEngine.scaleFor(modelKey),
    )

    override fun close() {
        if (closed) return
        closed = true
        try {
            sessions.values.forEach(::closeSession)
        } finally {
            sessions.clear()
            try {
                modelProvider.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * True when the bundled graph for [modelKey] cannot run odd tile dimensions and
 * needs [TensorCodec.padChwToEven] first.
 *
 * `realesrgan_compact_x2.onnx` ends in a reshape-based pixel shuffle
 * (3x Reshape + 3x Unsqueeze + 1x Transpose) that requires even H/W;
 * `realesrgan_compact_x4.onnx` has no Reshape/Unsqueeze/Transpose at all and
 * upsamples with 2x Resize, so it handles odd tiles unchanged.
 */
internal fun evenTileRequired(modelKey: InferenceEngine.ModelKey): Boolean =
    modelKey == InferenceEngine.ModelKey.CREATIVE_X2

/**
 * Drop the padded tail from a model output so the engine always hands back
 * exactly `3 * tileHeight * scale * tileWidth * scale`, which is what
 * `TensorCodec.chwToRgba` and the seam blender read. Returns [output] untouched
 * when the model produced no more than the requested tile, so an even tile and
 * an unexpected small output keep their previous behaviour.
 */
internal fun cropPaddedTail(
    output: FloatArray,
    outWidth: Int,
    outHeight: Int,
    tileWidth: Int,
    tileHeight: Int,
    scale: Int,
): FloatArray {
    val wantWidth = tileWidth * scale
    val wantHeight = tileHeight * scale
    return if (outWidth <= wantWidth && outHeight <= wantHeight) {
        output
    } else {
        TensorCodec.cropChw(output, outWidth, outHeight, wantWidth, wantHeight)
    }
}
