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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EnhanceScaleTest {

    @Test
    fun `eight x scale has eight x multiplier`() {
        assertEquals(8, ScaleFactor.X8.multiplier)
    }

    @Test
    fun `worker scale eight maps to x8`() {
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
    fun `eight x request reports unsupported before image io`() = runBlocking {
        var imageIoCalled = false
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int): EnhanceImage.Dimensions {
                imageIoCalled = true
                return EnhanceImage.Dimensions(2, 2)
            }

            override fun decode(uri: String, maxMegapixels: Int): EnhanceImage.DecodedImage {
                imageIoCalled = true
                return EnhanceImage.DecodedImage(ByteArray(16), 2, 2)
            }

            override fun encode(
                rgba: ByteArray,
                width: Int,
                height: Int,
                destinationUri: String,
                format: EnhanceImage.OutputFormat,
                exifSourceUri: String?,
            ): String {
                imageIoCalled = true
                return destinationUri
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
            ): FloatArray = error("inference should not run")
        }

        val progress = EnhanceImage(engine, imageIo).run(
            request = EnhanceRequest(inputUris = listOf("content://input/photo"), scale = ScaleFactor.X8),
            outputNameFor = { _, _ -> "output.png" },
        ).toList()

        assertFalse(imageIoCalled)
        assertEquals("8x enhancement is not yet supported", progress.last().error)
        assertNull(progress.last().outputUri)
    }
}
