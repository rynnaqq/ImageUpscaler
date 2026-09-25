package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.work.EnhanceWorker
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhanceWorkerFailureTest {

    @Test
    fun `terminal out of memory keeps allocation detail`() {
        assertEquals(
            "out of memory: allocation failed",
            EnhanceWorker.terminalFailureMessage(OutOfMemoryError("allocation failed")),
        )
    }

    @Test
    fun `cleanup errors do not escape`() {
        listOf(OutOfMemoryError("cleanup allocation"), RuntimeException("cleanup failure")).forEach { error ->
            assertTrue(
                runCatching { EnhanceWorker.closeSafely { throw error } }.isSuccess,
            )
        }
    }

    @Test
    fun `cleanup cancellation propagates`() {
        val expected = CancellationException("cleanup cancellation")
        val actual = runCatching { EnhanceWorker.closeSafely { throw expected } }.exceptionOrNull()

        assertSame(expected, actual)
    }
}
