package com.rimuru.twobytwo.presentation

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.rimuru.twobytwo.data.device.DeviceTiers
import com.rimuru.twobytwo.data.engine.ModelManifest
import com.rimuru.twobytwo.data.engine.ModelRegistry
import com.rimuru.twobytwo.data.engine.OnnxInferenceEngine
import com.rimuru.twobytwo.data.history.FileHistoryStore
import com.rimuru.twobytwo.data.history.HistoryRecord
import com.rimuru.twobytwo.data.history.HistoryStore
import com.rimuru.twobytwo.data.work.EnhanceRequestJson
import com.rimuru.twobytwo.data.work.EnhanceWorker
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class BatchItemStatus {
    QUEUED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class BatchItemState(
    val uri: String,
    val status: BatchItemStatus = BatchItemStatus.QUEUED,
)

/**
 * MVI: single immutable UiState, user intents reduce via [onIntent] (UX-2).
 */
class EnhanceViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        // S1 — batch: first uri drives preview, list drives the job
        val pickedUris: List<String> = emptyList(),
        val batchItems: List<BatchItemState> = emptyList(),
        val pickedWidth: Int = 0,
        val pickedHeight: Int = 0,
        // S2 config
        val scale: ScaleFactor = ScaleFactor.X2,
        val mode: EngineMode = EngineMode.CREATIVE,
        val denoise: Int = DenoiseStrength.DEFAULT.percent,
        val faceRestore: Boolean = false,
        val faceStrength: Int = 50,
        val accelerator: Accelerator = Accelerator.AUTO,
        val isLowSpec: Boolean = false,
        val showFastPathOffer: Boolean = false,
        // S3 processing
        val jobId: UUID? = null,
        val progress: JobProgress? = null,
        val backendUsed: String? = null,
        // Result
        val outputUri: String? = null,
        val error: String? = null,
        val modelProfile: ModelProfile = ModelProfile.ULTRA,
        val availableAccelerators: Set<Accelerator> = setOf(Accelerator.AUTO, Accelerator.CPU),
        val exportPolicy: ExportPolicy = ExportPolicy(),
        val cacheLimitBytes: Long = EnhanceRequest.DEFAULT_CACHE_LIMIT_BYTES,
        val fastPathOfferHandled: Boolean = false,
        val history: List<HistoryRecord> = emptyList(),
        val historyOpen: Boolean = false,
        val selectedHistoryId: String? = null,
        val cropPreset: CropPreset? = null,
        val sharpen: Boolean = true,
        val deblurEnabled: Boolean = false,
        val deblurStrength: Int = 50,
        val scratchRepairEnabled: Boolean = false,
        val scratchRepairStrength: Int = 50,
        val colorizeEnabled: Boolean = false,
        val colorizeStrength: Int = 50,
    ) {
        val previewUri: String? get() = pickedUris.firstOrNull()
        val isProcessing: Boolean get() = progress != null && progress.step != ProcessStep.DONE
        val outputFormat: OutputFormat get() = exportPolicy.format

        val quality: Int get() = exportPolicy.effectiveJpegQuality
        val keepExif: Boolean get() = exportPolicy.keepExif
        val keepGps: Boolean get() = exportPolicy.keepGps
    }

    sealed interface Intent {
        data class PickPhotos(val uris: List<Uri>) : Intent
        data object ClearPhoto : Intent
        data class SetScale(val scale: ScaleFactor) : Intent
        data class SetMode(val mode: EngineMode) : Intent
        data class SetDenoise(val percent: Int) : Intent
        data class SetFaceRestore(val enabled: Boolean) : Intent
        data class SetFaceStrength(val percent: Int) : Intent
        data class SetCropPreset(val preset: CropPreset?) : Intent
        data class SetSharpen(val enabled: Boolean) : Intent
        data class SetDeblur(val enabled: Boolean) : Intent
        data class SetDeblurStrength(val percent: Int) : Intent
        data class SetScratchRepair(val enabled: Boolean) : Intent
        data class SetScratchRepairStrength(val percent: Int) : Intent
        data class SetColorize(val enabled: Boolean) : Intent
        data class SetColorizeStrength(val percent: Int) : Intent
        data class SetModelProfile(val profile: ModelProfile) : Intent
        data class SetAccelerator(val accelerator: Accelerator) : Intent
        data class SetExportFormat(val format: OutputFormat) : Intent
        data class SetJpegQuality(val quality: Int) : Intent

        data class SetKeepExif(val enabled: Boolean) : Intent
        data class SetKeepGps(val enabled: Boolean) : Intent
        data class SetCacheLimitBytes(val bytes: Long) : Intent
        data object StartEnhance : Intent
        data object CancelJob : Intent
        data object DismissError : Intent
        data object OpenHistory : Intent
        data class RestoreHistorySettings(val id: String) : Intent
        data class DuplicateHistory(val id: String) : Intent
        data class ExportHistoryItem(val id: String) : Intent
    }

    private val historyStore = FileHistoryStore(app)
    private var historyLoadJob: Job? = null
    private var progressCollector: Job? = null

    private val _state = MutableStateFlow(
        UiState(
            isLowSpec = DeviceTiers.classify(app).isLowSpec,
            availableAccelerators = probeAvailableAccelerators(app),

        ),
    )
    val state: StateFlow<UiState> = _state

    fun onIntent(intent: Intent) {
        when (intent) {
            is Intent.PickPhotos -> _state.update {
                if (intent.uris.isNotEmpty()) {
                    val pickedUris = intent.uris.map(Uri::toString)
                    it.copy(
                        pickedUris = pickedUris,
                        batchItems = initialBatchItems(pickedUris),
                        outputUri = null,
                        progress = null,
                        error = null,
                        selectedHistoryId = null,
                    )
                } else it.copy(selectedHistoryId = null)
            }
            Intent.ClearPhoto -> _state.update {
                it.copy(
                    pickedUris = emptyList(),
                    batchItems = emptyList(),
                    outputUri = null,
                    progress = null,
                    selectedHistoryId = null,
                )
            }
            is Intent.SetScale -> _state.update { it.copy(scale = intent.scale) }
            is Intent.SetMode -> _state.update { it.copy(mode = intent.mode) }
            is Intent.SetDenoise -> _state.update { it.copy(denoise = intent.percent.coerceIn(0, 100)) }
            is Intent.SetFaceRestore -> _state.update { it.copy(faceRestore = intent.enabled) }
            is Intent.SetFaceStrength -> _state.update { it.copy(faceStrength = intent.percent.coerceIn(0, 100)) }
            is Intent.SetCropPreset,
            is Intent.SetSharpen,
            is Intent.SetDeblur,
            is Intent.SetDeblurStrength,
            is Intent.SetScratchRepair,
            is Intent.SetScratchRepairStrength,
            is Intent.SetColorize,
            is Intent.SetColorizeStrength,
            is Intent.SetModelProfile,
            is Intent.SetAccelerator,
            is Intent.SetExportFormat,
            is Intent.SetJpegQuality,

            is Intent.SetKeepExif,
            is Intent.SetKeepGps,
            is Intent.SetCacheLimitBytes -> _state.update { applySettingsIntent(it, intent) }
            Intent.OpenHistory -> toggleHistory()
            is Intent.RestoreHistorySettings -> restoreHistorySettings(intent.id)
            is Intent.DuplicateHistory -> duplicateHistory(intent.id)
            is Intent.ExportHistoryItem -> exportHistoryItem(intent.id)
            Intent.StartEnhance -> startJob()
            Intent.CancelJob -> cancelJob()
            Intent.DismissError -> _state.update { it.copy(error = null) }
        }
    }

    private fun startJob() {
        val s = _state.value
        if (s.pickedUris.isEmpty()) return
        if (shouldOfferFastPath(s)) {
            // US-09: offer fast path once on low-spec devices
            _state.update { it.copy(showFastPathOffer = true) }
            return
        }

        progressCollector?.cancel()
        progressCollector = null
        val request = requestFor(s)
        val json = EnhanceRequestJson.encode(request)
        val workData = Data.Builder().putString(EnhanceWorker.KEY_REQUEST, json).build()
        val work = OneTimeWorkRequestBuilder<EnhanceWorker>()
            .setInputData(workData)

            .build()

        val wm = WorkManager.getInstance(getApplication())
        wm.enqueueUniqueWork("enhance", ExistingWorkPolicy.REPLACE, work)
        _state.update {
            beginJobState(it, s.pickedUris).copy(jobId = work.id)
        }

        progressCollector = observeProgress(work.id)
    }

    private fun observeProgress(workId: UUID): Job {
        val wm = WorkManager.getInstance(getApplication())
        return viewModelScope.launch {
            wm.getWorkInfoByIdFlow(workId).collect { info ->
                when (info?.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        val p = info.progress
                        val step = p.getString(EnhanceWorker.KEY_STEP)?.let {
                            runCatching { ProcessStep.valueOf(it) }.getOrNull()
                        }
                        val outputUri = p.getString(EnhanceWorker.KEY_OUTPUT_URI)?.takeIf { it.isNotBlank() }
                        val batchOutcomes = p.getString(EnhanceWorker.KEY_BATCH_OUTCOMES)
                        if (step != null && (step != ProcessStep.DONE || !outputUri.isNullOrBlank())) {
                            val progress = JobProgress(
                                step = step,
                                tilesDone = p.getInt(EnhanceWorker.KEY_TILES_DONE, 0),
                                tilesTotal = p.getInt(EnhanceWorker.KEY_TILES_TOTAL, 0),
                                backendUsed = p.getString(EnhanceWorker.KEY_BACKEND),
                                outputUri = outputUri,
                                batchIndex = p.getInt(EnhanceWorker.KEY_BATCH_INDEX, 0),
                                batchTotal = p.getInt(EnhanceWorker.KEY_BATCH_TOTAL, 1),
                                skippedSmallFaces = p.getInt(EnhanceWorker.KEY_SKIPPED_SMALL_FACES, 0),
                                error = p.getString(EnhanceWorker.KEY_ERROR)?.takeIf { it.isNotBlank() },
                                overallOverride = p.getString(EnhanceWorker.KEY_OVERALL_OVERRIDE)?.toFloatOrNull(),
                            )
                            _state.update { current ->
                                applyObservedWork(current, workId) {
                                    runningState(it, progress, batchOutcomes)
                                }
                            }
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val outputUri = firstNonBlank(
                            info.outputData.getString(EnhanceWorker.KEY_OUTPUT_URI),
                            info.progress.getString(EnhanceWorker.KEY_OUTPUT_URI),
                        )
                        val backend = firstNonBlank(
                            info.outputData.getString(EnhanceWorker.KEY_BACKEND),
                            info.progress.getString(EnhanceWorker.KEY_BACKEND),
                        )
                        val error = firstNonBlank(
                            info.outputData.getString(EnhanceWorker.KEY_ERROR),
                            info.progress.getString(EnhanceWorker.KEY_ERROR),
                        )
                        val batchOutcomes = firstNonBlank(
                            info.outputData.getString(EnhanceWorker.KEY_BATCH_OUTCOMES),
                            info.progress.getString(EnhanceWorker.KEY_BATCH_OUTCOMES),
                        )
                        val skippedSmallFaces = info.outputData.getInt(
                            EnhanceWorker.KEY_SKIPPED_SMALL_FACES,
                            info.progress.getInt(EnhanceWorker.KEY_SKIPPED_SMALL_FACES, 0),
                        )
                        if (outputUri == null) {
                            _state.update { current ->
                                applyObservedWork(current, workId) {
                                    failedState(
                                        withBatchOutcomes(it, batchOutcomes),
                                        error ?: getApplication<Application>()
                                            .getString(com.rimuru.twobytwo.R.string.error_job_failed),
                                        backend,
                                    )
                                }
                            }
                        } else {
                            _state.update { current ->
                                applyObservedWork(current, workId) {
                                    succeededState(
                                        withBatchOutcomes(it, batchOutcomes),
                                        outputUri,
                                        backend,
                                        skippedSmallFaces,
                                    )
                                }
                            }
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val error = firstNonBlank(
                            info.outputData.getString(EnhanceWorker.KEY_ERROR),
                            info.progress.getString(EnhanceWorker.KEY_ERROR),
                        )
                        val backend = firstNonBlank(
                            info.outputData.getString(EnhanceWorker.KEY_BACKEND),
                            info.progress.getString(EnhanceWorker.KEY_BACKEND),
                        )
                        val batchOutcomes = firstNonBlank(
                            info.outputData.getString(EnhanceWorker.KEY_BATCH_OUTCOMES),
                            info.progress.getString(EnhanceWorker.KEY_BATCH_OUTCOMES),
                        )
                        _state.update { current ->
                            applyObservedWork(current, workId) {
                                failedState(
                                    withBatchOutcomes(it, batchOutcomes),
                                    error ?: getApplication<Application>()
                                        .getString(com.rimuru.twobytwo.R.string.error_job_failed),
                                    backend,
                                )
                            }
                        }
                    }
                    WorkInfo.State.CANCELLED -> {
                        val batchOutcomes = info.progress.getString(EnhanceWorker.KEY_BATCH_OUTCOMES)
                        _state.update { current ->
                            applyObservedWork(current, workId) { cancelledState(it, batchOutcomes) }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun cancelJob() {
        _state.value.jobId?.let { WorkManager.getInstance(getApplication()).cancelWorkById(it) }
    }


    private fun toggleHistory() {
        historyLoadJob?.cancel()
        if (_state.value.historyOpen) {
            historyLoadJob = null
            _state.update { it.copy(historyOpen = false, selectedHistoryId = null) }
            return
        }
        _state.update { it.copy(historyOpen = true, selectedHistoryId = null) }
        historyLoadJob = viewModelScope.launch {
            try {
                val records = withContext(Dispatchers.IO) { loadHistory(historyStore) }
                _state.update { current -> current.copy(history = records) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
            }
        }
    }

    private fun restoreHistorySettings(id: String) {
        viewModelScope.launch {
            try {
                val record = withContext(Dispatchers.IO) { historyStore.find(id) } ?: return@launch
                _state.update { current -> restoreHistoryState(current, record) ?: current }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
            }
        }
    }

    private fun duplicateHistory(id: String) {
        historyLoadJob?.cancel()
        val newId = UUID.randomUUID().toString()
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    historyStore.duplicateSettings(id, newId)?.let { child ->
                        child to historyStore.list()
                    }
                } ?: return@launch
                val (child, records) = result
                _state.update { current ->
                    val restored = restoreHistoryState(current, child)
                    (restored ?: current).copy(history = records)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
            }
        }
    }

    private fun exportHistoryItem(id: String) {
        viewModelScope.launch {
            try {
                val record = withContext(Dispatchers.IO) { historyStore.find(id) } ?: return@launch
                _state.update { current -> selectHistoryOutput(current, record) ?: current }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        internal fun loadHistory(store: HistoryStore): List<HistoryRecord> = store.list()

        internal fun decodeHistoryRequest(record: HistoryRecord): EnhanceRequest? {
            if (record.sourceUri.isBlank()) return null
            return runCatching {
                val json = JSONObject(record.settingsJson)
                json.put("inputUris", JSONArray().put(record.sourceUri))
                EnhanceRequestJson.decode(json.toString())
            }.getOrNull()
        }

        internal fun restoreHistoryState(state: UiState, record: HistoryRecord): UiState? {
            val request = decodeHistoryRequest(record) ?: return null
            return state.copy(
                pickedUris = listOf(record.sourceUri),
                batchItems = initialBatchItems(listOf(record.sourceUri)),
                pickedWidth = 0,
                pickedHeight = 0,
                scale = request.scale,
                mode = request.mode,
                denoise = request.denoise.percent,
                faceRestore = request.faceRestoreEnabled,
                faceStrength = request.faceRestoreStrength,
                accelerator = request.accelerator.takeIf { it in state.availableAccelerators }
                    ?: Accelerator.AUTO,
                modelProfile = request.modelProfile,
                exportPolicy = request.exportPolicy,
                cacheLimitBytes = request.cacheLimitBytes,
                cropPreset = request.cropPreset,
                sharpen = request.sharpen,
                deblurEnabled = request.deblurEnabled,
                deblurStrength = request.deblurStrength,
                scratchRepairEnabled = request.scratchRepairEnabled,
                scratchRepairStrength = request.scratchRepairStrength,
                colorizeEnabled = request.colorizeEnabled,
                colorizeStrength = request.colorizeStrength,
                outputUri = null,
                progress = null,
                jobId = null,
                backendUsed = null,

                error = null,
                showFastPathOffer = false,
                historyOpen = false,
                selectedHistoryId = record.id,
            )
        }

        internal fun selectHistoryOutput(state: UiState, record: HistoryRecord): UiState? {
            if (record.sourceUri.isBlank() || record.outputUri.isNullOrBlank()) return null
            return state.copy(
                pickedUris = listOf(record.sourceUri),
                batchItems = initialBatchItems(listOf(record.sourceUri)),
                outputUri = record.outputUri,
                progress = null,
                jobId = null,
                backendUsed = null,
                error = null,
                historyOpen = false,
                selectedHistoryId = record.id,
            )
        }

        internal fun historyParent(
            record: HistoryRecord,
            history: List<HistoryRecord>,
        ): HistoryRecord? {
            val parentId = record.parentId ?: return null
            return history.firstOrNull { it.id == parentId }
        }

        internal fun filterAvailableAccelerators(
            probe: (Accelerator) -> Boolean,
        ): Set<Accelerator> = Accelerator.entries
            .filterTo(linkedSetOf()) { accelerator ->
                accelerator == Accelerator.AUTO || probe(accelerator)
            }

        internal fun shouldOfferFastPath(state: UiState): Boolean =
            state.pickedUris.isNotEmpty() &&
                state.isLowSpec &&
                !state.showFastPathOffer &&
                !state.fastPathOfferHandled &&
                state.accelerator == Accelerator.AUTO &&
                state.modelProfile == ModelProfile.ULTRA

        internal fun applySettingsIntent(state: UiState, intent: Intent): UiState = when (intent) {
            is Intent.SetCropPreset -> state.copy(cropPreset = intent.preset)
            is Intent.SetSharpen -> state.copy(sharpen = intent.enabled)
            is Intent.SetDeblur -> state.copy(deblurEnabled = intent.enabled)
            is Intent.SetDeblurStrength -> state.copy(deblurStrength = intent.percent.coerceIn(0, 100))
            is Intent.SetScratchRepair -> state.copy(scratchRepairEnabled = intent.enabled)
            is Intent.SetScratchRepairStrength ->
                state.copy(scratchRepairStrength = intent.percent.coerceIn(0, 100))
            is Intent.SetColorize -> state.copy(colorizeEnabled = intent.enabled)
            is Intent.SetColorizeStrength -> state.copy(colorizeStrength = intent.percent.coerceIn(0, 100))
            is Intent.SetModelProfile -> state.copy(
                modelProfile = intent.profile,
                showFastPathOffer = false,
                fastPathOfferHandled = true,
            )
            is Intent.SetExportFormat -> state.copy(exportPolicy = state.exportPolicy.copy(format = intent.format))

            is Intent.SetJpegQuality -> state.copy(
                exportPolicy = state.exportPolicy.copy(jpegQuality = intent.quality.coerceIn(80, 100)),
            )
            is Intent.SetKeepExif -> state.copy(
                exportPolicy = state.exportPolicy.copy(keepExif = intent.enabled),
            )
            is Intent.SetKeepGps -> state.copy(
                exportPolicy = state.exportPolicy.copy(keepGps = intent.enabled),
            )
            is Intent.SetCacheLimitBytes -> state.copy(
                cacheLimitBytes = intent.bytes.coerceIn(
                    EnhanceRequest.MIN_CACHE_LIMIT_BYTES,
                    EnhanceRequest.MAX_CACHE_LIMIT_BYTES,
                ),
            )
            is Intent.SetAccelerator -> if (intent.accelerator in state.availableAccelerators) {
                state.copy(accelerator = intent.accelerator)
            } else {
                state
            }
            else -> state
        }

        internal fun requestFor(state: UiState): EnhanceRequest = EnhanceRequest(
            inputUris = state.pickedUris,
            scale = state.scale,
            mode = state.mode,
            denoise = DenoiseStrength(state.denoise),
            faceRestoreEnabled = state.faceRestore,
            faceRestoreStrength = state.faceStrength,
            accelerator = state.accelerator.takeIf { it in state.availableAccelerators } ?: Accelerator.AUTO,
            useNeuralEngine = state.modelProfile == ModelProfile.ULTRA,
            sharpen = state.sharpen,
            deblurEnabled = state.deblurEnabled,
            deblurStrength = state.deblurStrength,
            scratchRepairEnabled = state.scratchRepairEnabled,
            scratchRepairStrength = state.scratchRepairStrength,
            colorizeEnabled = state.colorizeEnabled,
            colorizeStrength = state.colorizeStrength,
            cropPreset = state.cropPreset,
            cacheLimitBytes = state.cacheLimitBytes,

            exportPolicy = state.exportPolicy,
        )

        internal fun <T : AutoCloseable> probeAvailableAccelerators(
            createEngine: () -> T,
            probe: (T, Accelerator) -> Boolean,
        ): Set<Accelerator> {
            val engine = try {
                createEngine()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return setOf(Accelerator.AUTO)
            }
            var available = setOf(Accelerator.AUTO)
            var closeFailed = false
            try {
                available = filterAvailableAccelerators { accelerator ->
                    try {
                        probe(engine, accelerator)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        false
                    }
                }.toSet()
            } finally {
                try {
                    engine.close()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    closeFailed = true
                }
            }
            return if (closeFailed) setOf(Accelerator.AUTO) else available
        }

        private fun probeAvailableAccelerators(app: Application): Set<Accelerator> =
            probeAvailableAccelerators(
                createEngine = { OnnxInferenceEngine(ModelRegistry(app, ModelManifest.PLACEHOLDER)) },
                probe = { engine, accelerator -> engine.isAvailable(accelerator) },
            )

        internal fun initialProgress(batchTotal: Int): JobProgress =
            JobProgress(
                step = ProcessStep.PREPARING,
                batchIndex = 0,
                batchTotal = batchTotal.coerceAtLeast(1),
            )

        internal fun firstNonBlank(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() }

        internal fun applyObservedWork(
            state: UiState,
            observedWorkId: UUID,
            update: (UiState) -> UiState,
        ): UiState = if (state.jobId == observedWorkId) update(state) else state

        internal fun initialBatchItems(uris: List<String>): List<BatchItemState> =
            uris.map { BatchItemState(it) }


        internal fun beginJobState(state: UiState, uris: List<String>): UiState = state.copy(
            outputUri = null,
            progress = initialProgress(uris.size),
            batchItems = initialBatchItems(uris),
            backendUsed = null,
            error = null,
            historyOpen = false,
            selectedHistoryId = null,
        )

        internal fun applyBatchOutcomes(
            items: List<BatchItemState>,
            encoded: String?,
        ): List<BatchItemState> {
            if (encoded.isNullOrEmpty()) return items
            return items.mapIndexed { index, item ->
                when (encoded.getOrNull(index)) {
                    EnhanceWorker.OUTCOME_SUCCESS -> item.copy(status = BatchItemStatus.SUCCEEDED)
                    EnhanceWorker.OUTCOME_FAILURE -> item.copy(status = BatchItemStatus.FAILED)
                    EnhanceWorker.OUTCOME_CANCELLED -> item.copy(status = BatchItemStatus.CANCELLED)
                    else -> item
                }
            }
        }

        internal fun withBatchOutcomes(state: UiState, encoded: String?): UiState {
            val items = state.batchItems.ifEmpty { initialBatchItems(state.pickedUris) }
            return state.copy(batchItems = applyBatchOutcomes(items, encoded))
        }

        internal fun updateBatchItems(
            items: List<BatchItemState>,
            progress: JobProgress,
        ): List<BatchItemState> {
            if (items.isEmpty()) return items
            val total = progress.batchTotal.coerceAtLeast(1)
            val index = progress.batchIndex.coerceIn(0, minOf(items.lastIndex, total - 1))
            val currentStatus = when {
                progress.error != null -> BatchItemStatus.FAILED
                progress.step == ProcessStep.DONE -> BatchItemStatus.SUCCEEDED
                else -> BatchItemStatus.PROCESSING
            }
            return items.mapIndexed { itemIndex, item ->
                when {
                    itemIndex < index &&
                        item.status != BatchItemStatus.FAILED &&
                        item.status != BatchItemStatus.CANCELLED -> item.copy(status = BatchItemStatus.SUCCEEDED)
                    itemIndex == index &&
                        (item.status == BatchItemStatus.QUEUED || item.status == BatchItemStatus.PROCESSING) ->
                        item.copy(status = currentStatus)
                    else -> item
                }
            }
        }

        internal fun runningState(
            state: UiState,
            progress: JobProgress,
            encodedOutcomes: String? = null,
        ): UiState {
            val withOutcomes = withBatchOutcomes(state, encodedOutcomes)
            val items = withOutcomes.batchItems
            return withOutcomes.copy(
                progress = progress,
                batchItems = updateBatchItems(items, progress),
                backendUsed = progress.backendUsed?.takeIf { it.isNotBlank() } ?: withOutcomes.backendUsed,
            )
        }

        internal fun cancelledState(state: UiState, encodedOutcomes: String? = null): UiState {
            val withOutcomes = withBatchOutcomes(state, encodedOutcomes)
            val items = withOutcomes.batchItems
            val index = state.progress?.batchIndex?.coerceIn(0, items.lastIndex.coerceAtLeast(0)) ?: 0
            return withOutcomes.copy(
                progress = null,
                batchItems = items.mapIndexed { itemIndex, item ->
                    when {
                        item.status == BatchItemStatus.FAILED ||
                            item.status == BatchItemStatus.CANCELLED ||
                            item.status == BatchItemStatus.SUCCEEDED -> item
                        itemIndex < index -> item.copy(status = BatchItemStatus.SUCCEEDED)
                        else -> item.copy(status = BatchItemStatus.CANCELLED)
                    }
                },
            )
        }

        internal fun succeededState(
            state: UiState,
            outputUri: String,
            backend: String?,
            skippedSmallFaces: Int = 0,
        ): UiState {
            val backendUsed = backend?.takeIf { it.isNotBlank() } ?: state.backendUsed
            val items = state.batchItems.ifEmpty { initialBatchItems(state.pickedUris) }
            val previousProgress = state.progress
            return state.copy(
                outputUri = outputUri,
                progress = JobProgress(
                    ProcessStep.DONE,
                    backendUsed = backendUsed,
                    outputUri = outputUri,
                    batchIndex = previousProgress?.batchIndex ?: (items.lastIndex.coerceAtLeast(0)),
                    batchTotal = previousProgress?.batchTotal ?: items.size.coerceAtLeast(1),
                    skippedSmallFaces = skippedSmallFaces,
                ),
                batchItems = items.map {
                    if (it.status == BatchItemStatus.FAILED || it.status == BatchItemStatus.CANCELLED) {
                        it
                    } else {
                        it.copy(status = BatchItemStatus.SUCCEEDED)
                    }
                },
                backendUsed = backendUsed,
                error = null,
            )
        }

        internal fun failedState(state: UiState, error: String, backend: String?): UiState {
            val items = state.batchItems.ifEmpty { initialBatchItems(state.pickedUris) }
            return state.copy(
                progress = null,
                batchItems = items.map {
                    if (
                        it.status == BatchItemStatus.SUCCEEDED ||
                        it.status == BatchItemStatus.FAILED ||
                        it.status == BatchItemStatus.CANCELLED
                    ) {
                        it
                    } else {
                        it.copy(status = BatchItemStatus.FAILED)
                    }
                },
                backendUsed = backend?.takeIf { it.isNotBlank() } ?: state.backendUsed,
                error = error,
            )
        }
    }
}
