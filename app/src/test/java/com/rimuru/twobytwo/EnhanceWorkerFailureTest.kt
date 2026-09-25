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

    @Test
    fun `setup out of memory becomes a structured failure and still cleans up`() {
        var closed = false

        val message = EnhanceWorker.runGuarded(
            close = { closed = true },
            onFailure = { EnhanceWorker.terminalFailureText(it) },
        ) { throw OutOfMemoryError("engine construction") }

        assertEquals("out of memory: engine construction", message)
        assertTrue(closed)
    }

    @Test
    fun `setup failure without allocation detail keeps its message and still cleans up`() {
        var closed = false

        val message = EnhanceWorker.runGuarded(
            close = { closed = true },
            onFailure = { EnhanceWorker.terminalFailureText(it) },
        ) { throw IllegalStateException("request decode failed") }

        assertEquals("request decode failed", message)
        assertTrue(closed)
    }

    @Test
    fun `guarded body propagates cancellation and still cleans up`() {
        val expected = CancellationException("cancelled during setup")
        var closed = false

        val actual = runCatching {
            EnhanceWorker.runGuarded(
                close = { closed = true },
                onFailure = { EnhanceWorker.terminalFailureText(it) },
            ) { throw expected }
        }.exceptionOrNull()

        assertSame(expected, actual)
        assertTrue(closed)
    }
}
