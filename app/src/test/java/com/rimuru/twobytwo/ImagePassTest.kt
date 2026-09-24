package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.ImagePass
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.ModelProvider
import com.rimuru.twobytwo.domain.engine.PassContext
import com.rimuru.twobytwo.domain.engine.PassResult
import com.rimuru.twobytwo.domain.engine.RgbaImage
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.PassRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePassTest {

    private val models = object : ModelProvider {
        override fun load(key: InferenceEngine.ModelKey) = null
    }

    @Test
    fun `disabled image pass preserves the input pixel reference`() {
        val pixels = ByteArray(4) { 0x7F }
        val image = RgbaImage(pixels, 1, 1)

        val result = InterfaceContractImagePassFake().apply(image, PassContext(0, models))

        assertSame(pixels, result.image.pixels)
        assertFalse(result.usedFallback)
    }

    @Test
    fun `enabled contract fake reports fallback without changing pixels`() {
        val pixels = ByteArray(4) { 0x7F }
        val image = RgbaImage(pixels, 1, 1)

        val result = InterfaceContractImagePassFake().apply(image, PassContext(50, models))

        assertSame(pixels, result.image.pixels)
        assertTrue(result.usedFallback)
    }

    @Test
    fun `pass context accepts strength boundaries`() {
        assertEquals(0, PassContext(0, models).strength)
        assertEquals(100, PassContext(100, models).strength)
    }

    @Test
    fun `pass context rejects strength outside bounds`() {
        assertRejects { PassContext(-1, models) }
        assertRejects { PassContext(101, models) }
    }

    @Test
    fun `pass request accepts strength boundaries`() {
        assertEquals(0, PassRequest("test", 0).strength)
        assertEquals(100, PassRequest("test", 100).strength)
    }

    @Test
    fun `pass request rejects strength outside bounds`() {
        assertRejects { PassRequest("test", -1) }
        assertRejects { PassRequest("test", 101) }
    }

    @Test
    fun `enhance request validates face strength bounds`() {
        val inputUris = listOf("content://input/photo")
        assertEquals(0, EnhanceRequest(inputUris, faceRestoreStrength = 0).faceRestoreStrength)
        assertEquals(100, EnhanceRequest(inputUris, faceRestoreStrength = 100).faceRestoreStrength)
        assertRejects { EnhanceRequest(inputUris, faceRestoreStrength = -1) }
        assertRejects { EnhanceRequest(inputUris, faceRestoreStrength = 101) }
    }

    @Test
    fun `pass context exposes its model provider as the domain interface`() {
        val provider: ModelProvider = models
        val context: PassContext = PassContext(50, provider)
        val exposed: ModelProvider = context.models

        assertSame(provider, exposed)
    }

    private class InterfaceContractImagePassFake : ImagePass {
        override val id = "interface-contract-fake"

        override fun apply(image: RgbaImage, context: PassContext): PassResult =
            PassResult(
                image = image,
                usedFallback = context.strength > 0,
                detail = if (context.strength == 0) "disabled" else "test fallback",
            )
    }

    private fun assertRejects(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
