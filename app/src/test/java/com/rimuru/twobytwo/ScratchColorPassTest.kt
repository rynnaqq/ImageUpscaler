package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.engine.ModelManifest
import com.rimuru.twobytwo.data.work.EnhanceRequestJson
import com.rimuru.twobytwo.domain.engine.ColorizePass
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.ModelProvider
import com.rimuru.twobytwo.domain.engine.PassContext
import com.rimuru.twobytwo.domain.engine.RgbaImage
import com.rimuru.twobytwo.domain.engine.ScratchRepairPass
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.DenoiseStrength
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScratchColorPassTest {

    private val missingModels = object : ModelProvider {
        override fun load(key: InferenceEngine.ModelKey) = null
    }

    @Test
    fun `disabled scratch repair and colorization are no-ops`() {
        val image = solidImage(3, 2, 90, 110, 130, 200)

        val scratch = ScratchRepairPass(0).apply(image, PassContext(0, missingModels))
        val color = ColorizePass(0).apply(image, PassContext(0, missingModels))

        assertSame(image.pixels, scratch.image.pixels)
        assertSame(image.pixels, color.image.pixels)
        assertFalse(scratch.usedFallback)
        assertFalse(color.usedFallback)
    }

    @Test
    fun `scratch repair changes only detected defect pixels`() {
        val image = solidImage(5, 5, 80, 90, 100, 180)
        for (y in 0 until image.height) {
            val index = (y * image.width + 2) * 4
            image.pixels[index] = 245.toByte()
            image.pixels[index + 1] = 245.toByte()
            image.pixels[index + 2] = 245.toByte()
        }

        val result = ScratchRepairPass(100).apply(image, PassContext(100, missingModels))

        assertTrue(result.usedFallback)
        assertEquals(image.width, result.image.width)
        assertEquals(image.height, result.image.height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val index = (y * image.width + x) * 4
                if (x != 2) {
                    assertEquals(image.pixels[index], result.image.pixels[index])
                    assertEquals(image.pixels[index + 1], result.image.pixels[index + 1])
                    assertEquals(image.pixels[index + 2], result.image.pixels[index + 2])
                }
                assertEquals(image.pixels[index + 3], result.image.pixels[index + 3])
            }
        }
        assertNotEquals(image.pixels[(2 * image.width + 2) * 4], result.image.pixels[(2 * image.width + 2) * 4])
    }

    @Test
    fun `colorization preserves dimensions alpha and luminance`() {
        val image = RgbaImage(
            byteArrayOf(
                20, 20, 20, 0,
                80, 80, 80, 64,
                160.toByte(), 160.toByte(), 160.toByte(), 160.toByte(),
                220.toByte(), 220.toByte(), 220.toByte(), 255.toByte(),
            ),
            2,
            2,
        )

        val result = ColorizePass(100).apply(image, PassContext(100, missingModels))

        assertTrue(result.usedFallback)
        assertEquals(image.width, result.image.width)
        assertEquals(image.height, result.image.height)
        for (pixel in 0 until 4) {
            val index = pixel * 4
            assertEquals(image.pixels[index + 3], result.image.pixels[index + 3])
            assertTrue(abs(luminance(result.image.pixels, index) - luminance(image.pixels, index)) <= 2)
        }
        assertNotEquals(result.image.pixels[4], result.image.pixels[5])
    }

    @Test
    fun `enabled passes report fallback without a verified model`() {
        val image = solidImage(2, 2, 100, 100, 100, 255)

        val scratch = ScratchRepairPass(50).apply(image, PassContext(50, missingModels))
        val color = ColorizePass(50).apply(image, PassContext(50, missingModels))

        assertTrue(scratch.usedFallback)
        assertTrue(color.usedFallback)
        assertTrue(scratch.detail.contains("fallback"))
        assertTrue(color.detail.contains("fallback"))
        assertTrue(scratch.detail.contains("model execution deferred"))
        assertTrue(color.detail.contains("model execution deferred"))
    }

    @Test
    fun `scratch and colorization stop when cancellation is requested`() {
        var scratchChecks = 0
        val scratchError = runCatching {
            ScratchRepairPass(
                strength = 100,
                isCancelled = {
                    scratchChecks++
                    scratchChecks >= 3
                },
            ).apply(solidImage(32, 32, 80, 80, 80, 255), PassContext(100, missingModels))
        }.exceptionOrNull()

        var colorChecks = 0
        val colorError = runCatching {
            ColorizePass(
                strength = 100,
                isCancelled = {
                    colorChecks++
                    colorChecks >= 3
                },
            ).apply(solidImage(32, 32, 80, 80, 80, 255), PassContext(100, missingModels))
        }.exceptionOrNull()

        assertTrue(scratchError is CancellationException)
        assertTrue(colorError is CancellationException)
        assertTrue(scratchChecks >= 3)
        assertTrue(colorChecks >= 3)
    }

    @Test
    fun `scratch and colorization model keys are scale one placeholders`() {
        assertEquals(1, InferenceEngine.scaleFor(InferenceEngine.ModelKey.SCRATCH_REPAIR))
        assertEquals(1, InferenceEngine.scaleFor(InferenceEngine.ModelKey.COLORIZE))
        assertTrue(ModelManifest.PLACEHOLDER.entries.containsKey(InferenceEngine.ModelKey.SCRATCH_REPAIR))
        assertTrue(ModelManifest.PLACEHOLDER.entries.containsKey(InferenceEngine.ModelKey.COLORIZE))
        assertEquals("", ModelManifest.PLACEHOLDER.entries.getValue(InferenceEngine.ModelKey.SCRATCH_REPAIR).sha256)
        assertEquals("", ModelManifest.PLACEHOLDER.entries.getValue(InferenceEngine.ModelKey.COLORIZE).sha256)
    }

    @Test
    fun `scratch and colorization run after denoise and deblur before upscaling`() = runBlocking {
        val engineInputs = mutableListOf<FloatArray>()
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(2, 2)

            override fun decode(uri: String, maxMegapixels: Int) =
                EnhanceImage.DecodedImage(solidImage(2, 2, 100, 100, 100, 255).pixels, 2, 2)

            override fun encode(
                rgba: ByteArray,
                width: Int,
                height: Int,
                destinationUri: String,
                format: EnhanceImage.OutputFormat,
                exifSourceUri: String?,
            ): String = "content://output/$destinationUri"
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
                engineInputs += input.copyOf()
                val outputWidth = tileWidth * InferenceEngine.scaleFor(modelKey)
                val outputHeight = tileHeight * InferenceEngine.scaleFor(modelKey)
                return FloatArray(3 * outputWidth * outputHeight)
            }
        }
        val progress = EnhanceImage(engine, imageIo).run(
            request = EnhanceRequest(
                inputUris = listOf("content://input/photo"),
                denoise = DenoiseStrength(50),
                deblurEnabled = true,
                scratchRepairEnabled = true,
                colorizeEnabled = true,
                sharpen = false,
            ),
            outputNameFor = { _, _ -> "ordered.png" },
        ).toList()
        val status = progress.mapNotNull { it.backendUsed }
            .first { it.contains("denoise") && it.contains("deblur") && it.contains("scratch-repair") && it.contains("colorize") }
        val input = engineInputs.single()

        assertTrue(status.indexOf("denoise") < status.indexOf("deblur"))
        assertTrue(status.indexOf("deblur") < status.indexOf("scratch-repair"))
        assertTrue(status.indexOf("scratch-repair") < status.indexOf("colorize"))
        assertTrue(input[0] != input[input.size / 3] || input[0] != input[2 * (input.size / 3)])
    }

    @Test
    fun `scratch and colorization request settings are disabled by default and bounded`() {
        val request = EnhanceRequest(inputUris = listOf("content://input/one"))

        assertFalse(request.scratchRepairEnabled)
        assertFalse(request.colorizeEnabled)
        assertEquals(50, request.scratchRepairStrength)
        assertEquals(50, request.colorizeStrength)
        assertTrue(runCatching {
            EnhanceRequest(inputUris = listOf("content://input/one"), scratchRepairStrength = -1)
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            EnhanceRequest(inputUris = listOf("content://input/one"), colorizeStrength = 101)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `scratch and colorization request settings round trip through worker JSON`() {
        val request = EnhanceRequest(
            inputUris = listOf("content://input/one"),
            denoise = DenoiseStrength.OFF,
            deblurEnabled = false,
            scratchRepairEnabled = true,
            scratchRepairStrength = 73,
            colorizeEnabled = true,
            colorizeStrength = 41,
        )

        val decoded = EnhanceRequestJson.decode(EnhanceRequestJson.encode(request))

        assertEquals(request, decoded)
    }

    private fun solidImage(width: Int, height: Int, r: Int, g: Int, b: Int, a: Int): RgbaImage {
        val pixels = ByteArray(width * height * 4)
        var index = 0
        while (index < pixels.size) {
            pixels[index] = r.toByte()
            pixels[index + 1] = g.toByte()
            pixels[index + 2] = b.toByte()
            pixels[index + 3] = a.toByte()
            index += 4
        }
        return RgbaImage(pixels, width, height)
    }

    private fun luminance(pixels: ByteArray, index: Int): Int {
        val r = pixels[index].toInt() and 0xFF
        val g = pixels[index + 1].toInt() and 0xFF
        val b = pixels[index + 2].toInt() and 0xFF
        return (54 * r + 183 * g + 19 * b) / 256
    }
}
