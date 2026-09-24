package com.rimuru.twobytwo.domain.usecase

import com.rimuru.twobytwo.domain.engine.ImageOps
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.TensorCodec
import com.rimuru.twobytwo.domain.engine.TileBlender
import com.rimuru.twobytwo.domain.engine.TilingManager
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.EngineMode
import com.rimuru.twobytwo.domain.model.JobProgress
import com.rimuru.twobytwo.domain.model.ProcessStep
import com.rimuru.twobytwo.domain.model.ScaleFactor
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

        internal fun outputBufferSize(outWidth: Long, outHeight: Long, maxOutputMegapixels: Double): Int {
            require(outWidth > 0 && outHeight > 0) { "output dimensions must be positive" }
            require(maxOutputMegapixels.isFinite() && maxOutputMegapixels > 0.0) {
                "max output megapixels must be finite and positive"
            }
            require(outWidth <= Int.MAX_VALUE.toLong() && outHeight <= Int.MAX_VALUE.toLong()) {
                "output dimensions exceed JVM array limit"
            }
            val pixels = outWidth * outHeight
            require(pixels <= maxOutputMegapixels * 1_000_000.0) {
                "output ${pixels / 1_000_000.0} MP exceeds cap $maxOutputMegapixels MP"
            }
            require(pixels <= Int.MAX_VALUE.toLong() / 4L) { "output buffer exceeds JVM array limit" }
            return (pixels * 4L).toInt()
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
        maxOutputMegapixels: Double = DEFAULT_MAX_OUTPUT_MEGAPIXELS,
    ): Flow<JobProgress> = channelFlow {
        withContext(Dispatchers.Default) {
            val total = request.inputUris.size
            var succeeded = 0
            var failed = 0
            var outputUri: String? = null
            var lastError: String? = null
            val failedUris = mutableListOf<String>()

            for ((batchIndex, inputUri) in request.inputUris.withIndex()) {
                if (isCancelled() || !currentCoroutineContext().isActive) {
                    close(CancellationExceptionFromUser())
                    return@withContext
                }

                try {
                    send(JobProgress(ProcessStep.PREPARING, batchIndex = batchIndex, batchTotal = total))
                    val encodedUri = processOne(
                        request = request,
                        inputUri = inputUri,
                        outputUri = outputNameFor(batchIndex, inputUri),
                        format = format,
                        maxMegapixels = maxMegapixels,
                        sharpenOutput = request.sharpen,
                        sharpenMaxMegapixels = sharpenMaxMegapixels,
                        batchIndex = batchIndex,
                        batchTotal = total,
                        maxOutputMegapixels = maxOutputMegapixels,
                    ) { progress ->
                        send(progress)
                    }
                    if (outputUri == null) outputUri = encodedUri
                    succeeded++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // Per-image isolation: log-and-continue, batch survives (batch stability)
                    val message = t.message?.takeIf { it.isNotBlank() }
                        ?: t::class.java.simpleName
                        ?: "Enhancement failed"
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
                        ),
                    )
                }
            }

            send(
                JobProgress(
                    ProcessStep.DONE,
                    batchIndex = total - 1,
                    batchTotal = total,
                    backendUsed = "$succeeded ok, $failed failed",
                    outputUri = outputUri,
                    error = if (outputUri == null) lastError else null,
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
        maxOutputMegapixels: Double,
        emit: (JobProgress) -> Unit,
    ): String {
        val dimensions = imageIo.measure(inputUri, maxMegapixels)
        val scale = when (request.scale) {
            ScaleFactor.X2 -> 2
            ScaleFactor.X4 -> 4
        }
        val modelKey = when (request.mode) {
            EngineMode.PRECISION ->
                if (scale == 2) InferenceEngine.ModelKey.PRECISION_X2 else InferenceEngine.ModelKey.PRECISION_X4
            EngineMode.CREATIVE ->
                if (scale == 2) InferenceEngine.ModelKey.CREATIVE_X2 else InferenceEngine.ModelKey.CREATIVE_X4
        }

        val outputBytes = outputBufferSize(
            dimensions.width.toLong() * scale,
            dimensions.height.toLong() * scale,
            maxOutputMegapixels,
        )
        val tiling = TilingManager(
            imageWidth = dimensions.width,
            imageHeight = dimensions.height,
            scale = scale,
            tileSize = tileConfig.tileSize,
            overlap = TilingManager.overlapFor(tileConfig.tileSize),
        )
        val decoded = imageIo.decode(inputUri, maxMegapixels)
        val out = ByteArray(outputBytes)
        var tilesDone = 0

        val modelScale = InferenceEngine.scaleFor(modelKey)
        for (tile in tiling.tiles()) {
            if (currentCoroutineContext().isActive.not()) {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }

            val inPixels = tile.inW * tile.inH
            val inTile = ByteArray(inPixels * 4)
            copyTile(decoded.rgba, decoded.width, tile.inX, tile.inY, tile.inW, tile.inH, inTile)

            val inChw = TensorCodec.rgbaToChw(inTile, inPixels)
            var outChw = engine.upscaleTile(inChw, tile.inW, tile.inH, modelKey)

            // FR-1.4: 4× via chained 2× if the model is a 2× model
            if (modelScale != scale) {
                val midW = tile.inW * 2
                val midH = tile.inH * 2
                val nextKey = if (request.mode == EngineMode.PRECISION) {
                    InferenceEngine.ModelKey.PRECISION_X2
                } else {
                    InferenceEngine.ModelKey.CREATIVE_X2
                }
                outChw = engine.upscaleTile(outChw, midW, midH, nextKey)
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
                    backendUsed = engine.backendName,
                    batchIndex = batchIndex,
                    batchTotal = batchTotal,
                ),
            )
        }

        // Stronger output: unsharp post-pass within memory-safe output size
        val outMp = tiling.outWidth.toLong() * tiling.outHeight / 1_000_000.0
        if (sharpenOutput && outMp <= sharpenMaxMegapixels) {
            ImageOps.unsharpMask(out, tiling.outWidth, tiling.outHeight, amount = 0.45f)
        }

        emit(JobProgress(ProcessStep.BLENDING, batchIndex = batchIndex, batchTotal = batchTotal))
        return imageIo.encode(out, tiling.outWidth, tiling.outHeight, outputUri, format, inputUri)
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
