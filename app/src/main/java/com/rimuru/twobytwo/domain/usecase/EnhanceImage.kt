package com.rimuru.twobytwo.domain.usecase

import com.rimuru.twobytwo.domain.engine.ColorizePass
import com.rimuru.twobytwo.domain.engine.DeblurPass
import com.rimuru.twobytwo.domain.engine.DenoisePass
import com.rimuru.twobytwo.domain.engine.FaceDetector
import com.rimuru.twobytwo.domain.engine.FaceRestorePass
import com.rimuru.twobytwo.domain.engine.FaceRestorer
import com.rimuru.twobytwo.domain.engine.ImageOps
import com.rimuru.twobytwo.domain.engine.checkPassCancellation
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.ModelProvider
import com.rimuru.twobytwo.domain.engine.PassContext
import com.rimuru.twobytwo.domain.engine.RgbaImage
import com.rimuru.twobytwo.domain.engine.ScratchRepairPass
import com.rimuru.twobytwo.domain.engine.TensorCodec
import com.rimuru.twobytwo.domain.engine.TileBlender
import com.rimuru.twobytwo.domain.engine.TilingManager
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.EnhanceResult
import com.rimuru.twobytwo.domain.model.EngineMode
import com.rimuru.twobytwo.domain.model.JobProgress
import com.rimuru.twobytwo.domain.model.ProcessStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The core pipeline: decode → tiled upscale with seam blending → optional
 * sharpen → encode. Batch-capable: processes each input sequentially, isolating
 * failures per image so one bad photo never kills the batch (stability rule).
 *
 * Decode/encode behind [ImageIo]; the engine behind [InferenceEngine] — both
 * swappable, keeping this pure Kotlin (PRD §5.3).
 */
