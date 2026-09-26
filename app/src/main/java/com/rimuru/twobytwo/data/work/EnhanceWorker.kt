package com.rimuru.twobytwo.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.rimuru.twobytwo.R
import com.rimuru.twobytwo.data.cache.RenderCacheStore
import com.rimuru.twobytwo.data.device.DeviceTiers
import com.rimuru.twobytwo.data.history.FileHistoryStore
import com.rimuru.twobytwo.data.engine.ModelManifest
import com.rimuru.twobytwo.data.engine.ModelRegistry
import com.rimuru.twobytwo.data.engine.OnnxInferenceEngine
import com.rimuru.twobytwo.data.media.MediaStoreImageIo
import com.rimuru.twobytwo.domain.model.EnhanceResult
import com.rimuru.twobytwo.domain.model.JobProgress
import com.rimuru.twobytwo.domain.model.OutputFormat
import com.rimuru.twobytwo.domain.model.ProcessStep
import com.rimuru.twobytwo.domain.model.ScaleFactor
import com.rimuru.twobytwo.domain.model.modelProfile
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import kotlinx.coroutines.CancellationException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun parseScaleFactor(value: Int): ScaleFactor = when (value) {
    4 -> ScaleFactor.X4
    8 -> ScaleFactor.X8
    else -> ScaleFactor.X2
}

internal fun enhanceOutputName(baseName: String, batchIndex: Int, format: OutputFormat): String =
    "${baseName}_${batchIndex + 1}.${format.fileExtension}"

/**
 * Long-running restore job (PRD §5.7): foreground dataSync worker, progress via
 * setProgress → Flow, cancelable. Batch-aware: request carries a URI list; each
 * image is isolated inside EnhanceImage so one failure doesn't kill the batch.
 */
class EnhanceWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private var notificationChannelReady = false

    override suspend fun doWork(): Result {
        var engine: OnnxInferenceEngine? = null
        var lastBackend = ""
        var last: JobProgress? = null
        var outcomes: CharArray? = null

        return runGuarded(
            close = { engine?.close() },
            onFailure = { error ->
                failure(
                    terminalFailureText(error),
                    lastBackend,
                    last?.batchIndex,
                    last?.batchTotal,
                    outcomes?.let { String(it) },
                )
            },
        ) {
            val request = EnhanceRequestJson.decode(inputData.getString(KEY_REQUEST))
                ?: return@runGuarded failure("Invalid enhancement request")
            val baseName = inputData.getString(KEY_OUTPUT_NAME) ?: defaultBaseName()
            val tier = DeviceTiers.classify(applicationContext)
            val registry = ModelRegistry(applicationContext, ModelManifest.BUNDLED)
            val created = OnnxInferenceEngine(registry)
            engine = created
            val configured = created
                .withProfile(request.modelProfile)
                .withAccelerator(request.accelerator)
            lastBackend = configured.backendName
            val batchOutcomes = CharArray(request.inputUris.size) { OUTCOME_QUEUED }
            outcomes = batchOutcomes
            val persistenceFailures = mutableSetOf<Int>()
            var firstPersistenceFailure: String? = null

            setForeground(createForegroundInfo(applicationContext.getString(R.string.proc_step_preparing)))

            val historyStore = FileHistoryStore(applicationContext)
            val cacheStore = RenderCacheStore(
                File(applicationContext.cacheDir, "renders"),
                request.cacheLimitBytes,
            )
            val persistenceService = WorkflowPersistenceService(historyStore, cacheStore)
            val runId = id.toString()
            val settingsJson = EnhanceRequestJson.encodeSettings(request)
            val useCase = EnhanceImage(
                configured,
                MediaStoreImageIo(applicationContext),
                EnhanceImage.TileConfig(tier.recommendedTileSize),
                modelProvider = registry,
                streamingScratchDirectory = applicationContext.cacheDir,
            )
            val flow = useCase.run(
                request = request,
                outputNameFor = { index, _ ->
                    enhanceOutputName(baseName, index, request.exportPolicy.format)
                },
                isCancelled = { isStopped },
                onItemCompleted = { batchIndex, result ->
                    if (result is EnhanceResult.Success) {
                        try {
                            persistenceService.saveCompletedItem(
                                runId = runId,
                                batchIndex = batchIndex,
                                sourceUri = request.inputUris[batchIndex],
                                settingsJson = settingsJson,
                                result = result,
                            )
                            batchOutcomes[batchIndex] = OUTCOME_SUCCESS
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: OutOfMemoryError) {
                            throw e
                         } catch (t: Throwable) {
                             persistenceFailures += batchIndex
                             batchOutcomes[batchIndex] = OUTCOME_FAILURE

                            if (firstPersistenceFailure == null) {
                                firstPersistenceFailure = t.message?.takeIf { it.isNotBlank() }
                                    ?: t::class.java.simpleName
                                    ?: "Persistence failed"
                            }
                        }
                    }
                },
            )

            val throttle = ProgressThrottle()
            flow.collect { progress ->
                last = progress
                if (progress.itemCompleted && progress.batchIndex in batchOutcomes.indices) {
                    batchOutcomes[progress.batchIndex] = when {
                        progress.batchIndex in persistenceFailures -> OUTCOME_FAILURE
                        progress.error == null -> OUTCOME_SUCCESS
                        else -> OUTCOME_FAILURE
                    }
                }
                val reportedBackend = progress.backendUsed?.takeIf { it.isNotBlank() } ?: lastBackend
                lastBackend = reportedBackend
                if (!throttle.shouldEmit(progress)) return@collect
                setProgress(
                    androidx.work.workDataOf(
                        KEY_STEP to progress.step.name,
                        KEY_TILES_DONE to progress.tilesDone,
                        KEY_TILES_TOTAL to progress.tilesTotal,
                        KEY_BACKEND to reportedBackend,
                        KEY_BATCH_INDEX to progress.batchIndex,
                        KEY_BATCH_TOTAL to progress.batchTotal,
                        KEY_SKIPPED_SMALL_FACES to progress.skippedSmallFaces,
                        KEY_OVERALL_OVERRIDE to progress.overallOverride?.toString().orEmpty(),
                        KEY_OUTPUT_URI to progress.outputUri.orEmpty(),
                        KEY_ERROR to progress.error.orEmpty(),
                        KEY_BATCH_OUTCOMES to String(batchOutcomes),
                    ),
                )
                setForeground(createForegroundInfo(stepText(progress)))
            }

            val outputUri = last?.outputUri
            val persistenceFailure = firstPersistenceFailure
            if (persistenceFailure != null) {
                return@runGuarded failure(
                    persistenceFailure,
                    lastBackend,
                    last?.batchIndex,
                    last?.batchTotal,
                    String(batchOutcomes),
                )
            }
            return@runGuarded if (last?.step == ProcessStep.DONE && !outputUri.isNullOrBlank()) {
                Result.success(
                    androidx.work.workDataOf(
                        KEY_OUTPUT_URI to outputUri,
                        KEY_BACKEND to lastBackend,
                        KEY_SKIPPED_SMALL_FACES to (last?.skippedSmallFaces ?: 0),
                        KEY_BATCH_INDEX to (last?.batchIndex ?: 0),
                        KEY_BATCH_TOTAL to (last?.batchTotal ?: 1),
                        KEY_BATCH_OUTCOMES to String(batchOutcomes),
                    ),
                )
            } else {
                failure(
                    last?.error,
                    last?.backendUsed ?: lastBackend,
                    last?.batchIndex,
                    last?.batchTotal,
                    String(batchOutcomes),
                )
            }
        }
    }

    private fun failure(

        message: String?,
        backend: String? = null,
        batchIndex: Int? = null,
        batchTotal: Int? = null,
        outcomes: String? = null,
    ): Result = Result.failure(
        failureData(
            message = message,
            backend = backend,
            batchIndex = batchIndex,
            batchTotal = batchTotal,
            outcomes = outcomes,
            fallbackError = applicationContext.getString(R.string.error_job_failed),
        ),
    )

    private fun stepText(p: JobProgress): String {
        val batchPrefix = if (p.batchTotal > 1) "(${p.batchIndex + 1}/${p.batchTotal}) " else ""
        val base = when (p.step) {
            ProcessStep.PREPARING -> applicationContext.getString(R.string.proc_step_preparing)
            ProcessStep.DETECTING_FACES -> applicationContext.getString(R.string.proc_step_detecting_faces)
            ProcessStep.PROCESSING_TILES ->
                applicationContext.getString(R.string.proc_step_tiles, p.tilesDone, p.tilesTotal)
            ProcessStep.RESTORING_FACES -> applicationContext.getString(R.string.proc_step_restoring_faces)
            ProcessStep.BLENDING -> applicationContext.getString(R.string.proc_step_blending)
            ProcessStep.DONE -> applicationContext.getString(R.string.proc_step_done)
        }
        return batchPrefix + base
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // The channel is process-wide and immutable once created; registering it per
        // progress update was pure waste. The worker instance is per job, so this
        // still runs once per job rather than once per tile.
        if (!notificationChannelReady && Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.proc_title), NotificationManager.IMPORTANCE_LOW),
            )
            notificationChannelReady = true
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun defaultBaseName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "rimuru2x_$ts"
    }

    /**
     * Rate-limits the per-tile progress writes. Every tile emits a JobProgress and
     * the worker used to turn each one into a setProgress + setForeground pair plus a
     * fresh NotificationCompat build, so a 12 MP x2 job (~221 tiles) cost ~442 binder
     * round-trips. Tile progress is still reported often enough to look live, while
     * step changes, item completion, errors and the final tile always get through.
     */
    internal class ProgressThrottle(
        private val intervalMs: Long = DEFAULT_INTERVAL_MS,
        private val clock: () -> Long = System::currentTimeMillis,
    ) {
        private var lastEmitAt: Long? = null
        private var lastStep: ProcessStep? = null

        fun shouldEmit(progress: JobProgress): Boolean {
            val now = clock()
            val previous = lastEmitAt
            val dueByClock = previous == null || now - previous >= intervalMs
            val important = progress.itemCompleted ||
                progress.error != null ||
                progress.step != lastStep ||
                (progress.tilesTotal > 0 && progress.tilesDone >= progress.tilesTotal)
            if (!dueByClock && !important) return false
            lastEmitAt = now
            lastStep = progress.step
            return true
        }

        companion object {
            const val DEFAULT_INTERVAL_MS = 250L
        }
    }

    companion object {
        internal fun terminalFailureMessage(error: Throwable): String {
            val detail = error.message?.takeIf { it.isNotBlank() }
            return when {
                error is OutOfMemoryError && detail != null -> "out of memory: $detail"
                error is OutOfMemoryError -> "out of memory"
                detail != null -> detail
                else -> error::class.simpleName ?: "Enhancement failed"
            }
        }

        internal fun closeSafely(close: () -> Unit) {
            try {
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }

        internal fun terminalFailureText(error: Throwable): String? =
            if (error is OutOfMemoryError) terminalFailureMessage(error) else error.message

        internal fun failureData(
            message: String?,
            backend: String?,
            batchIndex: Int?,
            batchTotal: Int?,
            outcomes: String?,
            fallbackError: String,
        ): Data {
            val data = Data.Builder()
                .putString(
                    KEY_ERROR,
                    message?.takeIf { it.isNotBlank() }?.take(200) ?: fallbackError,
                )
                .putString(KEY_BACKEND, backend.orEmpty())
            batchIndex?.let { data.putInt(KEY_BATCH_INDEX, it) }
            batchTotal?.let { data.putInt(KEY_BATCH_TOTAL, it) }
            outcomes?.let { data.putString(KEY_BATCH_OUTCOMES, it) }
            return data.build()
        }

        internal inline fun <T> runGuarded(
            noinline close: () -> Unit,
            onFailure: (Throwable) -> T,
            block: () -> T,
        ): T {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return onFailure(t)
            } finally {
                closeSafely(close)
            }
        }

        const val KEY_REQUEST = "request"
        const val KEY_OUTPUT_NAME = "outputName"
        const val KEY_STEP = "step"
        const val KEY_TILES_DONE = "tilesDone"
        const val KEY_TILES_TOTAL = "tilesTotal"
        const val KEY_BACKEND = "backend"
        const val KEY_BATCH_INDEX = "batchIndex"
        const val KEY_BATCH_TOTAL = "batchTotal"
        const val KEY_BATCH_OUTCOMES = "batchOutcomes"
        const val OUTCOME_QUEUED = 'Q'
        const val OUTCOME_SUCCESS = 'S'
        const val OUTCOME_FAILURE = 'F'
        const val OUTCOME_CANCELLED = 'C'
        const val KEY_SKIPPED_SMALL_FACES = "skippedSmallFaces"
        const val KEY_OVERALL_OVERRIDE = "overallOverride"
        const val KEY_OUTPUT_URI = "outputUri"
        const val KEY_ERROR = "error"
        const val CHANNEL_ID = "enhance_jobs"
        const val NOTIFICATION_ID = 42
    }
}
