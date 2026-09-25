package com.rimuru.twobytwo.data.media

import java.io.Closeable
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

class PngRowWriter(
    private val output: OutputStream,
    private val width: Int,
    private val height: Int,
) : Closeable {

    private val deflater: Deflater
    private val compressedBuffer: ByteArray
    private var rowsWritten = 0
    private var finished = false
    private var closed = false

    init {
        require(width > 0 && height > 0) { "PNG dimensions must be positive" }
        output.write(PNG_SIGNATURE)
        writeChunk(IHDR_TYPE, ihdrPayload())
        compressedBuffer = ByteArray(MAX_CHUNK_SIZE)
        deflater = Deflater()
    }

    fun writeRgbaRow(row: ByteArray) {
        check(!closed) { "PNG writer is closed" }
        check(!finished) { "PNG writer is finished" }
        require(row.size.toLong() == width.toLong() * 4L) { "RGBA row size does not match width" }
        check(rowsWritten < height) { "PNG row count exceeds height" }

        deflater.setInput(FILTER_NONE)
        drainAvailable()
        deflater.setInput(row)
        drainAvailable()
        rowsWritten += 1
    }

    fun finish() {
        if (finished) return
        check(rowsWritten == height) { "PNG row count does not match height" }
        finished = true
        deflater.finish()
        while (!deflater.finished()) {
            val count = deflater.deflate(compressedBuffer)
            if (count > 0) {
                writeChunk(IDAT_TYPE, compressedBuffer, count)
            }
        }
        writeChunk(IEND_TYPE, EMPTY)
    }

    override fun close() {
        if (closed) return
        try {
            finish()
        } finally {
            deflater.end()
            closed = true
        }
    }

    private fun drainAvailable() {
        while (!deflater.finished() && !deflater.needsInput()) {
            val count = deflater.deflate(compressedBuffer)
            if (count == 0) return
            writeChunk(IDAT_TYPE, compressedBuffer, count)
        }
    }

    private fun ihdrPayload() = byteArrayOf(
        (width ushr 24).toByte(),
        (width ushr 16).toByte(),
        (width ushr 8).toByte(),
        width.toByte(),
        (height ushr 24).toByte(),
        (height ushr 16).toByte(),
        (height ushr 8).toByte(),
        height.toByte(),
        8,
        6,
        0,
        0,
        0,
    )

    private fun writeChunk(type: ByteArray, payload: ByteArray) {
        writeChunk(type, payload, payload.size)
    }

    private fun writeChunk(type: ByteArray, payload: ByteArray, length: Int) {
        writeInt(length)
        output.write(type)
        output.write(payload, 0, length)
        val crc = CRC32().apply {
            update(type, 0, type.size)
            update(payload, 0, length)
        }.value
        writeInt(crc.toInt())
    }

    private fun writeInt(value: Int) {
        output.write(value ushr 24)
        output.write(value ushr 16)
        output.write(value ushr 8)
        output.write(value)
    }

    private companion object {
        const val MAX_CHUNK_SIZE = 64 * 1024
        val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val FILTER_NONE = byteArrayOf(0)
        val IHDR_TYPE = "IHDR".toByteArray(Charsets.US_ASCII)
        val IDAT_TYPE = "IDAT".toByteArray(Charsets.US_ASCII)
        val IEND_TYPE = "IEND".toByteArray(Charsets.US_ASCII)
        val EMPTY = ByteArray(0)
    }
}
