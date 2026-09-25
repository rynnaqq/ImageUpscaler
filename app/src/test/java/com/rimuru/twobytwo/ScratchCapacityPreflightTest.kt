package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.media.ensureScratchCapacity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScratchCapacityPreflightTest {

    @Test
    fun `insufficient available space reports requirement and availability`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            ensureScratchCapacity(requiredBytes = 1_048_576L, availableBytes = 1_048_575L)
        }

        assertEquals(
            "insufficient temporary storage: need 1048576 bytes, have 1048575 bytes",
            failure.message,
        )
    }

    @Test
    fun `available space equal to the requirement is accepted`() {
        ensureScratchCapacity(requiredBytes = 1_048_576L, availableBytes = 1_048_576L)
    }

    @Test
    fun `zero requirement is accepted without space`() {
        ensureScratchCapacity(requiredBytes = 0L, availableBytes = 0L)
    }

    @Test
    fun `long output dimensions are reported without truncation`() {
        val required = 64L * 1024L * 1024L * 1024L

        val failure = assertThrows(IllegalStateException::class.java) {
            ensureScratchCapacity(requiredBytes = required, availableBytes = 0L)
        }

        assertEquals(
            "insufficient temporary storage: need 68719476736 bytes, have 0 bytes",
            failure.message,
        )
    }
}
