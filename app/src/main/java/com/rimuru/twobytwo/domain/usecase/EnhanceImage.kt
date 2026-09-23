package com.rimuru.twobytwo.domain.usecase

import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.TensorCodec
import com.rimuru.twobytwo.domain.engine.TileBlender
import com.rimuru.twobytwo.domain.engine.TilingManager
import com.rimuru.twobytwo.domain.model.Accelerator
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
 * The core pipeline: decode → (denoise is fused in-model) → tiled neural upscale
 * with seam blending → optional face pass → encode. PRD §5.4 / TC-3.
 *
 * Decode/encode are behind [ImageIo] so this stays unit-testable; the ONNX engine
 * is behind [InferenceEngine].
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

    /**
     * @param maxMegapixels input safety valve (FR-1.6, default 48 MP).
     * @param isCancelled polled between tiles for prompt cancellation.
     */
    fun run(
        request: EnhanceRequest,
        outputUri: String,
        format: OutputFormat = OutputFormat.PNG,
        maxMegapixels: Int = 48,
        isCancelled: () -> Boolean = { false },
    ): Flow<JobProgress> = channelFlow {
        withContext(Dispatchers.Default) {
            send(JobProgress(ProcessStep.PREPARING))

            val decoded = imageIo.decode(request.inputUri, maxMegapixels)
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
                if (isCancelled() || !currentCoroutineContext().isActive) {
                    close(CancellationExceptionFromUser())
                    return@withContext
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
                send(
                    JobProgress(
                        step = ProcessStep.PROCESSING_TILES,
                        tilesDone = tilesDone,
                        tilesTotal = tiling.tileCount,
                        backendUsed = engine.backendName,
                    ),
                )
            }

            if (request.faceRestoreEnabled) {
                send(JobProgress(ProcessStep.DETECTING_FACES))
                // Face restoration is M3 (PRD milestone). The step shows in UI now so
                // the pipeline shape is final; the face pass itself lands with the
                // GFPGAN/CodeFormer model in models/.
                // ponytail: no-op face pass; add FaceRestorerImpl call when model ships.
            }

            send(JobProgress(ProcessStep.BLENDING))
            imageIo.encode(out, tiling.outWidth, tiling.outHeight, outputUri, format, request.inputUri)
            send(JobProgress(ProcessStep.DONE))
        }
    }

    private class CancellationExceptionFromUser :
        kotlinx.coroutines.CancellationException("cancelled by user")

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
}
