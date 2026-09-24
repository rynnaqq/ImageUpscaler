package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.ModelProvider
import com.rimuru.twobytwo.domain.engine.PassContext
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.PassRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePassTest {

    private val models = object : ModelProvider {
        override fun load(key: InferenceEngine.ModelKey) = null
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

    private fun assertRejects(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
