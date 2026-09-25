package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.work.EnhanceWorker
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhanceWorkerFailureTest {

    private fun payloadFor(
        error: Throwable,
        backend: String?,
        batchIndex: Int? = null,
        batchTotal: Int? = null,
        outcomes: String? = null,
    ) = EnhanceWorker.failureData(
        message = EnhanceWorker.terminalFailureText(error),
        backend = backend,
        batchIndex = batchIndex,
        batchTotal = batchTotal,
        outcomes = outcomes,
        fallbackError = FALLBACK_ERROR,
    )

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

    @Test
    fun `out of memory payload keeps the terminal message`() {
        val detailed = payloadFor(
            error = OutOfMemoryError("engine construction"),
            backend = "",
        )
        val bare = payloadFor(
            error = OutOfMemoryError(),
            backend = "",
        )

        assertEquals("out of memory: engine construction", detailed.getString(EnhanceWorker.KEY_ERROR))
        assertEquals("out of memory", bare.getString(EnhanceWorker.KEY_ERROR))
    }

    @Test
    fun `message-less and blank failures use the localized fallback`() {
        listOf(IllegalStateException(), IllegalStateException("   ")).forEach { error ->
            assertEquals(
                FALLBACK_ERROR,
                payloadFor(error = error, backend = null).getString(EnhanceWorker.KEY_ERROR),
            )
        }
    }

    @Test
    fun `setup failure payload reports an empty backend and no batch fields`() {
        val data = payloadFor(
            error = IllegalStateException("request decode failed"),
            backend = null,
        )

        assertEquals("request decode failed", data.getString(EnhanceWorker.KEY_ERROR))
        assertEquals("", data.getString(EnhanceWorker.KEY_BACKEND))
        assertEquals(-1, data.getInt(EnhanceWorker.KEY_BATCH_INDEX, -1))
        assertEquals(-1, data.getInt(EnhanceWorker.KEY_BATCH_TOTAL, -1))
        assertNull(data.getString(EnhanceWorker.KEY_BATCH_OUTCOMES))
    }

    @Test
    fun `body failure payload carries batch index total and outcomes`() {
        val data = payloadFor(
            error = RuntimeException("tile failed"),
            backend = "ORT CPU",
            batchIndex = 1,
            batchTotal = 3,
            outcomes = "QS",
        )

        assertEquals("tile failed", data.getString(EnhanceWorker.KEY_ERROR))
        assertEquals("ORT CPU", data.getString(EnhanceWorker.KEY_BACKEND))
        assertEquals(1, data.getInt(EnhanceWorker.KEY_BATCH_INDEX, -1))
        assertEquals(3, data.getInt(EnhanceWorker.KEY_BATCH_TOTAL, -1))
        assertEquals("QS", data.getString(EnhanceWorker.KEY_BATCH_OUTCOMES))
    }

    private companion object {
        const val FALLBACK_ERROR = "Enhancement failed"
    }
}
