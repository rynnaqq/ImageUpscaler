package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.engine.ModelManifest
import com.rimuru.twobytwo.data.engine.ModelRegistry
import com.rimuru.twobytwo.data.engine.OnnxInferenceEngine
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.ModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
