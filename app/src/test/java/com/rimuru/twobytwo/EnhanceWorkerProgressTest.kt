package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.work.EnhanceWorker
import com.rimuru.twobytwo.domain.model.JobProgress
import com.rimuru.twobytwo.domain.model.ProcessStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every tile used to emit a JobProgress, and the worker turned each one into a
 * setProgress + setForeground pair (plus a notification rebuild). A 12 MP x2 job
 * is ~221 tiles, so that was ~442 binder round-trips per image. The throttle keeps
 * the UI current while collapsing a burst into one write.
 */
class EnhanceWorkerProgressTest {

    private fun tile(tilesDone: Int, total: Int = 100) =
        JobProgress(step = ProcessStep.PROCESSING_TILES, tilesDone = tilesDone, tilesTotal = total)

    @Test
    fun `the first progress is always emitted`() {
        val throttle = EnhanceWorker.ProgressThrottle(intervalMs = 250, clock = { 1_000 })
        assertTrue(throttle.shouldEmit(tile(1)))
    }

    @Test
    fun `a burst inside the interval is suppressed`() {
        var now = 1_000L
        val throttle = EnhanceWorker.ProgressThrottle(intervalMs = 250, clock = { now })
        assertTrue(throttle.shouldEmit(tile(1)))
        repeat(20) { i ->
            now += 10
            assertFalse("tile ${i + 2} should be suppressed", throttle.shouldEmit(tile(i + 2)))
        }
    }

    @Test
    fun `progress resumes once the interval elapses`() {
        var now = 1_000L
        val throttle = EnhanceWorker.ProgressThrottle(intervalMs = 250, clock = { now })
        assertTrue(throttle.shouldEmit(tile(1)))
        now += 249
        assertFalse(throttle.shouldEmit(tile(2)))
        now += 1
        assertTrue("exactly at the interval must emit", throttle.shouldEmit(tile(3)))
    }

    @Test
    fun `a step change is never suppressed`() {
        var now = 1_000L
        val throttle = EnhanceWorker.ProgressThrottle(intervalMs = 250, clock = { now })
        assertTrue(throttle.shouldEmit(tile(1)))
        now += 1
        assertTrue(
            "BLENDING must not wait for the interval",
            throttle.shouldEmit(JobProgress(step = ProcessStep.BLENDING)),
        )
    }

    @Test
    fun `the final tile is never suppressed`() {
        var now = 1_000L
        val throttle = EnhanceWorker.ProgressThrottle(intervalMs = 250, clock = { now })
        assertTrue(throttle.shouldEmit(tile(1)))
        now += 1
        assertTrue(
            "tilesDone == tilesTotal must emit so the UI reaches 100%",
            throttle.shouldEmit(tile(99, total = 99)),
        )
    }

    @Test
    fun `a completed item is never suppressed`() {
        var now = 1_000L
        val throttle = EnhanceWorker.ProgressThrottle(intervalMs = 250, clock = { now })
        assertTrue(throttle.shouldEmit(tile(1)))
        now += 1
        assertTrue(
            throttle.shouldEmit(JobProgress(step = ProcessStep.DONE, itemCompleted = true)),
        )
    }

    @Test
    fun `an error is never suppressed`() {
        var now = 1_000L
        val throttle = EnhanceWorker.ProgressThrottle(intervalMs = 250, clock = { now })
        assertTrue(throttle.shouldEmit(tile(1)))
        now += 1
        assertTrue(
            throttle.shouldEmit(
                JobProgress(step = ProcessStep.PROCESSING_TILES, error = "tile failed"),
            ),
        )
    }

    @Test
    fun `a two hundred tile burst collapses to a handful of writes`() {
        var now = 1_000L
        val throttle = EnhanceWorker.ProgressThrottle(intervalMs = 250, clock = { now })
        var emitted = 0
        for (i in 1..200) {
            now += 5
            if (throttle.shouldEmit(tile(i, total = 200))) emitted++
        }
        // 1000 ms of 5 ms-per-tile work at a 250 ms interval: first, then ~4 more,
        // plus the forced final tile.
        assertTrue("expected a small number of writes, got $emitted", emitted in 4..7)
    }

    @Test
    fun `a zero interval emits every progress`() {
        var now = 1_000L
        val throttle = EnhanceWorker.ProgressThrottle(intervalMs = 0, clock = { now })
        repeat(5) {
            now += 1
            assertTrue(throttle.shouldEmit(tile(it + 1)))
        }
    }

    @Test
    fun `the default interval is a sane quarter second`() {
        assertEquals(250L, EnhanceWorker.ProgressThrottle.DEFAULT_INTERVAL_MS)
    }
}
