package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.media.attachCleanupFailure
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CleanupFailureSuppressionTest {

    @Test
    fun `absent cleanup failure is ignored with and without a primary`() {
        val primary = IllegalStateException("processing failed")

        assertNull(attachCleanupFailure(primary = null, cleanup = null))
        assertNull(attachCleanupFailure(primary = primary, cleanup = null))
        assertEquals(0, primary.suppressed.size)
    }

    @Test
    fun `cleanup failure without a primary is surfaced unchanged`() {
        val cleanup = IllegalStateException("failed to delete temporary file: /cache/x.png")

        val surfaced = attachCleanupFailure(primary = null, cleanup = cleanup)

        assertSame(cleanup, surfaced)
        assertEquals(0, checkNotNull(surfaced).suppressed.size)
    }

    @Test
    fun `cleanup failure is suppressed behind a primary without replacing it`() {
        val primary = CancellationException("cancelled during row production")
        val cleanup = IllegalStateException("failed to delete temporary file: /cache/x.png")

        val surfaced = attachCleanupFailure(primary = primary, cleanup = cleanup)

        assertNull(surfaced)
        assertEquals(1, primary.suppressed.size)
        assertSame(cleanup, primary.suppressed[0])
        assertEquals("cancelled during row production", primary.message)
    }

    @Test
    fun `both cleanup failures attach to the same primary in call order`() {
        val primary = IllegalStateException("MediaStore insert failed")
        val rowFailure = IllegalStateException("failed to delete pending row")
        val fileFailure = IllegalStateException("failed to delete temporary file: /cache/x.png")

        assertNull(attachCleanupFailure(primary, rowFailure))
        assertNull(attachCleanupFailure(primary, fileFailure))

        assertEquals(2, primary.suppressed.size)
        assertSame(rowFailure, primary.suppressed[0])
        assertSame(fileFailure, primary.suppressed[1])
    }
}
