package com.rimuru.twobytwo.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.rimuru.twobytwo.R
import com.rimuru.twobytwo.data.engine.ModelManifest
import com.rimuru.twobytwo.data.engine.OnnxInferenceEngine
import com.rimuru.twobytwo.data.media.MediaStoreImageIo
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.DenoiseStrength
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.EngineMode
import com.rimuru.twobytwo.domain.model.JobProgress
import com.rimuru.twobytwo.domain.model.ProcessStep
import com.rimuru.twobytwo.domain.model.ScaleFactor
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long-running restore job (PRD §5.7): foreground dataSync worker, progress via
 * setProgress → Flow, cancelable. JSON-serializes the request so WorkManager
 * persistence works without custom serializers.
 */
class EnhanceWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val request = parseRequest(inputData.getString(KEY_REQUEST)) ?: return Result.failure()
        val outputName = inputData.getString(KEY_OUTPUT_NAME) ?: defaultOutputName()
        val engine = OnnxInferenceEngine(applicationContext, ModelManifest.PLACEHOLDER)
            .withAccelerator(request.accelerator)

        try {
            setForeground(createForegroundInfo(applicationContext.getString(R.string.proc_step_preparing)))

            val useCase = EnhanceImage(engine, MediaStoreImageIo(applicationContext))
            val flow = useCase.run(
                request = request,
                outputUri = outputName,
                format = EnhanceImage.OutputFormat.PNG,
            )

            var last: JobProgress? = null
            flow.collect { progress ->
                last = progress
                setProgress(
                    androidx.work.workDataOf(
                        KEY_STEP to progress.step.name,
                        KEY_TILES_DONE to progress.tilesDone,
                        KEY_TILES_TOTAL to progress.tilesTotal,
                        KEY_BACKEND to (progress.backendUsed ?: engine.backendName),
                    ),
                )
                setForeground(createForegroundInfo(stepText(progress)))
            }

            return if (last?.step == ProcessStep.DONE) Result.success() else Result.failure()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return Result.failure()
        } finally {
            engine.close() // temp cache wiped by model cache dir lifecycle; outputs durable in MediaStore
        }
    }

    private fun stepText(p: JobProgress): String = when (p.step) {
        ProcessStep.PREPARING -> applicationContext.getString(R.string.proc_step_preparing)
        ProcessStep.DETECTING_FACES -> applicationContext.getString(R.string.proc_step_detecting_faces)
        ProcessStep.PROCESSING_TILES ->
            applicationContext.getString(R.string.proc_step_tiles, p.tilesDone, p.tilesTotal)
        ProcessStep.RESTORING_FACES -> applicationContext.getString(R.string.proc_step_restoring_faces)
        ProcessStep.BLENDING -> applicationContext.getString(R.string.proc_step_blending)
        ProcessStep.DONE -> applicationContext.getString(R.string.proc_step_done)
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.proc_title), NotificationManager.IMPORTANCE_LOW),
            )
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

    private fun parseRequest(json: String?): EnhanceRequest? = runCatching {
        val o = JSONObject(json ?: return null)
        EnhanceRequest(
            inputUri = o.getString("inputUri"),
            scale = if (o.getInt("scale") == 4) ScaleFactor.X4 else ScaleFactor.X2,
            mode = if (o.getString("mode") == "PRECISION") EngineMode.PRECISION else EngineMode.CREATIVE,
            denoise = DenoiseStrength(o.getInt("denoise")),
            faceRestoreEnabled = o.getBoolean("faceRestore"),
            faceRestoreStrength = o.getInt("faceStrength"),
            accelerator = Accelerator.entries.first { it.name == o.optString("accelerator", "AUTO") },
            useNeuralEngine = o.optBoolean("neural", true),
        )
    }.getOrNull()

    private fun defaultOutputName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "rimuru2x_$ts.png"
    }

    companion object {
        const val KEY_REQUEST = "request"
        const val KEY_OUTPUT_NAME = "outputName"
        const val KEY_STEP = "step"
        const val KEY_TILES_DONE = "tilesDone"
        const val KEY_TILES_TOTAL = "tilesTotal"
        const val KEY_BACKEND = "backend"
        const val CHANNEL_ID = "enhance_jobs"
        const val NOTIFICATION_ID = 42
    }
}
