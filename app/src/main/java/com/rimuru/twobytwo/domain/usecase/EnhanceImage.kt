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
        )
    }

    data class DecodedImage(val rgba: ByteArray, val width: Int, val height: Int) {
        val megapixels: Double get() = width.toLong() * height / 1_000_000.0
    }

    enum class OutputFormat { PNG, JPEG }

    data class TileConfig(val tileSize: Int = 256)

    data class BatchResult(val succeeded: Int, val failed: Int, val failedUris: List<String>)

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
    ): Flow<JobProgress> = channelFlow {
        withContext(Dispatchers.Default) {
            val total = request.inputUris.size
            var succeeded = 0
            var failed = 0
            val failedUris = mutableListOf<String>()

            for ((batchIndex, inputUri) in request.inputUris.withIndex()) {
                if (isCancelled() || !currentCoroutineContext().isActive) {
                    close(CancellationExceptionFromUser())
                    return@withContext
                }

                try {
                    send(JobProgress(ProcessStep.PREPARING, batchIndex = batchIndex, batchTotal = total))
                    processOne(request, inputUri, outputNameFor(batchIndex, inputUri), format, maxMegapixels, sharpenOutput = request.sharpen, sharpenMaxMegapixels, batchIndex, total) { progress ->
                        send(progress)
                    }
                    succeeded++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // Per-image isolation: log-and-continue, batch survives (batch stability)
                    failed++
                    failedUris += inputUri
                    send(
                        JobProgress(
                            ProcessStep.PREPARING,
                            batchIndex = batchIndex,
                            batchTotal = total,
                            backendUsed = "error: ${t.message?.take(80)}",
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
        emit: (JobProgress) -> Unit,
    ) {
        val decoded = imageIo.decode(inputUri, maxMegapixels)
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

        val tiling = TilingManager(
            imageWidth = decoded.width,
            imageHeight = decoded.height,
            scale = scale,
            tileSize = tileConfig.tileSize,
            overlap = TilingManager.overlapFor(tileConfig.tileSize),
        )

        // TC-3: the full-res output buffer is allocated exactly once.
        val out = ByteArray(tiling.outWidth * tiling.outHeight * 4)
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
        imageIo.encode(out, tiling.outWidth, tiling.outHeight, outputUri, format, inputUri)
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
