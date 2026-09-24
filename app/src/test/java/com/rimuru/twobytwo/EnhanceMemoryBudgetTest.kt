package com.rimuru.twobytwo

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
        assertEquals(
            192_000_000,
            EnhanceImage.outputBufferSize(24_000, 2_000, Double.MAX_VALUE),
        )
    }

    @Test
    fun `twelve megapixel four x output stays within technical boundary`() {
        assertEquals(
            768_000_000,
            EnhanceImage.outputBufferSize(48_000, 4_000, Double.MAX_VALUE),
        )
    }

    @Test
    fun `eight x output stays within technical buffer boundary`() {
        assertEquals(
            1_536_000_000,
            EnhanceImage.outputBufferSize(48_000, 8_000, Double.MAX_VALUE),
        )
    }

    @Test
    fun `output buffer rejects pixels over JVM array limit`() {
        val error = runCatching {
            EnhanceImage.outputBufferSize(1, Int.MAX_VALUE.toLong() / 4L + 1L, Double.MAX_VALUE)
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
