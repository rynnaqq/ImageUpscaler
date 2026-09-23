package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.TileBlender
import com.rimuru.twobytwo.domain.engine.TilingManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Seam-blending acceptance proxy (PRD §5.4): with constant-color tiles, output
 * must be identical everywhere (no seams). With a linear ramp input, the blended
 * overlap region must stay within tolerance of the ideal ramp.
 */
class TileBlenderTest {

    @Test
    fun `constant input produces constant output - no seams`() {
        val w = 100
        val h = 64
        val scale = 2
        val tiling = TilingManager(w, h, scale, tileSize = 64, overlap = 16)

        val out = ByteArray(tiling.outWidth * tiling.outHeight * 4)
        for (tile in tiling.tiles()) {
            val tw = tile.inW * scale
            val th = tile.inH * scale
            val tileRgba = ByteArray(tw * th * 4)
            // Fill with the true color at the tile origin — simulates a perfect model
            java.util.Arrays.fill(tileRgba, 0, tileRgba.size, 0x80.toByte())
            TileBlender.blend(tileRgba, tile, tiling, out, tiling.outWidth)
        }

        // Every pixel must be exactly 0x80 in RGB
        for (i in out.indices step 4) {
            assertEquals("seam at pixel $i", 0x80, out[i].toInt() and 0xFF)
        }
    }

    @Test
    fun `linear ramp stays within tolerance across overlaps`() {
        val w = 128
        val h = 32
        val scale = 2
        val tiling = TilingManager(w, h, scale, tileSize = 64, overlap = 16)

        val src = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = (x * 255 / w)
                val p = (y * w + x) * 4
                src[p] = v.toByte(); src[p + 1] = v.toByte(); src[p + 2] = v.toByte(); src[p + 3] = -1
            }
        }

        val out = ByteArray(tiling.outWidth * tiling.outHeight * 4)
        for (tile in tiling.tiles()) {
            val tw = tile.inW * scale
            val th = tile.inH * scale
            val tileRgba = ByteArray(tw * th * 4)
            // "Perfect 2x model": each output pixel = source pixel at x/2 (nearest for test)
            for (y in 0 until th) {
                for (x in 0 until tw) {
                    val sx = (tile.inX + x / scale).coerceIn(0, w - 1)
                    val sy = (tile.inY + y / scale).coerceIn(0, h - 1)
                    val sp = (sy * w + sx) * 4
                    val dp = (y * tw + x) * 4
                    System.arraycopy(src, sp, tileRgba, dp, 4)
                }
            }
            TileBlender.blend(tileRgba, tile, tiling, out, tiling.outWidth)
        }

        // Ideal: out[x] = src[x/2]; blended overlaps deviate at most a few levels
        var maxErr = 0
        for (y in 0 until tiling.outHeight) {
            for (x in 0 until tiling.outWidth) {
                val ideal = (x / 2 * 255 / w)
                val actual = out[(y * tiling.outWidth + x) * 4].toInt() and 0xFF
                maxErr = maxOf(maxErr, kotlin.math.abs(ideal - actual))
            }
        }
        assertEquals("max seam error $maxErr exceeds tolerance", true, maxErr <= 3)
    }
}
