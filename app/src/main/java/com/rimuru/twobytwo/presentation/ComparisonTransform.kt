package com.rimuru.twobytwo.presentation

internal fun clampComparisonZoom(value: Float): Float = value.coerceIn(1f, 12f)

internal fun clampComparisonPanAxis(value: Float, zoom: Float, viewportSize: Float): Float {
    val maxOffset = ((zoom - 1f) * viewportSize / 2f).coerceAtLeast(0f)
    return value.coerceIn(-maxOffset, maxOffset)
}
