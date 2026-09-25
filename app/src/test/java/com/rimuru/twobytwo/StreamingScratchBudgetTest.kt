package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.TilingManager
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingScratchBudgetTest {

    @Test
    fun `scratch budget covers overlapping raw groups and worst case PNG bytes`() {
        val tiling = TilingManager(70, 66, scale = 4, tileSize = 32, overlap = 8)

        val required = EnhanceImage.streamingScratchBytes(
            tiles = tiling.tiles(),
            scale = tiling.scale,
            outputWidth = tiling.outWidth.toLong(),
            outputHeight = tiling.outHeight.toLong(),
        )

        assertTrue(required >= 393_216L + 369_600L)
    }

    @Test
    fun `tall input sweeps tens of thousands of row groups`() {
        val inputHeight = 16_000_000
        val tiling = TilingManager(
            imageWidth = 1,
            imageHeight = inputHeight,
            scale = 4,
            tileSize = 256,
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
        val pngBound = tiling.outWidth.toLong() * tiling.outHeight * 5L
        val overlappingRaw = 2L * tiling.tileSize * tiling.scale * tiling.tileSize * tiling.scale * 4L

        assertTrue(required >= pngBound + overlappingRaw)
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
        val tiling = TilingManager(2, 2, scale = 1, tileSize = 2, overlap = 1)
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
}
