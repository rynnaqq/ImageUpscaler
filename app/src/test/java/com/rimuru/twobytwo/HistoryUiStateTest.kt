package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.history.FileHistoryStore
import com.rimuru.twobytwo.data.history.HistoryRecord
import com.rimuru.twobytwo.data.work.EnhanceRequestJson
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.CropPreset
import com.rimuru.twobytwo.domain.model.DenoiseStrength
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.EngineMode
import com.rimuru.twobytwo.domain.model.ExportPolicy
import com.rimuru.twobytwo.domain.model.JobProgress
import com.rimuru.twobytwo.domain.model.ModelProfile
import com.rimuru.twobytwo.domain.model.OutputFormat
import com.rimuru.twobytwo.domain.model.ProcessStep
import com.rimuru.twobytwo.domain.model.ScaleFactor
import com.rimuru.twobytwo.domain.model.modelProfile
import com.rimuru.twobytwo.presentation.BatchItemState
import com.rimuru.twobytwo.presentation.EnhanceViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class HistoryUiStateTest {

    @Test
    fun `history list preserves newest-first store order`() {
        val store = FileHistoryStore(Files.createTempDirectory("history-ui-order").toFile())
        val oldest = record("oldest", createdAt = 10L)
        val newest = record("newest", createdAt = 30L)
        val middle = record("middle", createdAt = 20L)
        store.save(oldest)
        store.save(newest)
        store.save(middle)

        val loaded = EnhanceViewModel.loadHistory(store)

        assertEquals(listOf("newest", "middle", "oldest"), loaded.map { it.id })
    }

    @Test
    fun `restore parses settings-only JSON into current request state`() {
        val source = "content://source/history-settings"
        val expectedRequest = EnhanceRequest(
            inputUris = listOf("ignored"),
            scale = ScaleFactor.X4,
            mode = EngineMode.PRECISION,
            denoise = DenoiseStrength(73),
            faceRestoreEnabled = true,
            faceRestoreStrength = 82,
            accelerator = Accelerator.NPU,
            useNeuralEngine = false,
            sharpen = false,
            deblurEnabled = true,
            deblurStrength = 64,
            scratchRepairEnabled = true,
            scratchRepairStrength = 61,
            colorizeEnabled = true,
            colorizeStrength = 59,
            cropPreset = CropPreset.SQUARE,
            cacheLimitBytes = 1_500_000_000L,
            exportPolicy = ExportPolicy(
                format = OutputFormat.WEBP,
                jpegQuality = 88,
                keepExif = false,
                keepGps = true,
            ),
        )
        val record = record(
            id = "settings-record",
            sourceUri = source,
            settingsJson = EnhanceRequestJson.encodeSettings(expectedRequest),
            outputUri = "content://output/old",
        )
        val state = EnhanceViewModel.UiState(
            pickedUris = listOf("content://source/old"),
            batchItems = EnhanceViewModel.initialBatchItems(listOf("content://source/old")),
            pickedWidth = 100,
            pickedHeight = 200,
            jobId = UUID.randomUUID(),
            progress = JobProgress(ProcessStep.DONE, outputUri = "content://output/old"),
            backendUsed = "ORT CPU",
            outputUri = "content://output/old",
            error = "old error",
            modelProfile = ModelProfile.ULTRA,
            availableAccelerators = setOf(Accelerator.AUTO, Accelerator.CPU, Accelerator.NPU),
            exportPolicy = ExportPolicy(),
            cacheLimitBytes = EnhanceRequest.DEFAULT_CACHE_LIMIT_BYTES,
            fastPathOfferHandled = true,
            history = listOf(record),
            historyOpen = true,
            selectedHistoryId = "old-record",
            showFastPathOffer = true,
            isLowSpec = true,
        )

        val restored = EnhanceViewModel.restoreHistoryState(state, record)

        assertNotNull(restored)
        val result = requireNotNull(restored)
        val request = EnhanceViewModel.requestFor(result)
        assertEquals(listOf(source), result.pickedUris)
        assertEquals(ScaleFactor.X4, result.scale)
        assertEquals(EngineMode.PRECISION, result.mode)
        assertEquals(73, result.denoise)
        assertTrue(result.faceRestore)
        assertEquals(82, result.faceStrength)
        assertEquals(ModelProfile.FAST, result.modelProfile)
        assertEquals(Accelerator.NPU, result.accelerator)
        assertEquals(OutputFormat.WEBP, result.exportPolicy.format)
        assertEquals(88, result.exportPolicy.jpegQuality)
        assertFalse(result.exportPolicy.keepExif)
        assertTrue(result.exportPolicy.keepGps)
        assertEquals(1_500_000_000L, result.cacheLimitBytes)
        assertEquals(listOf(BatchItemState(source)), result.batchItems)
        assertEquals(0, result.pickedWidth)
        assertEquals(0, result.pickedHeight)
        assertNull(result.outputUri)
        assertNull(result.progress)
        assertNull(result.jobId)
        assertNull(result.backendUsed)
        assertNull(result.error)
        assertFalse(result.showFastPathOffer)
        assertFalse(result.historyOpen)
        assertEquals(record.id, result.selectedHistoryId)
        assertEquals(state.availableAccelerators, result.availableAccelerators)
        assertTrue(result.isLowSpec)
        assertTrue(result.fastPathOfferHandled)
        assertEquals(listOf(source), request.inputUris)
        assertEquals(ScaleFactor.X4, request.scale)
        assertEquals(EngineMode.PRECISION, request.mode)
        assertEquals(73, request.denoise.percent)
        assertTrue(request.faceRestoreEnabled)
        assertEquals(82, request.faceRestoreStrength)
        assertEquals(Accelerator.NPU, request.accelerator)
        assertEquals(ModelProfile.FAST, request.modelProfile)
        assertEquals(expectedRequest.copy(inputUris = listOf(source)), request)
    }

    @Test
    fun `restore normalizes an unavailable accelerator to AUTO`() {
        val record = record(
            id = "unsupported-accelerator",
            sourceUri = "content://source/accelerator",
            settingsJson = EnhanceRequestJson.encodeSettings(
                request(accelerator = Accelerator.NPU),
            ),
        )
        val state = EnhanceViewModel.UiState(
            accelerator = Accelerator.CPU,
            availableAccelerators = setOf(Accelerator.AUTO, Accelerator.CPU),
        )

        val restored = requireNotNull(EnhanceViewModel.restoreHistoryState(state, record))
        val request = EnhanceViewModel.requestFor(restored)

        assertEquals(Accelerator.AUTO, restored.accelerator)
        assertEquals(Accelerator.AUTO, request.accelerator)
    }

    @Test
    fun `malformed history settings do not mutate current state`() {
        val original = EnhanceViewModel.UiState(
            outputUri = "content://output/live",
            progress = JobProgress(ProcessStep.DONE, outputUri = "content://output/live"),
            error = "live error",
            selectedHistoryId = "live-record",
        )
        val malformed = record("malformed", settingsJson = "{not valid json")
        val blankSource = record("blank", sourceUri = " ")

        var state = EnhanceViewModel.restoreHistoryState(original, malformed) ?: original
        assertEquals(original, state)
        state = EnhanceViewModel.restoreHistoryState(state, blankSource) ?: state
        assertEquals(original, state)
    }

    @Test
    fun `duplicate creates a settings-only child and preserves the parent output`() {
        val store = FileHistoryStore(Files.createTempDirectory("history-ui-duplicate").toFile())
        val parent = record(
            id = "parent",
            createdAt = 10L,
            sourceUri = "content://source/parent",
            settingsJson = EnhanceRequestJson.encodeSettings(request()),
            outputUri = "content://output/parent",
        )
        store.save(parent)
        val child = requireNotNull(store.duplicateSettings(parent.id, "child-fixed"))
        val history = EnhanceViewModel.loadHistory(store)
        val restored = requireNotNull(
            EnhanceViewModel.restoreHistoryState(
                EnhanceViewModel.UiState(history = history, historyOpen = true),
                child,
            ),
        )

        assertEquals("child-fixed", child.id)
        assertEquals(parent.sourceUri, child.sourceUri)
        assertEquals(parent.settingsJson, child.settingsJson)
        assertEquals(parent.width, child.width)
        assertEquals(parent.height, child.height)
        assertEquals(parent.id, child.parentId)
        assertNull(child.outputUri)
        assertEquals(parent, store.find(parent.id))
        assertEquals(listOf(child.id, parent.id), restored.history.map { it.id })
        assertEquals(child.id, restored.selectedHistoryId)
        assertFalse(restored.historyOpen)
        assertNull(restored.outputUri)
        assertNull(restored.progress)
        assertNull(restored.jobId)
        assertNull(restored.error)
        assertEquals(listOf(BatchItemState(parent.sourceUri)), restored.batchItems)
    }

    @Test
    fun `duplicate failure leaves navigation state unchanged`() {
        val store = FileHistoryStore(Files.createTempDirectory("history-ui-duplicate-failure").toFile())
        val parent = record("parent", createdAt = 10L)
        val existing = record("existing", createdAt = 20L)
        store.save(parent)
        store.save(existing)
        val state = EnhanceViewModel.UiState(
            history = listOf(parent, existing),
            historyOpen = true,
            selectedHistoryId = "previous",
        )

        val duplicate = store.duplicateSettings(parent.id, existing.id)
        val after = if (duplicate != null) {
            requireNotNull(EnhanceViewModel.restoreHistoryState(state, duplicate))
        } else {
            state
        }

        assertNull(duplicate)
        assertEquals(state, after)
        assertEquals(parent, store.find(parent.id))
        assertEquals(existing, store.find(existing.id))
    }

    @Test
    fun `export selects an existing URI without rewriting history`() {
        val store = FileHistoryStore(Files.createTempDirectory("history-ui-export").toFile())
        val source = "content://source/export"
        val output = "content://output/export"
        val record = record(
            id = "export-record",
            sourceUri = source,
            outputUri = output,
        )
        store.save(record)
        val state = EnhanceViewModel.UiState(
            pickedUris = listOf("content://source/live"),
            progress = JobProgress(ProcessStep.DONE, outputUri = "content://output/live"),
            jobId = UUID.randomUUID(),
            backendUsed = "ORT CPU",
            outputUri = "content://output/live",
            error = "old error",
            historyOpen = true,
            selectedHistoryId = "old-record",
        )

        val selected = requireNotNull(EnhanceViewModel.selectHistoryOutput(state, record))

        assertEquals(listOf(source), selected.pickedUris)
        assertEquals(output, selected.outputUri)
        assertEquals(record.id, selected.selectedHistoryId)
        assertFalse(selected.historyOpen)
        assertEquals(listOf(BatchItemState(source)), selected.batchItems)
        assertNull(selected.progress)
        assertNull(selected.jobId)
        assertNull(selected.backendUsed)
        assertNull(selected.error)
        assertEquals(record, store.find(record.id))
    }

    @Test
    fun `export ignores a settings-only child`() {
        val child = record("child", outputUri = null)
        val state = EnhanceViewModel.UiState(
            outputUri = "content://output/live",
            selectedHistoryId = "live",
            historyOpen = true,
        )

        val selected = EnhanceViewModel.selectHistoryOutput(state, child)

        assertNull(selected)
        assertEquals("content://output/live", state.outputUri)
        assertEquals("live", state.selectedHistoryId)
        assertTrue(state.historyOpen)
    }

    @Test
    fun `parent lookup follows parentId and tolerates a missing parent`() {
        val parent = record("parent", createdAt = 10L)
        val child = record("child", createdAt = 20L, parentId = parent.id)
        val orphan = record("orphan", createdAt = 30L, parentId = "missing")
        val history = listOf(orphan, child, parent)

        assertEquals(parent, EnhanceViewModel.historyParent(child, history))
        assertNull(EnhanceViewModel.historyParent(parent, history))
        assertNull(EnhanceViewModel.historyParent(orphan, history))
    }

    @Test
    fun `starting a new job clears a historical selection`() {
        val state = EnhanceViewModel.UiState(
            selectedHistoryId = "history-record",
            historyOpen = true,
        )

        val started = EnhanceViewModel.beginJobState(state, listOf("content://source/new"))

        assertNull(started.selectedHistoryId)
    }

    private fun request(
        inputUris: List<String> = listOf("content://source/default"),
        accelerator: Accelerator = Accelerator.AUTO,
    ) = EnhanceRequest(
        inputUris = inputUris,
        accelerator = accelerator,
    )

    private fun record(
        id: String,
        createdAt: Long = 0L,
        sourceUri: String = "content://source/$id",
        settingsJson: String = EnhanceRequestJson.encodeSettings(request()),
        outputUri: String? = "content://output/$id",
        width: Int = 640,
        height: Int = 480,
        parentId: String? = null,
    ) = HistoryRecord(
        id = id,
        sourceUri = sourceUri,
        settingsJson = settingsJson,
        outputUri = outputUri,
        width = width,
        height = height,
        createdAt = createdAt,
        parentId = parentId,
    )
}
