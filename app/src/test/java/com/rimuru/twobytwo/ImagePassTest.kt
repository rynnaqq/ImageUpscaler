package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.ImagePass
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.ModelProvider
import com.rimuru.twobytwo.domain.engine.PassContext
import com.rimuru.twobytwo.domain.engine.PassResult
import com.rimuru.twobytwo.domain.engine.RgbaImage
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.PassRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePassTest {

    private val models = object : ModelProvider {
        override fun load(key: InferenceEngine.ModelKey) = null
    }

    @Test
    fun `disabled pass preserves the input pixel reference`() {
        val pixels = ByteArray(4) { 0x7F }
        val image = RgbaImage(pixels, 1, 1)

        val result = NoOpPass().apply(image, PassContext(0, models))

        assertSame(pixels, result.image.pixels)
        assertFalse(result.usedFallback)
    }

    @Test
    fun `enabled no-op pass reports a fallback`() {
        val pixels = ByteArray(4) { 0x7F }
        val image = RgbaImage(pixels, 1, 1)

        val result = NoOpPass().apply(image, PassContext(50, models))

        assertSame(pixels, result.image.pixels)
        assertTrue(result.usedFallback)
    }

    @Test
    fun `request pass strength is bounded`() {
        val error = runCatching {
            EnhanceRequest(
                inputUris = listOf("content://input/photo"),
                passes = listOf(PassRequest("deblur", 101)),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `request face strength is bounded`() {
        val error = runCatching {
            EnhanceRequest(
                inputUris = listOf("content://input/photo"),
                faceRestoreStrength = 101,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private class NoOpPass : ImagePass {
        override val id = "test"

        override fun apply(image: RgbaImage, context: PassContext): PassResult =
            if (context.strength == 0) {
                PassResult(image, usedFallback = false, detail = "disabled")
            } else {
                PassResult(image, usedFallback = true, detail = "no-op fallback")
            }
    }
}
