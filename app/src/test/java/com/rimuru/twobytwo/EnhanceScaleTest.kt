package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.work.parseScaleFactor
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.ExportPolicy
import com.rimuru.twobytwo.domain.model.ScaleFactor
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import com.rimuru.twobytwo.domain.usecase.StreamingImageIo
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhanceScaleTest {

    @Test
    fun `eight x scale has eight x multiplier`() {
        assertEquals(8, ScaleFactor.X8.multiplier)
    }

    @Test
    fun `worker scale values map to matching factors`() {
        assertEquals(ScaleFactor.X2, parseScaleFactor(2))
        assertEquals(ScaleFactor.X4, parseScaleFactor(4))
        assertEquals(ScaleFactor.X8, parseScaleFactor(8))
    }

    @Test
    fun `output buffer accepts four x dimensions`() {
        assertEquals(
            768_000_000,
            EnhanceImage.outputBufferSize(48_000, 4_000, EnhanceImage.DEFAULT_MAX_OUTPUT_MEGAPIXELS),
        )
    }

    @Test
    fun `four x streams at sixty four megapixels`() {
        assertTrue(EnhanceImage.shouldStreamOutput(ScaleFactor.X4, 8_000L, 8_000L))
    }

    @Test
    fun `eight x streams at sixty four megapixels`() {
        assertTrue(EnhanceImage.shouldStreamOutput(ScaleFactor.X8, 8_000L, 8_000L))
    }

    @Test
    fun `four x below sixty four megapixels stays buffered`() {
        assertFalse(EnhanceImage.shouldStreamOutput(ScaleFactor.X4, 7_999L, 8_000L))
    }

    @Test
    fun `two x stays buffered above sixty four megapixels`() {
        assertFalse(EnhanceImage.shouldStreamOutput(ScaleFactor.X2, 8_000L, 8_000L))
    }

    @Test
    fun `x4 preflight accepts output above the single buffer limit`() = runBlocking {
        val decodeMarker = "stream-preflight-reached-decode"
        val sourceSide = 7_500
        val outputPixels = sourceSide.toLong() * 4L * sourceSide * 4L
        val imageIo = object : EnhanceImage.ImageIo, StreamingImageIo {
            override fun measure(uri: String, maxMegapixels: Int) =
                EnhanceImage.Dimensions(sourceSide, sourceSide)

            override fun decode(uri: String, maxMegapixels: Int): EnhanceImage.DecodedImage =
                error(decodeMarker)

            override fun encode(
                rgba: ByteArray,
                width: Int,
                height: Int,
                destinationUri: String,
                policy: ExportPolicy,
                exifSourceUri: String?,
            ): String = error("legacy encode must not run")

            override suspend fun encodeStreaming(
                width: Int,
                height: Int,
                destinationUri: String,
                policy: ExportPolicy,
                exifSourceUri: String?,
                produceRows: suspend (ByteArray) -> Unit,
            ): String = error("decode must fail before streaming")
        }
        val engine = object : InferenceEngine {
            override val backendName = "test"
            override fun isAvailable(accelerator: Accelerator) = true
            override fun close() = Unit

            override fun upscaleTile(
                input: FloatArray,
                tileWidth: Int,
                tileHeight: Int,
                modelKey: InferenceEngine.ModelKey,
            ): FloatArray = error("decode must fail before inference")
        }

        val progress = EnhanceImage(engine, imageIo).run(
            request = EnhanceRequest(
                inputUris = listOf("content://input/large-preflight"),
                scale = ScaleFactor.X4,
            ),
            outputNameFor = { _, _ -> "large.png" },
        ).toList()

        assertTrue(outputPixels > Int.MAX_VALUE.toLong() / 4L)
        assertEquals(decodeMarker, progress.last().error)
    }

    @Test
    fun `three two x passes produce eight x dimensions`() {
        val inputWidth = 2
        val inputHeight = 2
        val twoXScale = InferenceEngine.scaleFor(InferenceEngine.ModelKey.CREATIVE_X2)

        assertEquals(16, inputWidth * twoXScale * twoXScale * twoXScale)
        assertEquals(16, inputHeight * twoXScale * twoXScale * twoXScale)
    }

    @Test
    fun `eight x request chains two x model and encodes sixteen x sixteen`() = runBlocking {
        val modelCalls = mutableListOf<InferenceEngine.ModelKey>()
        val passDimensions = mutableListOf<EnhanceImage.Dimensions>()
        var encodedDimensions: EnhanceImage.Dimensions? = null
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(2, 2)

            override fun decode(uri: String, maxMegapixels: Int) =
                EnhanceImage.DecodedImage(ByteArray(2 * 2 * 4), 2, 2)

            override fun encode(
                rgba: ByteArray,
                width: Int,
                height: Int,
                destinationUri: String,
                policy: ExportPolicy,
                exifSourceUri: String?,
            ): String {
                encodedDimensions = EnhanceImage.Dimensions(width, height)
                return "content://output/$destinationUri"
            }
        }
        val engine = object : InferenceEngine {
            override val backendName = "test"
            override fun isAvailable(accelerator: Accelerator) = true
            override fun close() = Unit

            override fun upscaleTile(
                input: FloatArray,
                tileWidth: Int,
                tileHeight: Int,
                modelKey: InferenceEngine.ModelKey,
            ): FloatArray {
                modelCalls += modelKey
                passDimensions += EnhanceImage.Dimensions(tileWidth, tileHeight)
                val outputPixels = tileWidth * 2 * tileHeight * 2
                return FloatArray(3 * outputPixels)
            }
        }

        val progress = EnhanceImage(engine, imageIo).run(
            request = EnhanceRequest(inputUris = listOf("content://input/photo"), scale = ScaleFactor.X8),
            outputNameFor = { _, _ -> "output.png" },
        ).toList()

        assertEquals("content://output/output.png", progress.last().outputUri)
        assertEquals(
            "test; denoise=classical denoise fallback (model execution deferred); 1 ok, 0 failed",
            progress.last().backendUsed,
        )
        assertEquals(
            listOf(
                InferenceEngine.ModelKey.CREATIVE_X2,
                InferenceEngine.ModelKey.CREATIVE_X2,
                InferenceEngine.ModelKey.CREATIVE_X2,
            ),
            modelCalls,
        )
        assertEquals(
            listOf(
                EnhanceImage.Dimensions(2, 2),
                EnhanceImage.Dimensions(4, 4),
                EnhanceImage.Dimensions(8, 8),
            ),
            passDimensions,
        )
        assertEquals(EnhanceImage.Dimensions(16, 16), encodedDimensions)
    }
}
