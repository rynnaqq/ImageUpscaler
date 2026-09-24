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
    fun `default budget accepts twelve megapixel two x output`() {
        assertEquals(
            192_000_000,
            EnhanceImage.outputBufferSize(24_000, 2_000, EnhanceImage.DEFAULT_MAX_OUTPUT_MEGAPIXELS),
        )
    }

    @Test
    fun `output buffer rejects pixels over budget`() {
        val error = runCatching {
            EnhanceImage.outputBufferSize(5_000, 5_000, 24.0)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("exceeds") == true)
    }
}
