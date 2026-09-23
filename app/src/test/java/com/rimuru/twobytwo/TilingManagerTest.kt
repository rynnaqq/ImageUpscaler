package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.TilingManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TilingManager contract tests (PRD §5.4 acceptance):
 * - full output coverage: every output pixel is owned by exactly one tile core
 * - no OOM-by-construction: tile count bounded, tile dims ≤ tileSize
 * - feather weights: 1.0 in cores, ramping in overlaps, 1.0 at image borders
 */
class TilingManagerTest {

    @Test
    fun `tile grid covers image exactly`() {
        val tiling = TilingManager(imageWidth = 1080, imageHeight = 1920, scale = 2, tileSize = 256, overlap = 32)
        assertEquals(2160, tiling.outWidth)
        assertEquals(3840, tiling.outHeight)

        // Every output pixel covered by exactly one core
        val coverage = HashMap<Long, Int>()
        for (tile in tiling.tiles()) {
            for (y in tile.coreOutY until tile.coreOutY + tile.coreH) {
                for (x in tile.coreOutX until tile.coreOutX + tile.coreW) {
                    val key = y.toLong() * tiling.outWidth + x
                    coverage[key] = (coverage[key] ?: 0) + 1
                }
            }
        }
        assertEquals(tiling.outWidth.toLong() * tiling.outHeight, coverage.size.toLong())
        assertTrue("overlap in cores detected", coverage.values.all { it == 1 })
    }

    @Test
    fun `tile dims never exceed tileSize and count is bounded`() {
        val tiling = TilingManager(4000, 3000, 4, tileSize = 512, overlap = 32)
        for (t in tiling.tiles()) {
            assertTrue(t.inW <= 512)
            assertTrue(t.inH <= 512)
        }
        // Sanity: 4000x3000 with 480 stride → ~9x7 grid, not thousands of tiles
        assertTrue("tile count explosion: ${tiling.tileCount}", tiling.tileCount < 200)
    }

    @Test
    fun `feather weight is 1 in interior and ramps in overlap`() {
        val tiling = TilingManager(1000, 1000, 2, tileSize = 256, overlap = 32)
        val tiles = tiling.tiles()
        val nonEdge = tiles.firstOrNull { it.col > 0 && it.col < tiling.tilesX - 1 && it.row > 0 && it.row < tiling.tilesY - 1 }

        if (nonEdge != null) {
            val coreCenterX = nonEdge.coreOutX + nonEdge.coreW / 2
            val coreCenterY = nonEdge.coreOutY + nonEdge.coreH / 2
            assertEquals(1f, tiling.featherWeight(coreCenterX, coreCenterY, nonEdge), 0.001f)
        }

        // Image-border sides of the corner tile must be weight 1 — no halo (PRD §5.4 step 5)
        val corner = tiles.first { it.col == 0 && it.row == 0 }
        assertEquals(1f, tiling.featherWeight(0, 0, corner), 0.001f)
        // Left edge is image border (no left ramp); pick a y inside the core rows
        val coreMidY = corner.coreOutY + corner.coreH / 2
        assertEquals(1f, tiling.featherWeight(0, coreMidY, corner), 0.001f)

        // But the tile's bottom edge ramps down (neighbors below) — must be < 1
        val bottomY = corner.inY * 2 + corner.inH * 2 - 1
        assertTrue(
            "bottom edge should ramp, got ${tiling.featherWeight(corner.coreOutX + 10, bottomY, corner)}",
            tiling.featherWeight(corner.coreOutX + 10, bottomY, corner) < 0.1f,
        )
    }

    @Test
    fun `tiny image produces single tile`() {
        val tiling = TilingManager(48, 48, 2, tileSize = 256, overlap = 32)
        assertEquals(1, tiling.tileCount)
        assertEquals(1, tiling.tilesX)
        assertEquals(1, tiling.tilesY)
        val tile = tiling.tiles().single()
        assertEquals(48, tile.inW)
        assertEquals(96, tile.coreW)
    }

    @Test
    fun `tile size selection follows device tier rules`() {
        assertEquals(512, TilingManager.tileSizeForTier(8_000, hasVulkan = true))
        assertEquals(256, TilingManager.tileSizeForTier(8_000, hasVulkan = false))
        assertEquals(256, TilingManager.tileSizeForTier(3_000, hasVulkan = true))
    }
}
