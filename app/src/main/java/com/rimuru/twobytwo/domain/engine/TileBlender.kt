package com.rimuru.twobytwo.domain.engine

/**
 * Writes upscaled tile output into the full-output buffer with feathered blending.
 * Pure Kotlin. Works on RGBA byte buffers so the output bitmap can be backed by
 * a single pre-allocated array (TC-3: full-res buffer allocated exactly once).
 */
object TileBlender {

    /**
     * Blend one tile's RGBA output into [outRgba] (full upscaled image, w*outW).
     *
     * For each output pixel in the tile's footprint:
     *   weight = featherWeight(...) from TilingManager
     *   out = out*(1-w) + tile*w   (premultiplied-free: RGBA channels blended independently)
     *
     * Pixels with weight >= 1 are written directly (fast path).
     */
    fun blend(
        tileRgba: ByteArray,
        tile: com.rimuru.twobytwo.domain.engine.TilingManager.Tile,
        tiling: TilingManager,
        outRgba: ByteArray,
        outW: Int,
    ) {
        val scale = tiling.scale
        val tileOutW = tile.inW * scale
        val tileOutH = tile.inH * scale

        for (ty in 0 until tileOutH) {
            val outY = tile.inY * scale + ty
            if (outY < 0 || outY >= tiling.outHeight) continue
            for (tx in 0 until tileOutW) {
                val outX = tile.inX * scale + tx
                if (outX < 0 || outX >= outW) continue

                val w = tiling.featherWeight(outX, outY, tile)
                val src = (ty * tileOutW + tx) * 4
                val dst = (outY * outW + outX) * 4

                if (w >= 0.999f) {
                    outRgba[dst] = tileRgba[src]
                    outRgba[dst + 1] = tileRgba[src + 1]
                    outRgba[dst + 2] = tileRgba[src + 2]
                    outRgba[dst + 3] = tileRgba[src + 3]
                } else {
                    val iw = 1f - w
                    // Blend in int space; rounds correctly and avoids float buffer reads
                    for (c in 0 until 4) {
                        val o = outRgba[dst + c].toInt() and 0xFF
                        val s = tileRgba[src + c].toInt() and 0xFF
                        outRgba[dst + c] = (o * iw + s * w + 0.5f).toInt().coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }

    fun blendRow(
        tileRgba: ByteArray,
        tile: com.rimuru.twobytwo.domain.engine.TilingManager.Tile,
        tiling: TilingManager,
        outRow: ByteArray,
        outY: Int,
    ) {
        val scale = tiling.scale
        val localY = outY - tile.inY * scale
        val tileOutW = tile.inW * scale
        val tileOutH = tile.inH * scale
        if (outY < 0 || outY >= tiling.outHeight || localY < 0 || localY >= tileOutH) return

        val srcRow = localY * tileOutW * 4
        for (tx in 0 until tileOutW) {
            val outX = tile.inX * scale + tx
            if (outX < 0 || outX >= tiling.outWidth) continue

            val w = tiling.featherWeight(outX, outY, tile)
            val src = srcRow + tx * 4
            val dst = outX * 4
            if (w >= 0.999f) {
                outRow[dst] = tileRgba[src]
                outRow[dst + 1] = tileRgba[src + 1]
                outRow[dst + 2] = tileRgba[src + 2]
                outRow[dst + 3] = tileRgba[src + 3]
            } else {
                val iw = 1f - w
                for (c in 0 until 4) {
                    val o = outRow[dst + c].toInt() and 0xFF
                    val s = tileRgba[src + c].toInt() and 0xFF
                    outRow[dst + c] = (o * iw + s * w + 0.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
    }
}
