package com.rimuru.twobytwo.domain.engine

/**
 * Tile grid + seam blending math. Pure Kotlin, unit-testable, no Android imports (PRD §5.4).
 *
 * Design invariant: the image is partitioned into "core" regions — one per tile —
 * that exactly tile [0, W) × [0, H) with no gaps or overlaps. Each tile also owns
 * ramp (feather) regions inside its footprint where its contribution is weighted
 * by a cosine ramp; a ramp region always coincides with a neighbor's core, so the
 * neighbor's full-weight write wins and seams vanish.
 */
class TilingManager(
    private val imageWidth: Int,
    private val imageHeight: Int,
    val scale: Int,
    private val tileSize: Int = 256,
    private val overlap: Int = 32,
) {
    init {
        require(imageWidth > 0 && imageHeight > 0) { "image dims must be positive" }
        require(tileSize > overlap * 2) { "tileSize $tileSize too small for overlap $overlap" }
    }

    val outWidth: Int = imageWidth * scale
    val outHeight: Int = imageHeight * scale

    private val halfOverlap: Int = overlap / 2

    /** Stride between tile origins in input space. */
    val stride: Int = tileSize - overlap

    val tilesX: Int = ceilDiv(imageWidth - halfOverlap, stride).coerceAtLeast(1)
    val tilesY: Int = ceilDiv(imageHeight - halfOverlap, stride).coerceAtLeast(1)
    val tileCount: Int = tilesX * tilesY

    data class Tile(
        /** Input-space origin and extent, clamped to image bounds. */
        val inX: Int,
        val inY: Int,
        val inW: Int,
        val inH: Int,
        /** Core (full-weight) region in output space — this tile owns these pixels. */
        val coreOutX: Int,
        val coreOutY: Int,
        val coreW: Int,
        val coreH: Int,
        val col: Int,
        val row: Int,
        /** Feather ramp widths in output pixels; 0 = no ramp on that side. */
        val leftRampOut: Int,
        val rightRampOut: Int,
        val topRampOut: Int,
        val bottomRampOut: Int,
    )

    fun tiles(): List<Tile> {
        val result = ArrayList<Tile>(tileCount)
        for (row in 0 until tilesY) {
            for (col in 0 until tilesX) {
                // Core boundaries in absolute input space — exact partition
                val coreInStartX = if (col == 0) 0 else col * stride + halfOverlap
                val coreInEndX = if (col == tilesX - 1) imageWidth else (col + 1) * stride + halfOverlap
                val coreInStartY = if (row == 0) 0 else row * stride + halfOverlap
                val coreInEndY = if (row == tilesY - 1) imageHeight else (row + 1) * stride + halfOverlap

                // Tile origin: nominal grid position, pulled back so the tile still
                // covers its core when the grid runs past the image edge.
                val inX = minOf(col * stride, imageWidth - tileSize).coerceAtLeast(0)
                val inY = minOf(row * stride, imageHeight - tileSize).coerceAtLeast(0)
                val inW = minOf(tileSize, imageWidth - inX)
                val inH = minOf(tileSize, imageHeight - inY)

                result += Tile(
                    inX = inX,
                    inY = inY,
                    inW = inW,
                    inH = inH,
                    coreOutX = coreInStartX * scale,
                    coreOutY = coreInStartY * scale,
                    coreW = (coreInEndX - coreInStartX) * scale,
                    coreH = (coreInEndY - coreInStartY) * scale,
                    col = col,
                    row = row,
                    leftRampOut = (coreInStartX - inX) * scale,
                    rightRampOut = (inX + inW - coreInEndX) * scale,
                    topRampOut = (coreInStartY - inY) * scale,
                    bottomRampOut = (inY + inH - coreInEndY) * scale,
                )
            }
        }
        return result
    }

    /**
     * Feather weight for output pixel (outX, outY) within [tile]: 1.0 in the core,
     * cosine-ramped to ~0 across each ramp region. Image-edge sides have no ramp
     * (no neighbor to blend with — PRD §5.4 step 5 anti-halo).
     */
    fun featherWeight(
        outX: Int,
        outY: Int,
        tile: Tile,
    ): Float {
        val localX = outX - tile.inX * scale
        val localY = outY - tile.inY * scale
        val tileOutW = tile.inW * scale
        val tileOutH = tile.inH * scale

        var w = 1f
        if (tile.leftRampOut > 0 && localX < tile.leftRampOut) {
            w *= cosineRamp(localX.toFloat() / tile.leftRampOut)
        }
        if (tile.rightRampOut > 0 && localX > tileOutW - tile.rightRampOut) {
            w *= cosineRamp((tileOutW - localX).toFloat() / tile.rightRampOut)
        }
        if (tile.topRampOut > 0 && localY < tile.topRampOut) {
            w *= cosineRamp(localY.toFloat() / tile.topRampOut)
        }
        if (tile.bottomRampOut > 0 && localY > tileOutH - tile.bottomRampOut) {
            w *= cosineRamp((tileOutH - localY).toFloat() / tile.bottomRampOut)
        }
        return w
    }

    private fun cosineRamp(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return 0.5f - 0.5f * kotlin.math.cos(Math.PI * clamped).toFloat()
    }

    companion object {
        fun ceilDiv(a: Int, b: Int): Int = if (a <= 0) 0 else (a + b - 1) / b

        /** Pick tile size by device tier (PRD §6.1). */
        fun tileSizeForTier(totalRamMb: Long, hasVulkan: Boolean): Int =
            if (totalRamMb >= 6_000 && hasVulkan) 512 else 256

        /** Pick overlap; ≥ 32 px scaled to model needs (PRD §5.4 step 2). */
        fun overlapFor(tileSize: Int): Int = if (tileSize >= 512) 32 else 24
    }
}
