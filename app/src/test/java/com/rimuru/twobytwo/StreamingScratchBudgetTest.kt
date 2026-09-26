package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.TilingManager
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingScratchBudgetTest {

    private val pngBytesPerPixel = 5L
    private val codecMarginBytes = 1L * 1024L * 1024L
    private val safetyMarginBytes = 16L * 1024L * 1024L

    @Test
    fun `scratch budget equals overlapping raw groups plus worst case PNG bytes and margins`() {
        val tiling = TilingManager(70, 66, scale = 4, tileSize = 32, overlap = 8)
        val tiles = tiling.tiles()

        val required = EnhanceImage.streamingScratchBytes(
            tiles = tiles,
            scale = tiling.scale,
            outputWidth = tiling.outWidth.toLong(),
            outputHeight = tiling.outHeight.toLong(),
        )

        assertEquals("overlap", 393_216L, expectedMaxOverlapBytes(tiles, tiling.scale))
        assertEquals(
            393_216L + pngBytes(tiling) + codecMarginBytes + safetyMarginBytes,
            required,
        )
    }

    @Test(timeout = 30_000)
    fun `tall input sweeps tens of thousands of row groups`() {
        val inputHeight = 16_000_000
        val tileSize = 256
        val tiling = TilingManager(
            imageWidth = 1,
            imageHeight = inputHeight,
            scale = 4,
            tileSize = tileSize,
            overlap = 24,
        )
        val tiles = tiling.tiles()
        assertTrue(tiling.tilesY >= 60_000)

        val required = EnhanceImage.streamingScratchBytes(
            tiles = tiles,
            scale = tiling.scale,
            outputWidth = tiling.outWidth.toLong(),
            outputHeight = tiling.outHeight.toLong(),
        )

        assertEquals("overlap", 32_768L, expectedMaxOverlapBytes(tiles, tiling.scale))
        assertEquals(
            32_768L + pngBytes(tiling) + codecMarginBytes + safetyMarginBytes,
            required,
        )
    }

    @Test
    fun `scratch budget rejects invalid dimensions`() {
        val tiling = TilingManager(70, 66, scale = 4, tileSize = 32, overlap = 8)
        val tiles = tiling.tiles()

        listOf(0L, -1L).forEach { invalidWidth ->
            assertThrows(IllegalArgumentException::class.java) {
                EnhanceImage.streamingScratchBytes(tiles, tiling.scale, invalidWidth, tiling.outHeight.toLong())
            }
        }
        listOf(0L, -1L).forEach { invalidHeight ->
            assertThrows(IllegalArgumentException::class.java) {
                EnhanceImage.streamingScratchBytes(tiles, tiling.scale, tiling.outWidth.toLong(), invalidHeight)
            }
        }
    }

    @Test
    fun `scratch budget rejects overflowing tile dimensions`() {
        val tiling = TilingManager(70, 66, scale = 4, tileSize = 32, overlap = 8)
        val oversized = tiling.tiles().first().copy(inW = Int.MAX_VALUE, inH = Int.MAX_VALUE)

        assertThrows(ArithmeticException::class.java) {
            EnhanceImage.streamingScratchBytes(
                tiles = listOf(oversized),
                scale = tiling.scale,
                outputWidth = Int.MAX_VALUE.toLong(),
                outputHeight = Long.MAX_VALUE,
            )
        }
    }

    @Test
    fun `scratch budget remains finite above Int dimensions for 4x and 8x`() {
        val tiling = TilingManager(2, 2, scale = 1, tileSize = 4, overlap = 1)
        val tile = tiling.tiles().first().copy(inW = 1, inH = 1)

        listOf(4, 8).forEach { scale ->
            val required = EnhanceImage.streamingScratchBytes(
                tiles = listOf(tile),
                scale = scale,
                outputWidth = Int.MAX_VALUE.toLong() + 1L,
                outputHeight = scale.toLong(),
            )

            assertTrue(required > 0L)
        }
    }

    private fun pngBytes(tiling: TilingManager): Long =
        tiling.outWidth.toLong() * tiling.outHeight * pngBytesPerPixel

    /**
     * Peak raw spool bytes, computed by sweeping start/end events instead of the production
     * interval scan, so the budget is checked against an independent derivation.
     */
    private fun expectedMaxOverlapBytes(tiles: List<TilingManager.Tile>, scale: Int): Long {
        val events = ArrayList<Pair<Int, Long>>(tiles.size * 2)
        tiles.groupBy { it.row }.values.forEach { rowTiles ->
            var startY = Int.MAX_VALUE
            var endY = Int.MIN_VALUE
            var bytes = 0L
            rowTiles.forEach { tile ->
                val tileWidth = tile.inW.toLong() * scale
                val tileHeight = tile.inH.toLong() * scale
                bytes += tileWidth * tileHeight * 4L
                startY = minOf(startY, tile.inY * scale)
                endY = maxOf(endY, (tile.inY + tile.inH) * scale)
            }
            events += startY to bytes
            events += endY to -bytes
        }
        events.sortWith(compareBy({ it.first }, { it.second }))
        var active = 0L
        var peak = 0L
        events.forEach { (_, delta) ->
            active += delta
            peak = maxOf(peak, active)
        }
        return peak
    }
}
