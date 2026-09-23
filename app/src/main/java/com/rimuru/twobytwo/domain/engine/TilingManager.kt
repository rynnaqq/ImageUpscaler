package com.rimuru.twobytwo.domain.engine

/**
 * Tile grid + seam blending math. Pure Kotlin, unit-testable, no Android imports (PRD §5.4).
 *
 * Pipeline contract: an image of W×H is processed in overlapping tiles. Each tile
 * is upscaled by [scale]; only the tile's "core" (non-overlapped) region is written
 * unblended into the output; overlap zones get a cosine-ramp feathered blend so
 * seams are invisible.
 */
class TilingManager(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val scale: Int,
    tileSize: Int = 256,
    overlap: Int = 32,
) {
    init {
        require(imageWidth > 0 && imageHeight > 0) { "image dims must be positive" }
        require(tileSize > overlap * 2) { "tileSize $tileSize too small for overlap $overlap" }
    }

    val outWidth: Int = imageWidth * scale
    val outHeight: Int = imageHeight * scale

    /** Stride between tile origins in input space. */
    val stride: Int = tileSize - overlap

    val tilesX: Int = 1 + ceilDiv(imageWidth - overlap, stride)
    val tilesY: Int = 1 + ceilDiv(imageHeight - overlap, stride)
    val tileCount: Int = tilesX * tilesY

    data class Tile(
        /** Input-space origin and extent, clamped to image bounds. */
        val inX: Int,
        val inY: Int,
        val inW: Int,
        val inH: Int,
        /** Core (unblended) region in output space — this tile owns these pixels. */
        val coreOutX: Int,
        val coreOutY: Int,
        val coreW: Int,
        val coreH: Int,
        /** Where this tile's origin sits in input space relative to the grid. */
        val col: Int,
        val row: Int,
    )

    fun tiles(): List<Tile> {
        val result = ArrayList<Tile>(tileCount)
        for (row in 0 until tilesY) {
            for (col in 0 until tilesX) {
                val inX = (col * stride).coerceAtMost(imageWidth - 1)
                val inY = (row * stride).coerceAtMost(imageHeight - 1)
                val inW = minOf(tileSize, imageWidth - inX)
                val inH = minOf(tileSize, imageHeight - inY)

                // Core region: the part of this tile not overlapped by a neighbor on
                // the left/top, and not extending past the image on right/bottom.
                val coreInX = if (col == 0) 0 else overlap / 2
                val coreInY = if (row == 0) 0 else overlap / 2
                val coreInRight = if (col == tilesX - 1) inW else inW - overlap / 2
                val coreInBottom = if (row == tilesY - 1) inH else inH - overlap / 2

                val coreW = (coreInRight - coreInX).coerceAtLeast(1)
                val coreH = (coreInBottom - coreInY).coerceAtLeast(1)

                result += Tile(
                    inX = inX,
                    inY = inY,
                    inW = inW,
                    inH = inH,
                    coreOutX = (inX + coreInX) * scale,
                    coreOutY = (inY + coreInY) * scale,
                    coreW = coreW * scale,
                    coreH = coreH * scale,
                    col = col,
                    row = row,
                )
            }
        }
        return result
    }

    /**
     * Feather weight for output pixel (outX, outY) within a tile placed at
     * (inX, inY) with dims (inW, inH): 1.0 in the interior, cosine-ramped to ~0
     * across [overlap] px inside each overlapped edge. Edge-of-image sides get 1.0
     * (no neighbor to blend with, PRD §5.4 step 5 anti-halo).
     */
    fun featherWeight(
        outX: Int,
        outY: Int,
        tile: Tile,
    ): Float {
        val localOutX = outX - tile.inX * scale // position within tile output
        val localOutY = outY - tile.inY * scale
        val tileOutW = tile.inW * scale
        val tileOutH = tile.inH * scale
        val ramp = overlap * scale

        val wx = rampX(localOutX, tileOutW, ramp, tile.col == 0, tile.col == tilesX - 1)
        val wy = rampX(localOutY, tileOutH, ramp, tile.row == 0, tile.row == tilesY - 1)
        return wx * wy
    }

    private fun rampX(local: Int, tileOut: Int, ramp: Int, isFirst: Boolean, isLast: Boolean): Float {
        var w = 1f
        if (!isFirst && local < ramp) {
            w *= cosineRamp(local.toFloat() / ramp)
        }
        if (!isLast && local > tileOut - ramp) {
            val d = (tileOut - local).toFloat() / ramp
            w *= cosineRamp(d)
        }
        return w
    }

    private fun cosineRamp(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return 0.5f - 0.5f * kotlin.math.cos(Math.PI * clamped).toFloat()
    }

    companion object {
        fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

        /** Pick tile size by device tier (PRD §6.1). */
        fun tileSizeForTier(totalRamMb: Long, hasVulkan: Boolean): Int =
            if (totalRamMb >= 6_000 && hasVulkan) 512 else 256

        /** Pick overlap; ≥ 32 px scaled to model needs (PRD §5.4 step 2). */
        fun overlapFor(tileSize: Int): Int = if (tileSize >= 512) 32 else 24
    }
}
