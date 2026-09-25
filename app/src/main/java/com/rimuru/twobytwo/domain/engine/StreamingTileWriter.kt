package com.rimuru.twobytwo.domain.engine

class StreamingTileWriter(
    tiles: List<TilingManager.Tile>,
    private val tiling: TilingManager,
    private val writeRow: (ByteArray) -> Unit,
) {

    private data class RowGroup(
        val tiles: List<TilingManager.Tile>,
        val startY: Int,
        val endY: Int,
    ) {
        val buffers: MutableList<ByteArray> = ArrayList(tiles.size)
    }

    private val groups: List<RowGroup>
    private val activeGroups: MutableList<RowGroup> = ArrayList()
    private val rowSize: Int
    private var nextGroupIndex = 0
    private var nextTileIndex = 0
    private var lastEmittedY = 0
    private var finished = false

    internal val retainedTileCount: Int
        get() = activeGroups.size

    init {
        val grouped = LinkedHashMap<Int, MutableList<TilingManager.Tile>>()
        for (tile in tiles) {
            grouped.getOrPut(tile.row) { ArrayList() }.add(tile)
        }
        groups = grouped.entries.map { (row, rowTiles) ->
            RowGroup(
                tiles = rowTiles,
                startY = rowTiles.minOf { it.inY * tiling.scale },
                endY = rowTiles.maxOf { (it.inY + it.inH) * tiling.scale },
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

    fun accept(tileRgba: ByteArray, tile: TilingManager.Tile) {
        check(!finished) { "streaming tile writer is finished" }
        if (nextGroupIndex >= groups.size) {
            throw IllegalArgumentException("all tiles have already been accepted")
        }
        val group = groups[nextGroupIndex]
        val expected = group.tiles[nextTileIndex]
        if (tile != expected) {
            throw IllegalArgumentException("tile is duplicate, missing, or out of order")
        }
        val expectedSize = Math.multiplyExact(
            Math.multiplyExact(tile.inW * tiling.scale, tile.inH * tiling.scale),
            4,
        )
        require(tileRgba.size == expectedSize) { "tile RGBA size does not match tile" }

        if (nextTileIndex == 0) {
            require(group.startY >= lastEmittedY) { "tile row starts before the last emitted row" }
            emitUntil(group.startY)
            activeGroups.add(group)
        }
        group.buffers.add(tileRgba)
        nextTileIndex += 1
        if (nextTileIndex == group.tiles.size) {
            nextGroupIndex += 1
            nextTileIndex = 0
        }
    }

    fun finish() {
        if (finished) return
        if (nextGroupIndex != groups.size) {
            throw IllegalArgumentException("not all tiles were accepted")
        }
        emitUntil(tiling.outHeight)
        activeGroups.forEach { it.buffers.clear() }
        activeGroups.clear()
        finished = true
    }

    private fun emitUntil(limitY: Int) {
        val end = limitY.coerceAtMost(tiling.outHeight)
        while (lastEmittedY < end) {
            val row = ByteArray(rowSize)
            for (group in activeGroups) {
                if (group.startY <= lastEmittedY && lastEmittedY < group.endY) {
                    for (index in group.tiles.indices) {
                        TileBlender.blendRow(
                            group.buffers[index],
                            group.tiles[index],
                            tiling,
                            row,
                            lastEmittedY,
                        )
                    }
                }
            }
            writeRow(row)
            lastEmittedY += 1
        }
        val iterator = activeGroups.iterator()
        while (iterator.hasNext()) {
            val group = iterator.next()
            if (group.endY <= end) {
                group.buffers.clear()
                iterator.remove()
            }
        }
    }
}
