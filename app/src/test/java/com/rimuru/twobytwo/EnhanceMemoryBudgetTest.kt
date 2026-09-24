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
    fun `output buffer rejects pixels over budget`() {
        val error = runCatching {
            EnhanceImage.outputBufferSize(5_000, 5_000, 24.0)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("exceeds") == true)
    }
}
