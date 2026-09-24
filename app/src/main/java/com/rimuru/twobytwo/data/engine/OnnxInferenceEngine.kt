package com.rimuru.twobytwo.data.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.ModelProvider
import com.rimuru.twobytwo.domain.model.Accelerator
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
    private val sessions = mutableMapOf<InferenceEngine.ModelKey, OrtSession>()
    private val sessionBackends = mutableMapOf<InferenceEngine.ModelKey, String>()
    private val backendStatus = mutableMapOf<InferenceEngine.ModelKey, String>()
    private var activeModelKey: InferenceEngine.ModelKey? = null
    private var closed = false

    override val backendName: String
        get() = activeModelKey?.let { backendStatus[it] } ?: "Model not loaded"

    private var lastRequestedAccelerator = Accelerator.AUTO

    fun withAccelerator(accelerator: Accelerator): OnnxInferenceEngine {
        lastRequestedAccelerator = accelerator
        return this
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
        val session = sessionFor(modelKey)
            ?: return bicubicFallback(input, tileWidth, tileHeight, modelKey)
        return try {
            val shape = longArrayOf(1, 3, tileHeight.toLong(), tileWidth.toLong())
            val tensor = ai.onnxruntime.OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
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
                    flat
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

    private fun createSession(path: String): OrtSession {
        val options = OrtSession.SessionOptions()
        var session: OrtSession? = null
        return try {
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

    private fun reasonSuffix(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() }?.let { ": ${it.take(80)}" } ?: ""

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
