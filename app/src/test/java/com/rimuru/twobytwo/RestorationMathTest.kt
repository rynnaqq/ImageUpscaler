package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.DeblurPass
import com.rimuru.twobytwo.domain.engine.DenoisePass
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.ModelProvider
import com.rimuru.twobytwo.domain.engine.PassContext
import com.rimuru.twobytwo.domain.engine.RgbaImage
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.DenoiseStrength
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorationMathTest {

    private val models = object : ModelProvider {
        override fun load(key: InferenceEngine.ModelKey) = null
    }

    @Test
    fun `denoise strength zero preserves the input reference and pixels`() {
        val pixels = ByteArray(4 * 4 * 4) { index ->
            if (index % 4 == 3) 0xFF.toByte() else 0x40
        }
        val image = RgbaImage(pixels, 4, 4)

        val result = DenoisePass(0).apply(image, PassContext(0, models))

        assertSame(pixels, result.image.pixels)
        assertEquals(image.width, result.image.width)
        assertEquals(image.height, result.image.height)
        assertTrue(pixels.contentEquals(ByteArray(pixels.size) {
            if (it % 4 == 3) 0xFF.toByte() else 0x40
        }))
        assertFalse(result.usedFallback)
    }

    @Test
    fun `denoise strength reduces checkerboard variance`() {
        val image = checkerboard(8, 8)

        val result = DenoisePass(100).apply(image, PassContext(100, models))

        assertTrue(variance(result.image.pixels, 8, 8) < variance(image.pixels, 8, 8))
        assertTrue(result.usedFallback)
    }

    @Test
    fun `deblur preserves dimensions and alpha`() {
        val image = checkerboard(6, 4)

        val result = DeblurPass(50).apply(image, PassContext(50, models))

        assertEquals(image.width, result.image.width)
        assertEquals(image.height, result.image.height)
        assertTrue(result.image.pixels.indices.filter { it % 4 == 3 }
            .all { result.image.pixels[it] == image.pixels[it] })
        assertTrue(result.usedFallback)
    }

    @Test
    fun `deblur request defaults disabled and validates strength`() {
        val inputUris = listOf("content://input/photo")
        val default = EnhanceRequest(inputUris)

        assertFalse(default.deblurEnabled)
        assertEquals(50, default.deblurStrength)
        assertEquals(0, EnhanceRequest(inputUris, deblurEnabled = true, deblurStrength = 0).deblurStrength)
        assertEquals(100, EnhanceRequest(inputUris, deblurEnabled = true, deblurStrength = 100).deblurStrength)
        assertRejects { EnhanceRequest(inputUris, deblurEnabled = true, deblurStrength = -1) }
        assertRejects { EnhanceRequest(inputUris, deblurEnabled = true, deblurStrength = 101) }
    }

    @Test
    fun `enabled passes report ids and fallback state while disabled pass stays out of summary`() = runBlocking {
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(2, 2)

            override fun decode(uri: String, maxMegapixels: Int) =
                EnhanceImage.DecodedImage(checkerboard(2, 2).pixels, 2, 2)

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
                val outputWidth = tileWidth * InferenceEngine.scaleFor(modelKey)
                val outputHeight = tileHeight * InferenceEngine.scaleFor(modelKey)
                return FloatArray(3 * outputWidth * outputHeight)
            }
        }

        val enabledProgress = EnhanceImage(engine, imageIo).run(
            request = EnhanceRequest(
                inputUris = listOf("content://input/photo"),
                denoise = DenoiseStrength(50),
                deblurEnabled = true,
                deblurStrength = 50,
            ),
            outputNameFor = { _, _ -> "enabled.png" },
        ).toList()
        val defaultProgress = EnhanceImage(engine, imageIo).run(
            request = EnhanceRequest(inputUris = listOf("content://input/photo")),
            outputNameFor = { _, _ -> "default.png" },
        ).toList()
        val disabledProgress = EnhanceImage(engine, imageIo).run(
            request = EnhanceRequest(
                inputUris = listOf("content://input/photo"),
                denoise = DenoiseStrength.OFF,
                deblurEnabled = false,
            ),
            outputNameFor = { _, _ -> "disabled.png" },
        ).toList()
        val enabled = enabledProgress.last()
        val default = defaultProgress.last()
        val disabled = disabledProgress.last()

        assertTrue(enabledProgress.any { it.backendUsed?.contains("denoise") == true })
        assertTrue(enabledProgress.any { it.backendUsed?.contains("deblur") == true })
        assertTrue(enabled.backendUsed!!.contains("denoise"))
        assertTrue(enabled.backendUsed!!.contains("deblur"))
        assertTrue(enabled.backendUsed!!.contains("fallback"))
        assertTrue(defaultProgress.any { it.backendUsed?.contains("denoise") == true })
        assertEquals("test; 1 ok, 0 failed", default.backendUsed)
        assertEquals("test; 1 ok, 0 failed", disabled.backendUsed)
    }

    private fun checkerboard(width: Int, height: Int): RgbaImage {
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = (y * width + x) * 4
                val value = if ((x + y) % 2 == 0) 0xFF.toByte() else 0
                pixels[index] = value
                pixels[index + 1] = value
                pixels[index + 2] = value
                pixels[index + 3] = 0xFF.toByte()
            }
        }
        return RgbaImage(pixels, width, height)
    }

    private fun variance(pixels: ByteArray, width: Int, height: Int): Double {
        var total = 0.0
        var count = 0
        for (index in 0 until (width * height)) {
            val value = (pixels[index * 4].toInt() and 0xFF).toDouble()
            total += value
            count++
        }
        val mean = total / count
        var sum = 0.0
        for (index in 0 until count) {
            val delta = (pixels[index * 4].toInt() and 0xFF) - mean
            sum += delta * delta
        }
        return sum / count
    }

    private fun assertRejects(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
}
