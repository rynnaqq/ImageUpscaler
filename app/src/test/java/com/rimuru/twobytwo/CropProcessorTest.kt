package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.RgbaImage
import com.rimuru.twobytwo.domain.model.CropPreset
import com.rimuru.twobytwo.domain.model.CropRect
import com.rimuru.twobytwo.domain.usecase.CropProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CropProcessorTest {

    @Test
    fun `center crop returns the largest centered rectangle for every preset`() {
        val cases = listOf(
            Case(CropPreset.SQUARE, 100, 60, CropRect(20, 0, 60, 60)),
            Case(CropPreset.PORTRAIT_9_16, 120, 100, CropRect(32, 0, 56, 100)),
            Case(CropPreset.PORTRAIT_4_5, 100, 120, CropRect(2, 0, 96, 120)),
            Case(CropPreset.PRINT_4_6, 120, 100, CropRect(27, 0, 66, 100)),
            Case(CropPreset.PRINT_8_10, 100, 120, CropRect(2, 0, 96, 120)),
        )

        cases.forEach { case ->
            val result = CropProcessor.centerCrop(source(case.width, case.height), case.preset)

            assertEquals(case.expected, CropProcessor.centerRect(source(case.width, case.height), case.preset))
            assertEquals(case.expected.width, result.width)
            assertEquals(case.expected.height, result.height)
        }
    }

    @Test
    fun `odd dimensions keep the centered crop inside both axes`() {
        val horizontal = CropProcessor.centerRect(source(101, 97), CropPreset.SQUARE)
        val vertical = CropProcessor.centerRect(source(97, 101), CropPreset.SQUARE)

        assertEquals(CropRect(2, 0, 97, 97), horizontal)
        assertEquals(CropRect(0, 2, 97, 97), vertical)
    }

    @Test
    fun `crop copies pixels and alpha into a new buffer without changing the source`() {
        val image = RgbaImage(
            ByteArray(5 * 3 * 4) { index ->
                when (index % 4) {
                    0 -> (index / 4 % 5 * 10).toByte()
                    1 -> (index / 4 / 5 * 10).toByte()
                    2 -> 0x40
                    else -> (index / 4 + 1).toByte()
                }
            },
            5,
            3,
        )
        val original = image.pixels.copyOf()

        val result = CropProcessor.centerCrop(image, CropPreset.SQUARE)

        assertNotSame(image.pixels, result.pixels)
        assertTrue(image.pixels.contentEquals(original))
        for (y in 0 until result.height) {
            for (x in 0 until result.width) {
                val sourceIndex = ((y + 1) * 5 + (x + 1)) * 4
                val resultIndex = (y * result.width + x) * 4
                for (channel in 0..3) {
                    assertEquals(
                        "channel=$channel at x=$x y=$y",
                        image.pixels[sourceIndex + channel],
                        result.pixels[resultIndex + channel],
                    )
                }
            }
        }
    }

    @Test
    fun `one pixel sources remain one pixel for every preset`() {
        CropPreset.entries.forEach { preset ->
            val image = RgbaImage(byteArrayOf(11, 22, 33, 44), 1, 1)

            val result = CropProcessor.centerCrop(image, preset)

            assertEquals(1, result.width)
            assertEquals(1, result.height)
            assertEquals(1, result.pixels.size)
            assertTrue(result.pixels.contentEquals(image.pixels))
            assertNotSame(image.pixels, result.pixels)
        }
    }

    @Test
    fun `each result stays within one pixel of the requested aspect ratio`() {
        val ratios = listOf(
            Ratio(CropPreset.SQUARE, 1L, 1L),
            Ratio(CropPreset.PORTRAIT_9_16, 9L, 16L),
            Ratio(CropPreset.PORTRAIT_4_5, 4L, 5L),
            Ratio(CropPreset.PRINT_4_6, 4L, 6L),
            Ratio(CropPreset.PRINT_8_10, 8L, 10L),
        )

        ratios.forEach { ratio ->
            val result = CropProcessor.centerCrop(source(1000, 997), ratio.preset)
            val difference = abs(result.width * ratio.denominator - result.height * ratio.numerator)

            assertTrue(
                "preset=${ratio.preset} width=${result.width} height=${result.height}",
                difference <= maxOf(result.width, result.height),
            )
        }
    }

    @Test
    fun `center crop rejects invalid source dimensions and buffers`() {
        assertRejects { CropProcessor.centerCrop(RgbaImage(ByteArray(0), 0, 1), CropPreset.SQUARE) }
        assertRejects { CropProcessor.centerCrop(RgbaImage(ByteArray(0), 1, 0), CropPreset.SQUARE) }
        assertRejects { CropProcessor.centerCrop(RgbaImage(ByteArray(3), 1, 1), CropPreset.SQUARE) }
        assertRejects { CropProcessor.centerCrop(RgbaImage(ByteArray(4), Int.MAX_VALUE, Int.MAX_VALUE), CropPreset.SQUARE) }
    }

    private fun source(width: Int, height: Int): RgbaImage =
        RgbaImage(ByteArray(width.toLong().times(height).times(4L).toInt()), width, height)

    private fun assertRejects(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private data class Case(
        val preset: CropPreset,
        val width: Int,
        val height: Int,
        val expected: CropRect,
    )

    private data class Ratio(
        val preset: CropPreset,
        val numerator: Long,
        val denominator: Long,
    )
}
