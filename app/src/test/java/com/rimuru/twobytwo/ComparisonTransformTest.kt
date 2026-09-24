package com.rimuru.twobytwo

import com.rimuru.twobytwo.presentation.clampComparisonPanAxis
import com.rimuru.twobytwo.presentation.clampComparisonZoom
import org.junit.Assert.assertEquals
import org.junit.Test

class ComparisonTransformTest {

    @Test
    fun `zoom stays within comparison limits`() {
        assertEquals(1f, clampComparisonZoom(0.25f), 0.001f)
        assertEquals(12f, clampComparisonZoom(20f), 0.001f)
        assertEquals(3.5f, clampComparisonZoom(3.5f), 0.001f)
    }

    @Test
    fun `pan is bounded by zoomed viewport`() {
        assertEquals(100f, clampComparisonPanAxis(500f, 2f, 200f), 0.001f)
        assertEquals(-100f, clampComparisonPanAxis(-500f, 2f, 200f), 0.001f)
        assertEquals(0f, clampComparisonPanAxis(500f, 1f, 200f), 0.001f)
    }
}
