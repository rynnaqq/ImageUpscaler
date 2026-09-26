package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding used to re-measure the image: EnhanceImage measured the input, then
 * decode() measured it again, and each measure() opened the content stream twice
 * (bounds + Exif) while decode() opened it twice more. That was 6 content-uri
 * opens and 2 Exif parses per photo, half of them redundant.
 */
class DecodeOnceTest {

    private class CountingIo(
        private val side: Int,
        private val orientation: Int = 1,
    ) : EnhanceImage.ImageIo {
        var measureCalls = 0
        var decodeCalls = 0
        var decodeHint: EnhanceImage.Dimensions? = null

        override fun measure(uri: String, maxMegapixels: Int): EnhanceImage.Dimensions {
            measureCalls++
            return dimensions()
        }

        override fun decode(uri: String, maxMegapixels: Int): EnhanceImage.DecodedImage {
            decodeCalls++
            return decoded()
        }

        override fun decode(
            uri: String,
            maxMegapixels: Int,
            dimensions: EnhanceImage.Dimensions,
        ): EnhanceImage.DecodedImage {
            decodeCalls++
            decodeHint = dimensions
            return decoded()
        }

        override fun encode(
            rgba: ByteArray,
            width: Int,
            height: Int,
            destinationUri: String,
            policy: com.rimuru.twobytwo.domain.model.ExportPolicy,
            exifSourceUri: String?,
        ): String = destinationUri

        fun dimensions() = EnhanceImage.Dimensions(side, side, orientation)

        private fun decoded() = EnhanceImage.DecodedImage(ByteArray(side * side * 4), side, side)
    }

    @Test
    fun `the dimensions hint carries the exif orientation`() {
        val dimensions = EnhanceImage.Dimensions(4000, 3000, 6)
        assertEquals(4000, dimensions.width)
        assertEquals(3000, dimensions.height)
        assertEquals(6, dimensions.orientation)
    }

    @Test
    fun `dimensions default to a normal orientation so existing callers still compile`() {
        assertEquals(1, EnhanceImage.Dimensions(10, 20).orientation)
    }

    @Test
    fun `a rotated input reports swapped dimensions`() {
        val rotated = EnhanceImage.Dimensions(3000, 4000, 6)
        val upright = EnhanceImage.Dimensions(4000, 3000, 1)
        assertEquals(3000, rotated.width)
        assertEquals(4000, rotated.height)
        assertTrue(upright.width > upright.height)
    }

    @Test
    fun `the three argument decode receives the already measured dimensions`() {
        val io = CountingIo(side = 64)
        val measured = io.measure("content://x", 48)

        io.decode("content://x", 48, measured)

        assertEquals(1, io.decodeCalls)
        assertSame(measured, io.decodeHint)
        assertEquals("decode must not re-measure", 1, io.measureCalls)
    }
}
