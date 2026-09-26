package com.rimuru.twobytwo

import androidx.compose.ui.geometry.Offset
import com.rimuru.twobytwo.presentation.ComparisonTransform
import com.rimuru.twobytwo.presentation.clampComparisonPanAxis
import com.rimuru.twobytwo.presentation.clampComparisonSplit
import com.rimuru.twobytwo.presentation.clampComparisonZoom
import org.junit.Assert.assertEquals
import org.junit.Test

class ComparisonTransformTest {

    @Test
    fun `zoom clamps from 25 percent through 400 percent`() {
        assertEquals(0.25f, clampComparisonZoom(0.1f), 0.001f)
        assertEquals(0.25f, clampComparisonZoom(0.25f), 0.001f)
        assertEquals(3.5f, clampComparisonZoom(3.5f), 0.001f)
        assertEquals(4f, clampComparisonZoom(4f), 0.001f)
        assertEquals(4f, clampComparisonZoom(20f), 0.001f)
    }

    @Test
    fun `one gesture updates the single pan consumed by both layers`() {
        val before = ComparisonTransform(
            zoom = 2f,
            pan = Offset(20f, -10f),
            splitFraction = 0.4f,
        )

        val after = before.applyGesture(
            centroid = Offset(100f, 100f),
            panDelta = Offset(15f, -5f),
            gestureZoom = 1f,
            viewport = Offset(200f, 200f),
        )

        assertEquals(2f, after.zoom, 0.001f)
        assertEquals(Offset(35f, -15f), after.pan)
        assertEquals(0.4f, after.splitFraction, 0.001f)
        assertEquals(ComparisonTransform(2f, Offset(20f, -10f), 0.4f), before)
    }

    @Test
    fun `zoom scales the shared pan around the gesture centroid`() {
        val after = ComparisonTransform(zoom = 2f, pan = Offset(20f, 30f)).applyGesture(
            centroid = Offset(100f, 100f),
            panDelta = Offset.Zero,
            gestureZoom = 2f,
            viewport = Offset(200f, 200f),
        )

        assertEquals(4f, after.zoom, 0.001f)
        assertEquals(Offset(-60f, -40f), after.pan)
    }

    @Test
    fun `neutral horizontal drag changes only normalized split`() {
        val after = ComparisonTransform().applyGesture(
            centroid = Offset(100f, 100f),
            panDelta = Offset(50f, 5f),
            gestureZoom = 1f,
            viewport = Offset(200f, 200f),
        )

        assertEquals(0.75f, after.splitFraction, 0.001f)
        assertEquals(1f, after.zoom, 0.001f)
        assertEquals(Offset.Zero, after.pan)
    }

    @Test
    fun `split remains normalized at both boundaries`() {
        assertEquals(0f, clampComparisonSplit(-0.25f), 0.001f)
        assertEquals(0.5f, clampComparisonSplit(0.5f), 0.001f)
        assertEquals(1f, clampComparisonSplit(1.25f), 0.001f)
        assertEquals(0f, ComparisonTransform(splitFraction = 0.25f).moveSplitBy(-100f, 200f).splitFraction, 0.001f)
        assertEquals(1f, ComparisonTransform(splitFraction = 0.75f).moveSplitBy(100f, 200f).splitFraction, 0.001f)
    }

    @Test
    fun `reset restores neutral transform and midpoint split`() {
        val reset = ComparisonTransform(
            zoom = 4f,
            pan = Offset(80f, -60f),
            splitFraction = 0.9f,
        ).reset()

        assertEquals(ComparisonTransform(), reset)
    }

    @Test
    fun `pan is bounded by the zoomed viewport`() {
        assertEquals(0f, clampComparisonPanAxis(500f, 0.25f, 200f), 0.001f)
        assertEquals(0f, clampComparisonPanAxis(500f, 1f, 200f), 0.001f)
        assertEquals(100f, clampComparisonPanAxis(500f, 2f, 200f), 0.001f)
        assertEquals(-100f, clampComparisonPanAxis(-500f, 2f, 200f), 0.001f)
        assertEquals(300f, clampComparisonPanAxis(500f, 4f, 200f), 0.001f)
    }
}
