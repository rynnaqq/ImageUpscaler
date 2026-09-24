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
import com.rimuru.twobytwo.data.work.EnhanceWorker
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.DenoiseStrength
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.EngineMode
import com.rimuru.twobytwo.domain.model.JobProgress
import com.rimuru.twobytwo.domain.model.ProcessStep
import com.rimuru.twobytwo.domain.model.ScaleFactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * MVI: single immutable UiState, user intents reduce via [onIntent] (UX-2).
 */
class EnhanceViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        // S1 — batch: first uri drives preview, list drives the job
        val pickedUris: List<String> = emptyList(),
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
    ) {
        val previewUri: String? get() = pickedUris.firstOrNull()
        val isProcessing: Boolean get() = progress != null && progress.step != ProcessStep.DONE
    }

    sealed interface Intent {
        data class PickPhotos(val uris: List<Uri>) : Intent
        data object ClearPhoto : Intent
        data class SetScale(val scale: ScaleFactor) : Intent
        data class SetMode(val mode: EngineMode) : Intent
        data class SetDenoise(val percent: Int) : Intent
        data class SetFaceRestore(val enabled: Boolean) : Intent
        data class SetFaceStrength(val percent: Int) : Intent
        data class SetAccelerator(val accelerator: Accelerator) : Intent
        data object StartEnhance : Intent
        data object CancelJob : Intent
        data object DismissError : Intent
    }

    private val _state = MutableStateFlow(UiState(isLowSpec = DeviceTiers.classify(app).isLowSpec))
    val state: StateFlow<UiState> = _state

    fun onIntent(intent: Intent) {
        when (intent) {
            is Intent.PickPhotos -> _state.update {
                if (intent.uris.isNotEmpty()) {
                    it.copy(pickedUris = intent.uris.map(Uri::toString), outputUri = null, progress = null, error = null)
                } else it
            }
            Intent.ClearPhoto -> _state.update { it.copy(pickedUris = emptyList(), outputUri = null, progress = null) }
            is Intent.SetScale -> _state.update { it.copy(scale = intent.scale) }
            is Intent.SetMode -> _state.update { it.copy(mode = intent.mode) }
            is Intent.SetDenoise -> _state.update { it.copy(denoise = intent.percent.coerceIn(0, 100)) }
            is Intent.SetFaceRestore -> _state.update { it.copy(faceRestore = intent.enabled) }
            is Intent.SetFaceStrength -> _state.update { it.copy(faceStrength = intent.percent.coerceIn(0, 100)) }
            is Intent.SetAccelerator -> _state.update { it.copy(accelerator = intent.accelerator) }
            Intent.StartEnhance -> startJob()
            Intent.CancelJob -> cancelJob()
            Intent.DismissError -> _state.update { it.copy(error = null) }
        }
    }

    private fun startJob() {
        val s = _state.value
        if (s.pickedUris.isEmpty()) return
        if (s.isLowSpec && !s.showFastPathOffer && s.accelerator == Accelerator.AUTO) {
            // US-09: offer fast path once on low-spec devices
            _state.update { it.copy(showFastPathOffer = true) }
            return
        }

        val request = EnhanceRequest(
            inputUris = s.pickedUris,
            scale = s.scale,
            mode = s.mode,
            denoise = DenoiseStrength(s.denoise),
            faceRestoreEnabled = s.faceRestore,
            faceRestoreStrength = s.faceStrength,
            accelerator = s.accelerator,
        )
        val json = JSONObject().apply {
            put("inputUris", JSONArray(request.inputUris))
            put("scale", request.scale.multiplier)
            put("mode", request.mode.name)
            put("denoise", request.denoise.percent)
            put("faceRestore", request.faceRestoreEnabled)
            put("faceStrength", request.faceRestoreStrength)
            put("accelerator", request.accelerator.name)
            put("sharpen", request.sharpen)
            put("deblurEnabled", request.deblurEnabled)
            put("deblurStrength", request.deblurStrength)
        }
        val workData = Data.Builder().putString(EnhanceWorker.KEY_REQUEST, json.toString()).build()
        val work = OneTimeWorkRequestBuilder<EnhanceWorker>()
            .setInputData(workData)
            .build()

        val wm = WorkManager.getInstance(getApplication())
        wm.enqueueUniqueWork("enhance", ExistingWorkPolicy.REPLACE, work)
        _state.update {
            it.copy(
                jobId = work.id,
                outputUri = null,
                progress = JobProgress(ProcessStep.PREPARING),
                backendUsed = null,
            )
        }

        observeProgress(work.id)
    }

    private fun observeProgress(workId: UUID) {
        val wm = WorkManager.getInstance(getApplication())
        viewModelScope.launch {
            wm.getWorkInfoByIdFlow(workId).collect { info ->
                when (info?.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        val p = info.progress
                        val step = p.getString(EnhanceWorker.KEY_STEP)?.let { runCatching { ProcessStep.valueOf(it) }.getOrNull() }
                        val outputUri = p.getString(EnhanceWorker.KEY_OUTPUT_URI)?.takeIf { it.isNotBlank() }
                        if (step != null && (step != ProcessStep.DONE || !outputUri.isNullOrBlank())) {
                            val progress = JobProgress(
                                step = step,
                                tilesDone = p.getInt(EnhanceWorker.KEY_TILES_DONE, 0),
                                tilesTotal = p.getInt(EnhanceWorker.KEY_TILES_TOTAL, 0),
                                backendUsed = p.getString(EnhanceWorker.KEY_BACKEND),
                                outputUri = outputUri,
                                batchIndex = p.getInt(EnhanceWorker.KEY_BATCH_INDEX, 0),
                                batchTotal = p.getInt(EnhanceWorker.KEY_BATCH_TOTAL, 1),
                            )
                            _state.update { runningState(it, progress) }
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
                        if (outputUri == null) {
                            _state.update {
                                failedState(
                                    it,
                                    error ?: getApplication<Application>().getString(com.rimuru.twobytwo.R.string.error_job_failed),
                                    backend,
                                )
                            }
                        } else {
                            _state.update { succeededState(it, outputUri, backend) }
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
                        _state.update {
                            failedState(
                                it,
                                error ?: getApplication<Application>().getString(com.rimuru.twobytwo.R.string.error_job_failed),
                                backend,
                            )
                        }
                    }
                    WorkInfo.State.CANCELLED -> _state.update { it.copy(progress = null) }
                    else -> {}
                }
            }
        }
    }

    private fun cancelJob() {
        _state.value.jobId?.let { WorkManager.getInstance(getApplication()).cancelWorkById(it) }
    }

    companion object {
        internal fun firstNonBlank(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() }

        internal fun runningState(state: UiState, progress: JobProgress): UiState = state.copy(
            progress = progress,
            backendUsed = progress.backendUsed?.takeIf { it.isNotBlank() } ?: state.backendUsed,
        )

        internal fun succeededState(state: UiState, outputUri: String, backend: String?): UiState {
            val backendUsed = backend?.takeIf { it.isNotBlank() } ?: state.backendUsed
            return state.copy(
                outputUri = outputUri,
                progress = JobProgress(ProcessStep.DONE, backendUsed = backendUsed, outputUri = outputUri),
                backendUsed = backendUsed,
            )
        }

        internal fun failedState(state: UiState, error: String, backend: String?): UiState = state.copy(
            progress = null,
            backendUsed = backend?.takeIf { it.isNotBlank() } ?: state.backendUsed,
            error = error,
        )
    }
}
