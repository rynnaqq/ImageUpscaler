package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.model.ScaleFactor
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhanceMemoryBudgetTest {

    @Test
    fun `output buffer uses four bytes per pixel within budget`() {
        assertEquals(40_000, EnhanceImage.outputBufferSize(100, 100, 24.0))
    }

    @Test
    fun `twelve megapixel two x output stays within technical boundary`() {
        val sourceWidth = 12_000L
        val sourceHeight = 1_000L
        val scale = ScaleFactor.X2.multiplier
        assertEquals(
            192_000_000,
            EnhanceImage.outputBufferSize(sourceWidth * scale, sourceHeight * scale, Double.MAX_VALUE),
        )
    }

    @Test
    fun `twelve megapixel four x output stays within technical boundary`() {
        val sourceWidth = 12_000L
        val sourceHeight = 1_000L
        val scale = ScaleFactor.X4.multiplier
        assertEquals(
            768_000_000,
            EnhanceImage.outputBufferSize(sourceWidth * scale, sourceHeight * scale, Double.MAX_VALUE),
        )
    }

    @Test
    fun `six megapixel eight x output stays within technical boundary`() {
        val sourceWidth = 6_000L
        val sourceHeight = 1_000L
        val scale = ScaleFactor.X8.multiplier
        assertEquals(
            1_536_000_000,
            EnhanceImage.outputBufferSize(sourceWidth * scale, sourceHeight * scale, Double.MAX_VALUE),
        )
    }

    @Test
    fun `twelve megapixel eight x output exceeds technical boundary`() {
        val sourceWidth = 12_000L
        val sourceHeight = 1_000L
        val scale = ScaleFactor.X8.multiplier
        val error = runCatching {
            EnhanceImage.outputBufferSize(sourceWidth * scale, sourceHeight * scale, Double.MAX_VALUE)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("JVM array limit") == true)
    }

    @Test
    fun `output buffer rejects pixels over explicit test budget`() {
        val error = runCatching {
            EnhanceImage.outputBufferSize(5_000, 5_000, 24.0)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("exceeds") == true)
    }
}