class EnhanceImage(
    private val engine: InferenceEngine,
    private val imageIo: ImageIo,
    private val tileConfig: TileConfig = TileConfig(),
    private val modelProvider: ModelProvider? = null,
    private val faceDetector: FaceDetector? = null,
    private val faceRestorer: FaceRestorer? = null,
) {
    interface ImageIo {
        fun measure(uri: String, maxMegapixels: Int): Dimensions

        /** Decode input to RGBA_8888 bytes; returns dims. */
        fun decode(uri: String, maxMegapixels: Int): DecodedImage

        /** Encode RGBA to PNG or JPEG(q>=95) at destination, copying Exif. */
        fun encode(
            rgba: ByteArray,
            width: Int,
            height: Int,
            destinationUri: String,
            format: OutputFormat,
            exifSourceUri: String?,
        ): String
    }

    data class DecodedImage(val rgba: ByteArray, val width: Int, val height: Int) {
        val megapixels: Double get() = width.toLong() * height / 1_000_000.0
    }

    data class Dimensions(val width: Int, val height: Int)

    enum class OutputFormat { PNG, JPEG }

    data class TileConfig(val tileSize: Int = 256)

    data class BatchResult(val succeeded: Int, val failed: Int, val failedUris: List<String>)

    companion object {
        internal const val DEFAULT_MAX_OUTPUT_MEGAPIXELS = 256.0

        private fun outputPixels(outWidth: Long, outHeight: Long): Long {
            require(outWidth > 0 && outHeight > 0) { "output dimensions must be positive" }
            require(outWidth <= Int.MAX_VALUE.toLong() && outHeight <= Int.MAX_VALUE.toLong()) {
                "output dimensions exceed JVM array limit"
            }
            return outWidth * outHeight
        }

        private fun outputBufferSize(outWidth: Long, outHeight: Long): Int {
            val pixels = outputPixels(outWidth, outHeight)
            require(pixels <= Int.MAX_VALUE.toLong() / 4L) { "output buffer exceeds JVM array limit" }
            return (pixels * 4L).toInt()
        }

        private fun validateOutputBufferSize(outWidth: Long, outHeight: Long) {
            val pixels = outputPixels(outWidth, outHeight)
            require(pixels <= Int.MAX_VALUE.toLong() / 4L) { "output buffer exceeds JVM array limit" }
        }

        internal fun outputBufferSize(outWidth: Long, outHeight: Long, maxOutputMegapixels: Double): Int {
            require(maxOutputMegapixels.isFinite() && maxOutputMegapixels > 0.0) {
                "max output megapixels must be finite and positive"
            }
            val pixels = outputPixels(outWidth, outHeight)
            require(pixels <= maxOutputMegapixels * 1_000_000.0) {
                "output ${pixels / 1_000_000.0} MP exceeds cap $maxOutputMegapixels MP"
            }
            return outputBufferSize(outWidth, outHeight)
        }
    }

    /**
     * @param maxMegapixels input safety valve (FR-1.6, default 48 MP).
     * @param sharpenOutput applies unsharp post-pass to images under [sharpenMaxMegapixels]
     *   output size — unsharp needs the full buffer twice; above the ceiling memory
     *   risk outweighs the punch (stability over sharpness on huge images).
     */
    fun run(
        request: EnhanceRequest,
        outputNameFor: (index: Int, inputUri: String) -> String,
        format: OutputFormat = OutputFormat.PNG,
        maxMegapixels: Int = 48,
        sharpenMaxMegapixels: Double = 24.0,
        isCancelled: () -> Boolean = { false },
        onItemCompleted: (batchIndex: Int, result: EnhanceResult) -> Unit = { _, _ -> },
    ): Flow<JobProgress> = channelFlow {
        withContext(Dispatchers.Default) {
            val total = request.inputUris.size
            var succeeded = 0
            var failed = 0
            var outputUri: String? = null
            var lastError: String? = null
            var backendUsed = engine.backendName
            var skippedSmallFaces = 0
            val failedUris = mutableListOf<String>()

            for ((batchIndex, inputUri) in request.inputUris.withIndex()) {
                if (isCancelled() || !currentCoroutineContext().isActive) {
                    close(CancellationExceptionFromUser())
                    return@withContext
                }

                val preparing = JobProgress(ProcessStep.PREPARING, batchIndex = batchIndex, batchTotal = total)
                var lastOverall = preparing.overall
                val itemResult: EnhanceResult = try {
                    send(preparing)
                    val processed = processOne(
                        request = request,
                        inputUri = inputUri,
                        outputUri = outputNameFor(batchIndex, inputUri),
                        format = format,
                        maxMegapixels = maxMegapixels,
                        sharpenOutput = request.sharpen,
                        sharpenMaxMegapixels = sharpenMaxMegapixels,
                        batchIndex = batchIndex,
                        batchTotal = total,
                        isCancelled = isCancelled,
                    ) { progress ->
                        lastOverall = progress.overall
                        send(progress)
                    }
                    backendUsed = processed.backendUsed
                    skippedSmallFaces += processed.skippedSmallFaces
                    send(
                        JobProgress(
                            step = ProcessStep.BLENDING,
                            backendUsed = processed.backendUsed,
                            outputUri = processed.outputUri,
                            batchIndex = batchIndex,
                            batchTotal = total,
                            skippedSmallFaces = processed.skippedSmallFaces,
                            itemCompleted = true,
                        ),
                    )
                    if (outputUri == null) outputUri = processed.outputUri
                    succeeded++
                    EnhanceResult.Success(
                        outputUri = processed.outputUri,
                        width = processed.width,
                        height = processed.height,
                        backendUsed = processed.backendUsed,
                        skippedSmallFaces = processed.skippedSmallFaces,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    throw e
                } catch (t: Throwable) {
                    val message = t.message?.takeIf { it.isNotBlank() }
                        ?: t::class.java.simpleName
                        ?: "Enhancement failed"
                    backendUsed = engine.backendName
                    lastError = message
                    failed++
                    failedUris += inputUri
                    send(
                        JobProgress(
                            ProcessStep.PREPARING,
                            batchIndex = batchIndex,
                            batchTotal = total,
                            backendUsed = "error: ${message.take(80)}",
                            error = message,
                            itemCompleted = true,
                            overallOverride = lastOverall,
                        ),
                    )
                    EnhanceResult.Failure(message, t)
                }
                onItemCompleted(batchIndex, itemResult)

            }

            send(
                JobProgress(
                    ProcessStep.DONE,
                    batchIndex = total - 1,
                    batchTotal = total,
                    backendUsed = "$backendUsed; $succeeded ok, $failed failed",
                    outputUri = outputUri,
                    error = if (outputUri == null) lastError else null,
                    skippedSmallFaces = skippedSmallFaces,
                ),
            )
        }
    }

    /** Processes one image through the full tiled pipeline. Throws on failure. */
    private suspend inline fun processOne(
        request: EnhanceRequest,
        inputUri: String,
        outputUri: String,
        format: OutputFormat,
        maxMegapixels: Int,
        sharpenOutput: Boolean,
        sharpenMaxMegapixels: Double,
        batchIndex: Int,
        batchTotal: Int,
        crossinline isCancelled: () -> Boolean,
        emit: (JobProgress) -> Unit,
    ): ProcessedImage {
        val jobContext = currentCoroutineContext()
        val cancellationRequested = { isCancelled() || !jobContext.isActive }
        checkPassCancellation(cancellationRequested)
        val scale = request.scale.multiplier
        val dimensions = imageIo.measure(inputUri, maxMegapixels)
        val modelKey = when (request.mode) {
            EngineMode.PRECISION ->
                if (scale == 4) InferenceEngine.ModelKey.PRECISION_X4 else InferenceEngine.ModelKey.PRECISION_X2
            EngineMode.CREATIVE ->
                if (scale == 4) InferenceEngine.ModelKey.CREATIVE_X4 else InferenceEngine.ModelKey.CREATIVE_X2
        }
        if (request.cropPreset == null) {
            validateOutputBufferSize(
                dimensions.width.toLong() * scale,
                dimensions.height.toLong() * scale,
            )
        }

        val decoded = imageIo.decode(inputUri, maxMegapixels)
        checkPassCancellation(cancellationRequested)
        val working = request.cropPreset?.let { preset ->
            CropProcessor.centerCrop(RgbaImage(decoded.rgba, decoded.width, decoded.height), preset)
        } ?: RgbaImage(decoded.rgba, decoded.width, decoded.height)
        val outputBytes = outputBufferSize(
            working.width.toLong() * scale,
            working.height.toLong() * scale,
        )
        checkPassCancellation(cancellationRequested)
        val tiling = TilingManager(
            imageWidth = working.width,
            imageHeight = working.height,
            scale = scale,
            tileSize = tileConfig.tileSize,
            overlap = TilingManager.overlapFor(tileConfig.tileSize),
        )
        checkPassCancellation(cancellationRequested)
        val passProvider = modelProvider ?: NoModelProvider
        var restored = working
        val passStatuses = mutableListOf<String>()
        if (!request.denoise.isOff) {
            checkPassCancellation(cancellationRequested)
            val result = DenoisePass(
                strength = request.denoise.percent,
                isCancelled = cancellationRequested,
            ).apply(
                restored,
                PassContext(request.denoise.percent, passProvider),
            )
            checkPassCancellation(cancellationRequested)
            restored = result.image
            passStatuses += "denoise=${result.detail}"
        }
        if (request.deblurEnabled) {
            checkPassCancellation(cancellationRequested)
            val result = DeblurPass(
                strength = request.deblurStrength,
                isCancelled = cancellationRequested,
            ).apply(
                restored,
                PassContext(request.deblurStrength, passProvider),
            )
            checkPassCancellation(cancellationRequested)
            restored = result.image
            passStatuses += "deblur=${result.detail}"
        }
        if (request.scratchRepairEnabled) {
            checkPassCancellation(cancellationRequested)
            val result = ScratchRepairPass(
                strength = request.scratchRepairStrength,
                isCancelled = cancellationRequested,
            ).apply(
                restored,
                PassContext(request.scratchRepairStrength, passProvider),
            )
            checkPassCancellation(cancellationRequested)
            restored = result.image
            passStatuses += "scratch-repair=${result.detail}"
        }
        if (request.colorizeEnabled) {
            checkPassCancellation(cancellationRequested)
            val result = ColorizePass(
                strength = request.colorizeStrength,
                isCancelled = cancellationRequested,
            ).apply(
                restored,
                PassContext(request.colorizeStrength, passProvider),
            )
            checkPassCancellation(cancellationRequested)
            restored = result.image
            passStatuses += "colorize=${result.detail}"
        }
        checkPassCancellation(cancellationRequested)

        var out = ByteArray(outputBytes)
        var tilesDone = 0

        val modelScale = InferenceEngine.scaleFor(modelKey)
        for (tile in tiling.tiles()) {
            checkPassCancellation(cancellationRequested)

            val inPixels = tile.inW * tile.inH
            val inTile = ByteArray(inPixels * 4)
            copyTile(restored.pixels, restored.width, tile.inX, tile.inY, tile.inW, tile.inH, inTile)

            val inChw = TensorCodec.rgbaToChw(inTile, inPixels)
            var passW = tile.inW
            var passH = tile.inH
            var outChw = inChw
            var remainingScale = scale
            while (remainingScale > 1) {
                val passScale = minOf(modelScale, remainingScale)
                outChw = engine.upscaleTile(outChw, passW, passH, modelKey)
                passW *= passScale
                passH *= passScale
                remainingScale /= passScale
            }

            val tileOutW = tile.inW * scale
            val tileOutH = tile.inH * scale
            val tileRgba = TensorCodec.chwToRgba(outChw, tileOutW, tileOutH)
            TileBlender.blend(tileRgba, tile, tiling, out, tiling.outWidth)

            tilesDone++
            emit(
                JobProgress(
                    step = ProcessStep.PROCESSING_TILES,
                    tilesDone = tilesDone,
                    tilesTotal = tiling.tileCount,
                    backendUsed = backendWithPasses(engine.backendName, passStatuses),
                    batchIndex = batchIndex,
                    batchTotal = batchTotal,
                ),
            )
        }

        var skippedSmallFaces = 0
        if (request.faceRestoreEnabled) {
            checkPassCancellation(cancellationRequested)
            emit(
                JobProgress(
                    step = ProcessStep.DETECTING_FACES,
                    backendUsed = backendWithPasses(engine.backendName, passStatuses),
                    batchIndex = batchIndex,
                    batchTotal = batchTotal,
                ),
            )
            val faceResult = FaceRestorePass(
                strength = request.faceRestoreStrength,
                detector = faceDetector,
                restorer = faceRestorer,
                isCancelled = cancellationRequested,
            ).apply(
                RgbaImage(out, tiling.outWidth, tiling.outHeight),
                PassContext(request.faceRestoreStrength, passProvider),
            )
            checkPassCancellation(cancellationRequested)
            require(faceResult.image.width == tiling.outWidth && faceResult.image.height == tiling.outHeight)
            require(faceResult.image.pixels.size == outputBytes)
            out = faceResult.image.pixels
            skippedSmallFaces = faceResult.skippedSmallFaces
            passStatuses += "face-restore=${faceResult.detail}"
            emit(
                JobProgress(
                    step = ProcessStep.RESTORING_FACES,
                    backendUsed = backendWithPasses(engine.backendName, passStatuses),
                    batchIndex = batchIndex,
                    batchTotal = batchTotal,
                    skippedSmallFaces = skippedSmallFaces,
                ),
            )
        }

        // Stronger output: unsharp post-pass within memory-safe output size
        val outMp = tiling.outWidth.toLong() * tiling.outHeight / 1_000_000.0
        if (sharpenOutput && outMp <= sharpenMaxMegapixels) {
            ImageOps.unsharpMask(
                out,
                tiling.outWidth,
                tiling.outHeight,
                amount = 0.45f,
                isCancelled = cancellationRequested,
            )
        }
        checkPassCancellation(cancellationRequested)

        emit(
            JobProgress(
                step = ProcessStep.BLENDING,
                backendUsed = if (passStatuses.isEmpty()) null else backendWithPasses(engine.backendName, passStatuses),
                batchIndex = batchIndex,
                batchTotal = batchTotal,
                skippedSmallFaces = skippedSmallFaces,
            ),
        )
        checkPassCancellation(cancellationRequested)
        val encodedUri = imageIo.encode(out, tiling.outWidth, tiling.outHeight, outputUri, format, inputUri)
        require(encodedUri.isNotBlank()) { "encode returned a blank output URI" }
        return ProcessedImage(
            outputUri = encodedUri,
            width = tiling.outWidth,
            height = tiling.outHeight,
            backendUsed = backendWithPasses(engine.backendName, passStatuses),
            skippedSmallFaces = skippedSmallFaces,
        )
    }

    private fun backendWithPasses(backend: String, passStatuses: List<String>): String =
        if (passStatuses.isEmpty()) backend else "$backend; ${passStatuses.joinToString("; ")}"

    private data class ProcessedImage(
        val outputUri: String,
        val width: Int,
        val height: Int,
        val backendUsed: String,
        val skippedSmallFaces: Int,
    )

    private object NoModelProvider : ModelProvider {
        override fun load(key: InferenceEngine.ModelKey) = null
    }

    private fun copyTile(
        src: ByteArray,
        srcW: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        dst: ByteArray,
    ) {
        for (row in 0 until h) {
            System.arraycopy(src, ((y + row) * srcW + x) * 4, dst, row * w * 4, w * 4)
        }
    }

    private class CancellationExceptionFromUser :
        kotlinx.coroutines.CancellationException("cancelled by user")
}
