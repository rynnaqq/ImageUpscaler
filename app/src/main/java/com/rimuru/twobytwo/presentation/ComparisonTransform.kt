package com.rimuru.twobytwo.presentation

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

internal fun clampComparisonZoom(value: Float): Float = value.coerceIn(0.25f, 4f)

internal fun clampComparisonPanAxis(value: Float, zoom: Float, viewportSize: Float): Float {
    val maxOffset = ((zoom - 1f) * viewportSize / 2f).coerceAtLeast(0f)
    return value.coerceIn(-maxOffset, maxOffset)
}

internal fun clampComparisonSplit(value: Float): Float = value.coerceIn(0f, 1f)

internal data class ComparisonTransform(
    val zoom: Float = 1f,
    val pan: Offset = Offset.Zero,
    val splitFraction: Float = 0.5f,
) {
    fun applyGesture(
        centroid: Offset,
        panDelta: Offset,
        gestureZoom: Float,
        viewport: Offset,
    ): ComparisonTransform {
        val nextZoom = clampComparisonZoom(zoom * gestureZoom)
        if (zoom == 1f && nextZoom == 1f && abs(panDelta.x) > abs(panDelta.y)) {
            return moveSplitBy(panDelta.x, viewport.x)
        }
        val ratio = nextZoom / zoom
        val nextPan = Offset(
            centroid.x - (centroid.x - pan.x) * ratio + panDelta.x,
            centroid.y - (centroid.y - pan.y) * ratio + panDelta.y,
        )
        return copy(
            zoom = nextZoom,
            pan = Offset(
                clampComparisonPanAxis(nextPan.x, nextZoom, viewport.x),
                clampComparisonPanAxis(nextPan.y, nextZoom, viewport.y),
            ),
        )
    }

    fun moveSplitBy(deltaX: Float, viewportWidth: Float): ComparisonTransform =
        moveSplitTo(splitFraction + deltaX / viewportWidth)

    fun moveSplitTo(fraction: Float): ComparisonTransform =
        copy(splitFraction = clampComparisonSplit(fraction))

    fun reset(): ComparisonTransform = ComparisonTransform()
}
