package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.StreamingTileWriter
import com.rimuru.twobytwo.domain.engine.TileBlender
import com.rimuru.twobytwo.domain.engine.TilingManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTileWriterTest {

    @Test
    fun `streamed rows match legacy blending exactly`() {
        val tiling = TilingManager(300, 200, scale = 2, tileSize = 64, overlap = 8)
        val tiles = tiling.tiles()
        val buffers = tiles.map { deterministicTileBuffer(it, tiling.scale) }
        val legacy = ByteArray(tiling.outWidth * tiling.outHeight * 4)
        tiles.indices.forEach { index ->
            TileBlender.blend(buffers[index], tiles[index], tiling, legacy, tiling.outWidth)
        }

        val rows = mutableListOf<ByteArray>()
        val writer = StreamingTileWriter(tiles, tiling) { rows += it.copyOf() }
        tiles.indices.forEach { index -> writer.accept(buffers[index], tiles[index]) }
        writer.finish()

        assertEquals(tiling.outHeight, rows.size)
        assertTrue(rows.all { it.size == tiling.outWidth * 4 })
        rows.indices.forEach { y ->
            val expected = legacy.copyOfRange(
                y * tiling.outWidth * 4,
                (y + 1) * tiling.outWidth * 4,
            )
            assertArrayEquals("row $y", expected, rows[y])
        }
        assertEquals(0, writer.retainedTileCount)
    }

    @Test
    fun `retains only active row groups`() {
        val tiling = TilingManager(300, 200, scale = 2, tileSize = 64, overlap = 8)
        val tiles = tiling.tiles()
        val groups = tiles.groupBy { it.row }.values
        val writer = StreamingTileWriter(tiles, tiling) {}
        var maxRetained = 0

        groups.forEach { group ->
            group.forEach { tile ->
                writer.accept(deterministicTileBuffer(tile, tiling.scale), tile)
                maxRetained = maxOf(maxRetained, writer.retainedTileCount)
            }
        }
        writer.finish()

        assertTrue("retained $maxRetained groups", maxRetained <= 2)
        assertEquals(0, writer.retainedTileCount)
    }

    @Test
    fun `rejects duplicate and out of order tiles`() {
        val tiling = TilingManager(300, 200, scale = 2, tileSize = 64, overlap = 8)
        val tiles = tiling.tiles()
        val buffers = tiles.map { deterministicTileBuffer(it, tiling.scale) }
        val first = tiles.first()
        val duplicateWriter = StreamingTileWriter(tiles, tiling) {}

        duplicateWriter.accept(buffers.first(), first)
        assertThrows(IllegalArgumentException::class.java) {
            duplicateWriter.accept(buffers.first(), first)
        }

        val outOfOrderWriter = StreamingTileWriter(tiles, tiling) {}
        outOfOrderWriter.accept(buffers.first(), first)
        val secondRow = tiles.first { it.row == 1 }
        assertThrows(IllegalArgumentException::class.java) {
            outOfOrderWriter.accept(buffers[tiles.indexOf(secondRow)], secondRow)
        }
    }

    @Test
    fun `finish rejects missing tiles and emits rows only once`() {
        val tiling = TilingManager(300, 200, scale = 2, tileSize = 64, overlap = 8)
        val tiles = tiling.tiles()
        val buffers = tiles.map { deterministicTileBuffer(it, tiling.scale) }
        val missingWriter = StreamingTileWriter(tiles, tiling) {}

        tiles.dropLast(1).indices.forEach { index ->
            missingWriter.accept(buffers[index], tiles[index])
        }
        assertThrows(IllegalArgumentException::class.java) { missingWriter.finish() }

        val rows = mutableListOf<ByteArray>()
        val writer = StreamingTileWriter(tiles, tiling) { rows += it.copyOf() }
        tiles.indices.forEach { index -> writer.accept(buffers[index], tiles[index]) }
        writer.finish()
        val count = rows.size
        writer.finish()

        assertEquals(tiling.outHeight, count)
        assertEquals(count, rows.size)
    }

    private fun deterministicTileBuffer(tile: TilingManager.Tile, scale: Int): ByteArray {
        val width = tile.inW * scale
        val height = tile.inH * scale
        return ByteArray(width * height * 4).also { data ->
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val p = (y * width + x) * 4
                    val value = (tile.inX * 17 + tile.inY * 13 + x * 7 + y * 11 + tile.row * 31 + tile.col * 19)
                    data[p] = (value and 0xff).toByte()
                    data[p + 1] = ((value * 3) and 0xff).toByte()
                    data[p + 2] = ((value * 5) and 0xff).toByte()
                    data[p + 3] = 0xff.toByte()
                }
            }
        }
    }
}
