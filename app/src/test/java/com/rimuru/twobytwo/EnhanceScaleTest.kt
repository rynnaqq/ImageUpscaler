package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.work.parseScaleFactor
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.ScaleFactor
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
                format: EnhanceImage.OutputFormat,
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
