package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile window is what turns tile inference parallel, and the number of tiles in
 * flight is multiplied into peak memory: a 512 px tile at x4 needs ~64 MB while it is
 * being inferred. So the window is bounded by the heap as well as the core count, and
 * it must never drop below 1 — that value is the old sequential behaviour.
 */
class TileWindowTest {

    private val megabyte = 1024L * 1024L

    @Test
    fun `a small heap and a big tile fall back to one tile in flight`() {
        assertEquals(1, EnhanceImage.tileWindow(heapBytes = 8 * megabyte, bytesPerTile = 64 * megabyte, cores = 8))
    }

    @Test
    fun `a roomy heap on a many core device uses the cap`() {
        assertEquals(4, EnhanceImage.tileWindow(heapBytes = 1024 * megabyte, bytesPerTile = 16 * megabyte, cores = 16))
    }

    @Test
    fun `the core count still limits the window`() {
        assertEquals(2, EnhanceImage.tileWindow(heapBytes = 1024 * megabyte, bytesPerTile = 1 * megabyte, cores = 3))
    }

    @Test
    fun `a single core device runs one tile at a time`() {
        assertEquals(1, EnhanceImage.tileWindow(heapBytes = 1024 * megabyte, bytesPerTile = 1, cores = 1))
    }

    @Test
    fun `a two core device still gets one spare core for the consumer`() {
        assertEquals(1, EnhanceImage.tileWindow(heapBytes = 1024 * megabyte, bytesPerTile = 1, cores = 2))
    }

    @Test
    fun `the window never exceeds the hard cap however much room there is`() {
        assertEquals(4, EnhanceImage.tileWindow(heapBytes = 64 * 1024 * megabyte, bytesPerTile = 1, cores = 256))
    }

    @Test
    fun `a zero or negative tile size cannot divide by zero`() {
        assertEquals(1, EnhanceImage.tileWindow(heapBytes = 1024 * megabyte, bytesPerTile = 0, cores = 8))
        assertEquals(1, EnhanceImage.tileWindow(heapBytes = 1024 * megabyte, bytesPerTile = -5, cores = 8))
    }

    @Test
    fun `a tile cost is chw floats plus the rgba result`() {
        // 3 float channels (12 bytes) + 4 rgba bytes per output pixel.
        assertEquals(16L, EnhanceImage.tileInferenceBytes(1))
        assertEquals(16L * 2048 * 2048, EnhanceImage.tileInferenceBytes(2048L * 2048))
    }

    @Test
    fun `a 512 tile at x4 is about 64 megabytes in flight`() {
        val outPixels = (512L * 4) * (512L * 4)
        val bytes = EnhanceImage.tileInferenceBytes(outPixels)
        assertTrue("expected ~64 MB, got $bytes", bytes in 60 * megabyte..70 * megabyte)
    }

    @Test
    fun `the heap fraction leaves most of the heap alone`() {
        assertTrue(EnhanceImage.TILE_WINDOW_HEAP_FRACTION in 0.1..0.4)
    }
}
