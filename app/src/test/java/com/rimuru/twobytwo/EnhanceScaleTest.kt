package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.model.ScaleFactor
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import org.junit.Assert.assertEquals
import org.junit.Test

class EnhanceScaleTest {

    @Test
    fun `eight x scale has eight x multiplier`() {
        assertEquals(8, ScaleFactor.X8.multiplier)
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
}
