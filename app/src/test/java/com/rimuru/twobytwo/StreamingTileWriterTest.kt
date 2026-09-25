package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.StreamingTileWriter
import com.rimuru.twobytwo.domain.engine.TileBlender
import com.rimuru.twobytwo.domain.engine.TilingManager
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTileWriterTest {

    @Test
    fun `4x spooled rows match legacy blending exactly and stay bounded`() {
        assertSpooledRowsMatchLegacyBlending(scale = 4)
    }

    @Test
    fun `8x spooled rows match legacy blending exactly and stay bounded`() {
        assertSpooledRowsMatchLegacyBlending(scale = 8)
    }

    @Test
    fun `close removes spools after a partial accept and is idempotent`() {
        withScratchDirectory { scratch ->
            val tiling = TilingManager(70, 66, scale = 4, tileSize = 32, overlap = 8)
            val tile = tiling.tiles().first()
            val writer = StreamingTileWriter(tiling.tiles(), tiling, scratch)

            writer.accept(deterministicTileBuffer(tile, tiling.scale), tile)
            assertEquals(1, writer.activeGroupCount)
            assertEquals(1, spoolFiles(scratch).size)

            writer.close()
            writer.close()

            assertEquals(0, writer.activeGroupCount)
            assertTrue(spoolFiles(scratch).isEmpty())
        }
    }

    @Test
    fun `close retries failed spool deletion without replacing primary failure`() {
        withScratchDirectory { scratch ->
            val tiling = TilingManager(70, 66, scale = 4, tileSize = 32, overlap = 8)
            val tile = tiling.tiles().first()
            val deletionFailure = IOException("delete failed")
            var deleteAttempts = 0
            val writer = StreamingTileWriter(tiling.tiles(), tiling, scratch) { spool ->
                deleteAttempts++
                if (deleteAttempts == 1) throw deletionFailure
                if (spool.exists() && !spool.delete()) throw IOException("retry delete failed")
            }

            writer.accept(deterministicTileBuffer(tile, tiling.scale), tile)
            val expectedFailure = IllegalStateException("primary failure")
            val thrownFailure = assertThrows(IllegalStateException::class.java) {
                try {
                    throw expectedFailure
                } finally {
                    writer.close()
                }
            }

            assertSame(expectedFailure, thrownFailure)
            assertEquals(1, deleteAttempts)
            assertEquals(1, spoolFiles(scratch).size)

            writer.close()
            writer.close()

            assertEquals(2, deleteAttempts)
            assertEquals(0, writer.activeGroupCount)
            assertTrue(spoolFiles(scratch).isEmpty())
        }
    }

    @Test
    fun `rejects duplicate out of order and missing tiles`() {
        withScratchDirectory { scratch ->
            val tiling = TilingManager(70, 66, scale = 4, tileSize = 32, overlap = 8)
            val tiles = tiling.tiles()
            val buffers = tiles.map { deterministicTileBuffer(it, tiling.scale) }

            val duplicateWriter = StreamingTileWriter(tiles, tiling, scratch)
            try {
                duplicateWriter.accept(buffers.first(), tiles.first())
                assertThrows(IllegalArgumentException::class.java) {
                    duplicateWriter.accept(buffers.first(), tiles.first())
                }
            } finally {
                duplicateWriter.close()
            }

            val outOfOrderWriter = StreamingTileWriter(tiles, tiling, scratch)
            try {
                outOfOrderWriter.accept(buffers.first(), tiles.first())
                val secondRow = tiles.first { it.row == 1 }
                assertThrows(IllegalArgumentException::class.java) {
                    outOfOrderWriter.accept(buffers[tiles.indexOf(secondRow)], secondRow)
                }
            } finally {
                outOfOrderWriter.close()
            }

            val missingWriter = StreamingTileWriter(tiles, tiling, scratch)
            try {
                tiles.dropLast(1).indices.forEach { index ->
                    missingWriter.accept(buffers[index], tiles[index])
                }
                assertThrows(IllegalArgumentException::class.java) {
                    missingWriter.finish()
                }
            } finally {
                missingWriter.close()
            }
        }
    }

    @Test
    fun `write row rejects rows that are not next`() {
        withScratchDirectory { scratch ->
            val tiling = TilingManager(70, 66, scale = 4, tileSize = 32, overlap = 8)
            val writer = StreamingTileWriter(tiling.tiles(), tiling, scratch)
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    writer.writeRow(ByteArray(tiling.outWidth * 4), 1)
                }
            } finally {
                writer.close()
            }
        }
    }

    private fun assertSpooledRowsMatchLegacyBlending(scale: Int) {
        val tiling = TilingManager(70, 66, scale, tileSize = 32, overlap = 8)
        val tiles = tiling.tiles()
        val buffers = tiles.map { deterministicTileBuffer(it, scale) }
        val legacy = ByteArray(tiling.outWidth * tiling.outHeight * 4)
        tiles.indices.forEach { index ->
            TileBlender.blend(buffers[index], tiles[index], tiling, legacy, tiling.outWidth)
        }

        withScratchDirectory { scratch ->
            val writer = StreamingTileWriter(tiles, tiling, scratch)
            try {
                var nextTile = 0
                for (outY in 0 until tiling.outHeight) {
                    while (!writer.isRowReady(outY)) {
                        val tile = tiles[nextTile]
                        writer.accept(buffers[nextTile], tile)
                        nextTile++
                        assertEquals(0, writer.retainedTileBufferCount)
                    }

                    val actual = ByteArray(tiling.outWidth * 4) { 0x5a }
                    writer.writeRow(actual, outY)
                    val expected = legacy.copyOfRange(
                        outY * tiling.outWidth * 4,
                        (outY + 1) * tiling.outWidth * 4,
                    )
                    assertArrayEquals("row $outY", expected, actual)
                    assertTrue(
                        "active groups ${writer.activeGroupCount}",
                        writer.maxActiveGroupCount <= 2,
                    )
                }

                writer.finish()
                assertEquals(0, writer.activeGroupCount)
                assertTrue("max groups ${writer.maxActiveGroupCount}", writer.maxActiveGroupCount <= 2)
                assertEquals(0, writer.retainedTileBufferCount)
            } finally {
                writer.close()
            }

            assertTrue(spoolFiles(scratch).isEmpty())
        }
    }

    private fun deterministicTileBuffer(tile: TilingManager.Tile, scale: Int): ByteArray {
        val width = tile.inW * scale
        val height = tile.inH * scale
        return ByteArray(width * height * 4).also { data ->
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = (y * width + x) * 4
                    val value = tile.inX * 17 + tile.inY * 13 + x * 7 + y * 11 + tile.row * 31 + tile.col * 19
                    data[pixel] = (value and 0xff).toByte()
                    data[pixel + 1] = ((value * 3) and 0xff).toByte()
                    data[pixel + 2] = ((value * 5) and 0xff).toByte()
                    data[pixel + 3] = ((value * 13 + tile.col * 29 + tile.row * 43) and 0xff).toByte()
                }
            }
        }
    }

    private fun withScratchDirectory(block: (File) -> Unit) {
        val scratch = File.createTempFile("rimuru2x-writer-test-", ".tmp")
        try {
            assertTrue(scratch.delete())
            assertTrue(scratch.mkdir())
            block(scratch)
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun spoolFiles(scratch: File): List<File> =
        scratch.listFiles { file -> file.name.startsWith("rimuru2x_tiles_") && file.extension == "rgba" }
            ?.toList()
            .orEmpty()
}
