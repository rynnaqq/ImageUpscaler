package com.rimuru.twobytwo.domain.engine

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class StreamingTileWriter(
    tiles: List<TilingManager.Tile>,
    private val tiling: TilingManager,
    private val scratchDirectory: File,
    private val writeRowCallback: ((ByteArray) -> Unit)? = null,
) : Closeable {

    constructor(
        tiles: List<TilingManager.Tile>,
        tiling: TilingManager,
        writeRow: (ByteArray) -> Unit,
    ) : this(tiles, tiling, File(System.getProperty("java.io.tmpdir")), writeRow)

    private data class TileEntry(
        val offset: Long,
        val length: Int,
    )

    private class RowGroup(
        val tiles: List<TilingManager.Tile>,
        val startY: Int,
        val endY: Int,
    ) {
        val entries: MutableList<TileEntry> = ArrayList(tiles.size)
        var file: RandomAccessFile? = null
        var spool: File? = null
        var length: Long = 0
    }

    private val groups: List<RowGroup>
    private val activeGroups: MutableList<RowGroup> = ArrayList()
    private val rowSize: Int
    private var nextGroupIndex = 0
    private var nextTileIndex = 0
    private var nextOutputY = 0
    private var sourceRowScratch = ByteArray(0)
    private var maxActiveGroups = 0
    private var finished = false
    private var closed = false

    internal val activeGroupCount: Int
        get() = activeGroups.size

    internal val maxActiveGroupCount: Int
        get() = maxActiveGroups

    internal val retainedTileBufferCount: Int
        get() = 0

    init {
        require(scratchDirectory.isDirectory) { "scratch directory does not exist" }
        val grouped = LinkedHashMap<Int, MutableList<TilingManager.Tile>>()
        val orderedTiles = tiles.sortedWith(
            compareBy<TilingManager.Tile> { it.row }.thenBy { it.col },
        )
        for (tile in orderedTiles) {
            grouped.getOrPut(tile.row) { ArrayList() }.add(tile)
        }
        groups = grouped.entries.map { (_, rowTiles) ->
            RowGroup(
                tiles = rowTiles,
                startY = rowTiles.minOf { Math.multiplyExact(it.inY, tiling.scale) },
                endY = rowTiles.maxOf { Math.multiplyExact(it.inY + it.inH, tiling.scale) },
            )
        }
        rowSize = Math.multiplyExact(tiling.outWidth, 4)
        require(rowSize > 0) { "output row size must be positive" }
        require(groups.zipWithNext().all { (previous, next) -> previous.startY <= next.startY }) {
            "tile row output starts must be ordered"
        }
        val keys = HashSet<Pair<Int, Int>>()
        require(groups.all { group -> group.tiles.all { keys.add(it.row to it.col) } }) {
            "tile rows must contain unique row-column positions"
        }
    }

    fun isRowReady(outY: Int): Boolean {
        checkOpen()
        require(outY >= 0 && outY < tiling.outHeight) { "output row is out of bounds" }
        if (nextGroupIndex >= groups.size) return true
        return groups[nextGroupIndex].startY > outY
    }

    fun accept(tileRgba: ByteArray, tile: TilingManager.Tile) {
        checkOpen()
        check(!finished) { "streaming tile writer is finished" }
        if (nextGroupIndex >= groups.size) {
            throw IllegalArgumentException("all tiles have already been accepted")
        }
        val group = groups[nextGroupIndex]
        val expected = group.tiles[nextTileIndex]
        if (tile != expected) {
            throw IllegalArgumentException("tile is duplicate, missing, or out of order")
        }
        val tileWidth = Math.multiplyExact(tile.inW, tiling.scale)
        val tileHeight = Math.multiplyExact(tile.inH, tiling.scale)
        val expectedSize = Math.multiplyExact(Math.multiplyExact(tileWidth, tileHeight), 4)
        require(tileRgba.size == expectedSize) { "tile RGBA size does not match tile" }

        if (nextTileIndex == 0) {
            if (writeRowCallback != null) {
                require(group.startY >= nextOutputY) { "tile row starts before the last emitted row" }
                emitUntil(group.startY)
            }
            openGroup(group)
        }

        val offset = group.length
        val file = checkNotNull(group.file)
        file.seek(offset)
        file.write(tileRgba)
        group.entries.add(TileEntry(offset, expectedSize))
        group.length = Math.addExact(group.length, expectedSize.toLong())
        nextTileIndex++
        if (nextTileIndex == group.tiles.size) {
            nextGroupIndex++
            nextTileIndex = 0
        }
    }

    fun writeRow(outRow: ByteArray, outY: Int) {
        checkOpen()
        check(!finished) { "streaming tile writer is finished" }
        require(outY == nextOutputY) { "output rows must be written in order" }
        require(outRow.size == rowSize) { "streaming row buffer size mismatch" }
        if (writeRowCallback == null) {
            check(isRowReady(outY)) { "output row is not ready" }
        }
        writeRowInternal(outRow, outY)
    }

    fun finish() {
        checkOpen()
        if (finished) return
        if (nextGroupIndex != groups.size) {
            throw IllegalArgumentException("not all tiles were accepted")
        }
        if (writeRowCallback == null) {
            check(nextOutputY == tiling.outHeight) { "not all output rows were written" }
        } else {
            emitUntil(tiling.outHeight)
        }
        finished = true
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        for (group in groups) {
            try {
                closeGroup(group)
            } catch (error: Exception) {
                val current = failure
                if (current == null) {
                    failure = error
                } else {
                    current.addSuppressed(error)
                }
            }
        }
        activeGroups.clear()
        sourceRowScratch = ByteArray(0)
        failure?.let { throw it }
    }

    private fun openGroup(group: RowGroup) {
        val spool = File.createTempFile("rimuru2x_tiles_", ".rgba", scratchDirectory)
        val file = try {
            RandomAccessFile(spool, "rw")
        } catch (error: Exception) {
            spool.delete()
            throw error
        }
        group.spool = spool
        group.file = file
        activeGroups.add(group)
        maxActiveGroups = maxOf(maxActiveGroups, activeGroups.size)
    }

    private fun emitUntil(limitY: Int) {
        val end = limitY.coerceAtMost(tiling.outHeight)
        while (nextOutputY < end) {
            val row = ByteArray(rowSize)
            writeRowInternal(row, nextOutputY)
        }
    }

    private fun writeRowInternal(outRow: ByteArray, outY: Int) {
        outRow.fill(0)
        for (group in activeGroups) {
            if (group.startY <= outY && outY < group.endY) {
                for (index in group.tiles.indices) {
                    val tile = group.tiles[index]
                    val entry = group.entries[index]
                    val tileWidth = Math.multiplyExact(tile.inW, tiling.scale)
                    val sourceRowSize = Math.multiplyExact(tileWidth, 4)
                    val localY = outY - Math.multiplyExact(tile.inY, tiling.scale)
                    val sourceOffset = Math.addExact(
                        entry.offset,
                        Math.multiplyExact(localY.toLong(), sourceRowSize.toLong()),
                    )
                    if (sourceRowScratch.size != sourceRowSize) {
                        sourceRowScratch = ByteArray(sourceRowSize)
                    }
                    val file = checkNotNull(group.file)
                    file.seek(sourceOffset)
                    file.readFully(sourceRowScratch)
                    TileBlender.blendRow(sourceRowScratch, tile, tiling, outRow, outY)
                }
            }
        }
        nextOutputY++
        val iterator = activeGroups.iterator()
        while (iterator.hasNext()) {
            val group = iterator.next()
            if (group.endY <= outY + 1) {
                closeGroup(group)
                iterator.remove()
            }
        }
    }

    private fun closeGroup(group: RowGroup) {
        var failure: Exception? = null
        val file = group.file
        group.file = null
        if (file != null) {
            try {
                file.close()
            } catch (error: Exception) {
                failure = error
            }
        }
        val spool = group.spool
        group.spool = null
        if (spool != null && spool.exists() && !spool.delete()) {
            val error = IOException("failed to delete tile spool: ${spool.absolutePath}")
            val current = failure
            if (current == null) {
                failure = error
            } else {
                current.addSuppressed(error)
            }
        }
        group.entries.clear()
        group.length = 0
        failure?.let { throw it }
    }

    private fun checkOpen() {
        check(!closed) { "streaming tile writer is closed" }
    }
}
