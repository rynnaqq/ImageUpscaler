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
import org.json.JSONObject
import java.util.UUID

/**
 * MVI: single immutable UiState, user intents reduce via [onIntent] (UX-2).
 */
class EnhanceViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        // S1
        val pickedUri: String? = null,
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
        val isProcessing: Boolean get() = progress != null && progress.step != ProcessStep.DONE
    }

    sealed interface Intent {
        data class PickPhoto(val uri: Uri) : Intent
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
            is Intent.PickPhoto -> _state.update {
                it.copy(pickedUri = intent.uri.toString(), error = null)
            }
            Intent.ClearPhoto -> _state.update { it.copy(pickedUri = null, outputUri = null, progress = null) }
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
        val uri = s.pickedUri ?: return
        if (s.isLowSpec && !s.showFastPathOffer && s.accelerator == Accelerator.AUTO) {
            // US-09: offer fast path once on low-spec devices
            _state.update { it.copy(showFastPathOffer = true) }
            return
        }

        val request = EnhanceRequest(
            inputUri = uri,
            scale = s.scale,
            mode = s.mode,
            denoise = DenoiseStrength(s.denoise),
            faceRestoreEnabled = s.faceRestore,
            faceRestoreStrength = s.faceStrength,
            accelerator = s.accelerator,
        )
        val json = JSONObject().apply {
            put("inputUri", request.inputUri)
            put("scale", request.scale.multiplier)
            put("mode", request.mode.name)
            put("denoise", request.denoise.percent)
            put("faceRestore", request.faceRestoreEnabled)
            put("faceStrength", request.faceRestoreStrength)
            put("accelerator", request.accelerator.name)
        }
        val workData = Data.Builder().putString(EnhanceWorker.KEY_REQUEST, json.toString()).build()
        val work = OneTimeWorkRequestBuilder<EnhanceWorker>()
            .setInputData(workData)
            .build()

        val wm = WorkManager.getInstance(getApplication())
        wm.enqueueUniqueWork("enhance", ExistingWorkPolicy.REPLACE, work)
        _state.update { it.copy(jobId = work.id, progress = JobProgress(ProcessStep.PREPARING)) }

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
                        if (step != null) {
                            _state.update {
                                it.copy(
                                    progress = JobProgress(
                                        step = step,
                                        tilesDone = p.getInt(EnhanceWorker.KEY_TILES_DONE, 0),
                                        tilesTotal = p.getInt(EnhanceWorker.KEY_TILES_TOTAL, 0),
                                        backendUsed = p.getString(EnhanceWorker.KEY_BACKEND),
                                    ),
                                )
                            }
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        // Output lands in MediaStore; surfaced via Export screen by re-querying
                        _state.update { it.copy(progress = JobProgress(ProcessStep.DONE)) }
                    }
                    WorkInfo.State.FAILED -> _state.update {
                        it.copy(progress = null, error = getApplication<Application>().getString(com.rimuru.twobytwo.R.string.error_job_failed))
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
}
