package com.rimuru.twobytwo.data.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.model.Accelerator
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * ONNX Runtime Mobile engine (PRD TC-2). Models load from assets → filesDir cache
 * with sha256 verification (PRD §5.5). HW-3: any session-creation failure falls
 * back to CPU — no crash mid-job.
 *
 * ponytail: CPU-only sessions for now; NNAPI EP via SessionOptions.addNnapi()
 * (present in newer ORT builds) is the upgrade path when we benchmark it.
 */
class OnnxInferenceEngine(
    private val context: Context,
    private val manifest: ModelManifest,
) : InferenceEngine {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = mutableMapOf<InferenceEngine.ModelKey, OrtSession>()

    override val backendName: String
        get() = _backendName
    private var _backendName: String = "ORT CPU"

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
            ?: return bilinearFallback(input, tileWidth, tileHeight, modelKey)
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
                return flat
            }
        }
    }

    private fun sessionFor(modelKey: InferenceEngine.ModelKey): OrtSession? {
        sessions[modelKey]?.let { return it }
        val entry = manifest.entries[modelKey] ?: return null // model missing → bilinear fallback
        val file = materializeModel(entry) ?: return null
        return try {
            val session = env.createSession(file.absolutePath, OrtSession.SessionOptions())
            sessions[modelKey] = session
            session
        } catch (t: Throwable) {
            null // HW-3: never crash on backend failure
        }
    }

    /** Assets → filesDir cache with sha256 verify (PRD §5.5). Null if missing/corrupt. */
    private fun materializeModel(entry: ModelManifest.Entry): File? {
        val cacheDir = File(context.filesDir, "models").apply { mkdirs() }
        val f = File(cacheDir, entry.fileName)
        if (f.exists() && sha256(f) == entry.sha256) return f

        return try {
            context.assets.open("models/${entry.fileName}").use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
            if (entry.sha256.isEmpty() || sha256(f) == entry.sha256) f else {
                f.delete()
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { stream ->
            val buf = ByteArray(8192)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Deterministic bilinear upscale so a missing model degrades gracefully (FR-1.3/§5.5). */
    private fun bilinearFallback(
        input: FloatArray,
        w: Int,
        h: Int,
        modelKey: InferenceEngine.ModelKey,
    ): FloatArray {
        val scale = InferenceEngine.scaleFor(modelKey)
        val ow = w * scale
        val oh = h * scale
        val out = FloatArray(3 * ow * oh)
        for (c in 0 until 3) {
            for (y in 0 until oh) {
                val sy = y / scale.toFloat()
                val y0 = sy.toInt().coerceIn(0, h - 1)
                val y1 = (y0 + 1).coerceIn(0, h - 1)
                val fy = sy - y0
                for (x in 0 until ow) {
                    val sx = x / scale.toFloat()
                    val x0 = sx.toInt().coerceIn(0, w - 1)
                    val x1 = (x0 + 1).coerceIn(0, w - 1)
                    val fx = sx - x0
                    val p00 = input[c * w * h + y0 * w + x0]
                    val p10 = input[c * w * h + y0 * w + x1]
                    val p01 = input[c * w * h + y1 * w + x0]
                    val p11 = input[c * w * h + y1 * w + x1]
                    out[c * ow * oh + y * ow + x] =
                        p00 * (1 - fx) * (1 - fy) + p10 * fx * (1 - fy) + p01 * (1 - fx) * fy + p11 * fx * fy
                }
            }
        }
        return out
    }

    override fun close() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }
}
