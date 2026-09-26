package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.engine.cropPaddedTail
import com.rimuru.twobytwo.data.engine.evenTileRequired
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.TensorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Odd-sized tiles reach the engine whenever an image width/height is odd:
 * `TilingManager` clamps the last tile to the image edge, so `inW`/`inH` are
 * odd for odd images. The bundled CREATIVE_X2 graph cannot run those, so the
 * engine pads to even, runs, and crops back to the requested output size.
 */
class OddTilePaddingTest {

    @Test
    fun `pad replicates the right and bottom edge pixels`() {
        val width = 3
        val height = 3
        val input = chw(width, height) { c, y, x -> (c * 100 + y * 10 + x).toFloat() }

        val padded = TensorCodec.padChwToEven(input, width, height)

        assertEquals(4 * 4 * 3, padded.size)
        for (c in 0 until 3) {
            for (y in 0 until 4) {
                for (x in 0 until 4) {
                    val expectedX = x.coerceAtMost(width - 1)
                    val expectedY = y.coerceAtMost(height - 1)
                    assertEquals(
                        input[c * width * height + expectedY * width + expectedX],
                        padded[c * 16 + y * 4 + x],
                        0f,
                    )
                }
            }
        }
    }

    @Test
    fun `pad only the odd axis and leave an even axis untouched`() {
        val input = chw(4, 3) { c, y, x -> (c * 100 + y * 10 + x).toFloat() }

        val padded = TensorCodec.padChwToEven(input, 4, 3)

        assertEquals(4 * 4 * 3, padded.size)
        for (c in 0 until 3) {
            for (y in 0 until 3) {
                for (x in 0 until 4) {
                    assertEquals(
                        input[c * 12 + y * 4 + x],
                        padded[c * 16 + y * 4 + x],
                        0f,
                    )
                }
            }
        }
    }

    @Test
    fun `pad is a no-op for an even tile`() {
        val input = chw(4, 2) { c, y, x -> (c * 100 + y * 10 + x).toFloat() }

        assertSame(input, TensorCodec.padChwToEven(input, 4, 2))
    }

    @Test
    fun `crop keeps the top-left corner`() {
        val padded = chw(4, 4) { c, y, x -> (c * 100 + y * 10 + x).toFloat() }

        val cropped = TensorCodec.cropChw(padded, 4, 4, 3, 3)

        assertEquals(3 * 3 * 3, cropped.size)
        for (c in 0 until 3) {
            for (y in 0 until 3) {
                for (x in 0 until 3) {
                    assertEquals(
                        padded[c * 16 + y * 4 + x],
                        cropped[c * 9 + y * 3 + x],
                        0f,
                    )
                }
            }
        }
    }

    @Test
    fun `crop is a no-op when the requested size already matches`() {
        val input = chw(4, 4) { c, y, x -> (c * 100 + y * 10 + x).toFloat() }

        assertSame(input, TensorCodec.cropChw(input, 4, 4, 4, 4))
    }

    @Test
    fun `pad then crop returns the original tile unchanged`() {
        val input = chw(5, 3) { c, y, x -> (c * 100 + y * 10 + x).toFloat() }

        val roundTrip = TensorCodec.cropChw(
            TensorCodec.padChwToEven(input, 5, 3),
            6,
            4,
            5,
            3,
        )

        assertTrue("pad/crop round trip must be lossless", input.contentEquals(roundTrip))
    }

    @Test
    fun `an odd tile still yields the requested output size at x2`() {
        val scale = InferenceEngine.scaleFor(InferenceEngine.ModelKey.CREATIVE_X2)
        val tileWidth = 3
        val tileHeight = 5
        val input = chw(tileWidth, tileHeight) { c, y, x -> (c * 100 + y * 10 + x).toFloat() }
        val padded = TensorCodec.padChwToEven(input, tileWidth, tileHeight)
        val paddedWidth = tileWidth + (tileWidth and 1)
        val paddedHeight = tileHeight + (tileHeight and 1)
        val modelOutput = FloatArray(3 * paddedWidth * scale * paddedHeight * scale) { it.toFloat() }

        val output = cropPaddedTail(
            modelOutput,
            paddedWidth * scale,
            paddedHeight * scale,
            tileWidth,
            tileHeight,
            scale,
        )

        assertEquals(3 * tileWidth * scale * tileHeight * scale, output.size)
    }

    @Test
    fun `an odd width with an even height still crops back to the requested size`() {
        val scale = 2
        val tileWidth = 3
        val tileHeight = 4
        val input = chw(tileWidth, tileHeight) { c, y, x -> (c * 100 + y * 10 + x).toFloat() }
        val padded = TensorCodec.padChwToEven(input, tileWidth, tileHeight)
        val paddedWidth = tileWidth + (tileWidth and 1)
        // The even axis stays untouched, so only width grew.
        assertEquals(3 * paddedWidth * tileHeight, padded.size)
        val modelOutput = FloatArray(3 * paddedWidth * scale * tileHeight * scale) { it.toFloat() }

        val output = cropPaddedTail(
            modelOutput,
            paddedWidth * scale,
            tileHeight * scale,
            tileWidth,
            tileHeight,
            scale,
        )

        assertEquals(3 * tileWidth * scale * tileHeight * scale, output.size)
    }

    @Test
    fun `an even tile returns the model output untouched`() {
        val scale = 2
        val modelOutput = FloatArray(3 * 8 * 8) { it.toFloat() }

        val output = cropPaddedTail(modelOutput, 8, 8, 4, 4, scale)

        assertSame(modelOutput, output)
    }

    @Test
    fun `a model output smaller than requested is returned untouched`() {
        val modelOutput = FloatArray(3 * 4 * 4) { it.toFloat() }

        val output = cropPaddedTail(modelOutput, 4, 4, 4, 4, 2)

        assertSame(modelOutput, output)
    }

    @Test
    fun `only the creative x2 model requires even tiles`() {
        assertTrue(evenTileRequired(InferenceEngine.ModelKey.CREATIVE_X2))
        for (key in InferenceEngine.ModelKey.entries - setOf(InferenceEngine.ModelKey.CREATIVE_X2)) {
            assertTrue(
                "$key must keep its current tile handling",
                !evenTileRequired(key),
            )
        }
    }

    private fun chw(width: Int, height: Int, value: (c: Int, y: Int, x: Int) -> Float): FloatArray =
        FloatArray(3 * width * height) { index ->
            val plane = width * height
            val c = index / plane
            val rest = index % plane
            value(c, rest / width, rest % width)
        }
}
