package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.work.EnhanceWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class EnhanceWorkerFailureTest {

    @Test
    fun `terminal out of memory keeps allocation detail`() {
        assertEquals(
            "out of memory: allocation failed",
            EnhanceWorker.terminalFailureMessage(OutOfMemoryError("allocation failed")),
        )
    }
}
