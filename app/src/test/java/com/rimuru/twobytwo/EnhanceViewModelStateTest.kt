package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.model.JobProgress
import com.rimuru.twobytwo.domain.model.ProcessStep
import com.rimuru.twobytwo.presentation.EnhanceViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.UUID

class EnhanceViewModelStateTest {

    @Test
    fun `initial preparing progress uses the picked batch total`() {
        val progress = EnhanceViewModel.initialProgress(batchTotal = 2)

        assertEquals(ProcessStep.PREPARING, progress.step)
        assertEquals(0, progress.batchIndex)
        assertEquals(2, progress.batchTotal)
    }

    @Test
    fun `progress data wins when terminal output has a blank backend`() {
        assertEquals(
            "Bicubic fallback (model unavailable)",
            EnhanceViewModel.firstNonBlank("", "Bicubic fallback (model unavailable)"),
        )
    }

    @Test
    fun `running progress keeps backend in state and progress`() {
        val previous = EnhanceViewModel.UiState(backendUsed = "previous")
        val progress = JobProgress(ProcessStep.PROCESSING_TILES, backendUsed = "ORT CPU")

        val updated = EnhanceViewModel.runningState(previous, progress)

        assertEquals("ORT CPU", updated.backendUsed)
        assertEquals("ORT CPU", updated.progress?.backendUsed)
    }

    @Test
    fun `stale work info cannot overwrite a replacement job`() {
        val oldWorkId = UUID.randomUUID()
        val currentWorkId = UUID.randomUUID()
        val currentProgress = JobProgress(ProcessStep.PROCESSING_TILES, tilesDone = 2)
        val state = EnhanceViewModel.UiState(
            jobId = currentWorkId,
            progress = currentProgress,
        )

        val updated = EnhanceViewModel.applyObservedWork(state, oldWorkId) {
            it.copy(progress = JobProgress(ProcessStep.DONE, outputUri = "content://output/stale"))
        }

        assertSame(state, updated)
        assertEquals(currentProgress, updated.progress)
    }

    @Test
    fun `current work info updates the matching job`() {
        val workId = UUID.randomUUID()
        val state = EnhanceViewModel.UiState(jobId = workId)
        val progress = JobProgress(ProcessStep.PROCESSING_TILES, tilesDone = 3)

        val updated = EnhanceViewModel.applyObservedWork(state, workId) {
            it.copy(progress = progress)
        }

        assertEquals(progress, updated.progress)
    }

    @Test
    fun `starting a new job clears a previous error`() {
        val updated = EnhanceViewModel.beginJobState(
            EnhanceViewModel.UiState(error = "old failure"),
            listOf("content://input/photo"),
        )

        assertNull(updated.error)
        assertEquals(1, updated.batchItems.size)
    }

    @Test
    fun `success keeps terminal backend in state and done progress`() {
        val updated = EnhanceViewModel.succeededState(
            EnhanceViewModel.UiState(),
            outputUri = "content://output/photo",
            backend = "Bicubic fallback (model unavailable)",
        )

        assertEquals("Bicubic fallback (model unavailable)", updated.backendUsed)
        assertEquals("Bicubic fallback (model unavailable)", updated.progress?.backendUsed)
        assertEquals("content://output/photo", updated.progress?.outputUri)
    }

    @Test
    fun `failure keeps backend while clearing progress`() {
        val updated = EnhanceViewModel.failedState(
            EnhanceViewModel.UiState(backendUsed = "ORT CPU"),
            error = "backend failed",
            backend = "Bicubic fallback (session unavailable)",
        )

        assertEquals("Bicubic fallback (session unavailable)", updated.backendUsed)
        assertNull(updated.progress)
        assertEquals("backend failed", updated.error)
    }
}
