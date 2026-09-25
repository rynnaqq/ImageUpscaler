package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.engine.ModelManifest
import com.rimuru.twobytwo.data.engine.ModelRegistry
import com.rimuru.twobytwo.data.engine.OnnxInferenceEngine
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.ModelProvider
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.modelProfile
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelRegistryTest {

    @Test
    fun `missing asset returns null`() {
        val key = InferenceEngine.ModelKey.CREATIVE_X2
        val cache = Files.createTempDirectory("model-registry-missing").toFile()
        val registry = ModelRegistry(
            manifest = ModelManifest(
                mapOf(key to ModelManifest.Entry("model.onnx", "test", "checksum")),
            ),
            cacheDirectory = cache,
            assetSource = { null },
        )

        assertNull(registry.load(key))
    }

    @Test
    fun `empty checksum rejects cached model without reading asset`() {
        val key = InferenceEngine.ModelKey.CREATIVE_X2
        val cache = Files.createTempDirectory("model-registry-empty-checksum").toFile()
        File(cache, "model.onnx").writeText("cached")
        var assetRequests = 0
        val registry = ModelRegistry(
            manifest = ModelManifest(
                mapOf(key to ModelManifest.Entry("model.onnx", "test", "")),
            ),
            cacheDirectory = cache,
            assetSource = {
                assetRequests++
                null
            },
        )

        assertNull(registry.load(key))
        assertEquals(0, assetRequests)
    }

    @Test
    fun `checksum mismatch deletes cached file`() {
        val key = InferenceEngine.ModelKey.CREATIVE_X2
        val cache = Files.createTempDirectory("model-registry-checksum").toFile()
        val cached = File(cache, "model.onnx").apply { writeText("corrupt") }
        val registry = ModelRegistry(
            manifest = ModelManifest(
                mapOf(key to ModelManifest.Entry("model.onnx", "test", "expected-checksum")),
            ),
            cacheDirectory = cache,
            assetSource = { null },
        )

        assertNull(registry.load(key))
        assertFalse(cached.exists())
    }

    @Test
    fun `missing model does not request ONNX environment`() {
        var environmentRequests = 0
        val provider = object : ModelProvider {
            override fun load(key: InferenceEngine.ModelKey) = null
        }
        val engine = OnnxInferenceEngine(
            modelProvider = provider,
            environmentFactory = {
                environmentRequests++
                error("ONNX environment must stay lazy")
            },
        )

        engine.upscaleTile(
            FloatArray(3),
            1,
            1,
            InferenceEngine.ModelKey.CREATIVE_X2,
        )

        assertEquals(0, environmentRequests)
        assertTrue(engine.backendName.contains("fallback"))
    }

    @Test
    fun `fast request bypasses model loading and uses the classical path`() {
        var providerLoads = 0
        var environmentRequests = 0
        val provider = object : ModelProvider {
            override fun load(key: InferenceEngine.ModelKey): com.rimuru.twobytwo.domain.engine.ModelHandle? {
                providerLoads++
                return null
            }
        }
        val request = EnhanceRequest(
            inputUris = listOf("content://input/fast"),
            useNeuralEngine = false,
            accelerator = Accelerator.GPU,
        )
        val engine = OnnxInferenceEngine(
            modelProvider = provider,
            environmentFactory = {
                environmentRequests++
                error("ONNX environment must stay lazy")
            },
        )
            .withProfile(request.modelProfile)
            .withAccelerator(request.accelerator)

        val output = engine.upscaleTile(
            FloatArray(3),
            1,
            1,
            InferenceEngine.ModelKey.CREATIVE_X2,
        )

        assertEquals(0, providerLoads)
        assertEquals(0, environmentRequests)
        assertEquals(3 * 2 * 2, output.size)
        assertTrue(engine.backendName.contains("FAST"))
        assertFalse(engine.backendName.contains("GPU"))
    }

    @Test
    fun `provider cancellation escapes engine fallback`() {
        val expected = CancellationException("provider cancelled")
        val provider = object : ModelProvider {
            override fun load(key: InferenceEngine.ModelKey): com.rimuru.twobytwo.domain.engine.ModelHandle? {
                throw expected
            }
        }
        val engine = OnnxInferenceEngine(
            modelProvider = provider,
            environmentFactory = { error("ONNX environment must stay lazy") },
        )

        val actual = runCatching {
            engine.upscaleTile(FloatArray(3), 1, 1, InferenceEngine.ModelKey.CREATIVE_X2)
        }.exceptionOrNull()

        assertSame(expected, actual)
        assertEquals("Model not loaded", engine.backendName)
    }

    @Test
    fun `materializer cancellation escapes registry fallback`() {
        val expected = CancellationException("materializer cancelled")
        val key = InferenceEngine.ModelKey.CREATIVE_X2
        val registry = ModelRegistry(
            manifest = ModelManifest(mapOf(key to ModelManifest.Entry("model.onnx", "test", "checksum"))),
            cacheDirectory = Files.createTempDirectory("model-registry-cancel").toFile(),
            assetSource = { throw expected },
        )

        val actual = runCatching { registry.load(key) }.exceptionOrNull()

        assertSame(expected, actual)
    }

    @Test
    fun `repeated fallback status is bounded and not recursive`() {
        val provider = object : ModelProvider {
            override fun load(key: InferenceEngine.ModelKey) = null
        }
        val engine = OnnxInferenceEngine(modelProvider = provider)

        repeat(3) {
            engine.upscaleTile(FloatArray(3), 1, 1, InferenceEngine.ModelKey.CREATIVE_X2)
        }

        val status = engine.backendName
        assertEquals("Bicubic fallback (model unavailable (realesrgan_compact_x2.onnx))", status)
        assertEquals(1, "Bicubic fallback".toRegex().findAll(status).count())
    }

    @Test
    fun `engine closes provider once and propagates cancellation`() {
        val expected = CancellationException("provider close cancelled")
        var closeCalls = 0
        val provider = object : ModelProvider {
            override fun load(key: InferenceEngine.ModelKey) = null
            override fun close() {
                closeCalls++
                throw expected
            }
        }
        val engine = OnnxInferenceEngine(modelProvider = provider)

        val actual = runCatching { engine.close() }.exceptionOrNull()
        engine.close()

        assertSame(expected, actual)
        assertEquals(1, closeCalls)
    }
}
