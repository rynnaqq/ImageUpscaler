package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.media.PngRowWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Inflater

class PngRowWriterTest {

    @Test
    fun `constructor rejects non-positive dimensions`() {
        val output = ByteArrayOutputStream()

        assertThrows(IllegalArgumentException::class.java) { PngRowWriter(output, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { PngRowWriter(output, -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { PngRowWriter(output, 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { PngRowWriter(output, 1, -1) }
        assertEquals(0, output.size())
    }

    @Test
    fun `finish writes a valid RGBA PNG with checked chunks and rows`() {
        val output = ByteArrayOutputStream()
        val writer = PngRowWriter(output, 2, 2)

        writer.writeRgbaRow(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        writer.writeRgbaRow(byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16))
        writer.finish()

        val chunks = parseChunks(output.toByteArray())
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks.map { it.type })
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 2, 0, 0, 0, 2, 8, 6, 0, 0, 0),
            chunks.first().payload,
        )
        assertArrayEquals(
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 9, 10, 11, 12, 13, 14, 15, 16),
            inflateIdat(chunks),
        )
        writer.close()
    }

    @Test
    fun `compressed data is split into IDAT chunks no larger than 64 KiB`() {
        val output = ByteArrayOutputStream()
        val writer = PngRowWriter(output, 70_000, 1)
        val row = ByteArray(280_000).also { Random(0).nextBytes(it) }

        writer.writeRgbaRow(row)
        writer.finish()

        val idat = parseChunks(output.toByteArray()).filter { it.type == "IDAT" }
        assertTrue(idat.size > 1)
        assertTrue(idat.all { it.payload.size <= 65_536 })
        writer.close()
    }

    @Test
    fun `writeRgbaRow rejects rows that are not exactly width times four bytes`() {
        val writer = PngRowWriter(ByteArrayOutputStream(), 2, 1)

        assertThrows(IllegalArgumentException::class.java) {
            writer.writeRgbaRow(ByteArray(7))
        }
        assertThrows(IllegalArgumentException::class.java) {
            writer.writeRgbaRow(ByteArray(9))
        }
        writer.close()
    }

    @Test
    fun `writeRgbaRow rejects rows after finish`() {
        val writer = PngRowWriter(ByteArrayOutputStream(), 1, 1)
        writer.writeRgbaRow(byteArrayOf(1, 2, 3, 4))
        writer.finish()

        assertThrows(IllegalStateException::class.java) {
            writer.writeRgbaRow(byteArrayOf(5, 6, 7, 8))
        }
        writer.close()
    }

    @Test
    fun `close finishes the PNG without closing the caller stream`() {
        val output = TrackingOutputStream()
        val writer = PngRowWriter(output, 1, 1)
        writer.writeRgbaRow(byteArrayOf(1, 2, 3, 4))

        writer.close()

        assertFalse(output.closed)
        val chunks = parseChunks(output.toByteArray())
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks.map { it.type })
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), inflateIdat(chunks))
    }

    private fun parseChunks(png: ByteArray): List<Chunk> {
        assertArrayEquals(
            byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10),
            png.copyOfRange(0, 8),
        )
        val chunks = mutableListOf<Chunk>()
        var offset = 8
        while (offset < png.size) {
            val length = readInt(png, offset)
            offset += 4
            assertTrue(length >= 0)
            assertTrue(length <= png.size - offset - 12)
            val typeOffset = offset
            val type = String(png, offset, 4, Charsets.US_ASCII)
            offset += 4
            val payload = png.copyOfRange(offset, offset + length)
            offset += length
            val actualCrc = readInt(png, offset)
            offset += 4
            val expectedCrc = CRC32().apply { update(png, typeOffset, length + 4) }.value.toInt()
            assertEquals(expectedCrc, actualCrc)
            chunks += Chunk(type, payload)
        }
        assertEquals(png.size, offset)
        return chunks
    }

    private fun inflateIdat(chunks: List<Chunk>): ByteArray {
        val compressed = ByteArrayOutputStream().apply {
            chunks.filter { it.type == "IDAT" }.forEach { write(it.payload) }
        }.toByteArray()
        val inflater = Inflater()
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        try {
            inflater.setInput(compressed)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    error("Incomplete PNG data")
                }
                result.write(buffer, 0, count)
            }
        } finally {
            inflater.end()
        }
        return result.toByteArray()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff shl 24) or
            (bytes[offset + 1].toInt() and 0xff shl 16) or
            (bytes[offset + 2].toInt() and 0xff shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private data class Chunk(
        val type: String,
        val payload: ByteArray,
    )

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
        }
    }
}
