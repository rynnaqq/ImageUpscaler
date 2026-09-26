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
import com.rimuru.twobytwo.domain.engine.StreamingTileWriter
import com.rimuru.twobytwo.domain.engine.TensorCodec
import com.rimuru.twobytwo.domain.engine.TileBlender
import com.rimuru.twobytwo.domain.engine.TilingManager
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.EnhanceResult
import com.rimuru.twobytwo.domain.model.EngineMode
import com.rimuru.twobytwo.domain.model.ExportPolicy
import com.rimuru.twobytwo.domain.model.JobProgress
import com.rimuru.twobytwo.domain.model.ProcessStep
import com.rimuru.twobytwo.domain.model.ScaleFactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

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
    private val streamingImageIo: StreamingImageIo? = imageIo as? StreamingImageIo,
    private val streamingScratchDirectory: File = File(System.getProperty("java.io.tmpdir") ?: "."),
    private val tileWindowFor: (TilingManager, Long) -> Int = Companion::defaultTileWindow,
) {
    interface ImageIo {
        fun measure(uri: String, maxMegapixels: Int): Dimensions

        /** Decode input to RGBA_8888 bytes; returns dims. */
        fun decode(uri: String, maxMegapixels: Int): DecodedImage

        /**
         * Decode using dimensions the caller already measured. Measuring is not free
         * — on a content:// uri it opens the stream twice and parses Exif — so the
         * pipeline hands back what it got from [measure] instead of paying for it
         * twice. Implementations that cannot use the hint fall back to measuring.
         */
        fun decode(uri: String, maxMegapixels: Int, dimensions: Dimensions): DecodedImage =
            decode(uri, maxMegapixels)

        /** Encode RGBA to PNG or JPEG(q>=95) at destination, copying Exif. */
        fun encode(
            rgba: ByteArray,
            width: Int,
            height: Int,
            destinationUri: String,
            policy: ExportPolicy,
            exifSourceUri: String?,
        ): String
    }

    data class DecodedImage(val rgba: ByteArray, val width: Int, val height: Int) {
        val megapixels: Double get() = width.toLong() * height / 1_000_000.0
    }

    /**
     * @param orientation raw Exif orientation tag value (1 when upright/absent).
     *   Carried so decode can honour rotation without re-reading the Exif block.
     */
    data class Dimensions(val width: Int, val height: Int, val orientation: Int = 1)

    data class TileConfig(val tileSize: Int = 256)

    data class BatchResult(val succeeded: Int, val failed: Int, val failedUris: List<String>)

    companion object {
        internal const val DEFAULT_MAX_OUTPUT_MEGAPIXELS = 256.0
        internal const val STREAMING_OUTPUT_MIN_PIXELS = 64_000_000L
        private const val STREAMING_PNG_BYTES_PER_PIXEL = 5L
        private const val STREAMING_CODEC_MARGIN_BYTES = 1L * 1024L * 1024L
        private const val STREAMING_SAFETY_MARGIN_BYTES = 16L * 1024L * 1024L

        /** Tiles in flight is capped so a big job cannot turn a slow run into an OOM. */
        internal const val MAX_TILE_WINDOW = 4

        /** Share of the heap the in-flight tiles may claim between them. */
        internal const val TILE_WINDOW_HEAP_FRACTION = 0.25

        private const val CHANNELS = 3
        private const val BYTES_PER_FLOAT = 4
        private const val RGBA_BYTES_PER_PIXEL = 4

        /**
         * Peak bytes one in-flight tile holds: the CHW float output the engine
         * produces, plus the RGBA byte buffer handed to the blender.
         */
        internal fun tileInferenceBytes(outputPixels: Long): Long =
            outputPixels * (CHANNELS * BYTES_PER_FLOAT + RGBA_BYTES_PER_PIXEL)

        /**
         * How many tiles to keep in flight. Bounded by the heap as well as the core
         * count, because a 512 px tile at x4 is ~64 MB while it is being inferred —
         * four of those on a small device is the difference between faster and an
         * OutOfMemoryError. Always at least 1, which is the old sequential behaviour.
         *
         * One core is deliberately left free: the consumer still has to blend, sharpen
         * and encode while the window infers.
         */
        internal fun tileWindow(heapBytes: Long, bytesPerTile: Long, cores: Int): Int {
            if (bytesPerTile <= 0L) return 1
            val byMemory = (heapBytes / bytesPerTile).coerceAtLeast(1L)
            val byCores = (cores - 1).coerceAtLeast(1).toLong()
            return minOf(byMemory, byCores)
                .coerceIn(1L, MAX_TILE_WINDOW.toLong())
                .toInt()
        }

        /**
         * The window for one image, from the heap this process actually has. Injectable
         * via the constructor so tests can force 1 (the old sequential path) or a wide
         * window and compare the two byte for byte.
         */
        internal fun defaultTileWindow(tiling: TilingManager, outputPixels: Long): Int {
            val heap = Runtime.getRuntime().maxMemory()
            val budget = (heap.toDouble() * TILE_WINDOW_HEAP_FRACTION).toLong()
            val perTile = tileInferenceBytes(outputPixels / tiling.tiles().size.coerceAtLeast(1))
            return tileWindow(budget, perTile, Runtime.getRuntime().availableProcessors())
        }

        private data class ScratchGroup(
            val startY: Long,
            val endY: Long,
            val bytes: Long,
        )

        internal fun streamingScratchBytes(
            tiles: List<TilingManager.Tile>,
            scale: Int,
            outputWidth: Long,
            outputHeight: Long,
        ): Long {
            require(scale > 0) { "streaming scale must be positive" }
            require(outputWidth > 0L && outputHeight > 0L) { "streaming output dimensions must be positive" }
            require(tiles.isNotEmpty()) { "streaming tile plan must not be empty" }
            val scaleLong = scale.toLong()
            val groups = tiles.groupBy { it.row }.values.map { rowTiles ->
                var startY = Long.MAX_VALUE
                var endY = 0L
                var bytes = 0L
                rowTiles.forEach { tile ->
                    require(tile.inY >= 0 && tile.inW > 0 && tile.inH > 0) { "invalid streaming tile dimensions" }
                    val tileWidth = Math.multiplyExact(tile.inW.toLong(), scaleLong)
                    val tileHeight = Math.multiplyExact(tile.inH.toLong(), scaleLong)
                    bytes = Math.addExact(
                        bytes,
                        Math.multiplyExact(Math.multiplyExact(tileWidth, tileHeight), 4L),
                    )
                    val tileStartY = Math.multiplyExact(tile.inY.toLong(), scaleLong)
                    val tileEndY = Math.multiplyExact(
                        Math.addExact(tile.inY.toLong(), tile.inH.toLong()),
                        scaleLong,
                    )
                    startY = minOf(startY, tileStartY)
                    endY = maxOf(endY, tileEndY)
                }
                ScratchGroup(startY, endY, bytes)
            }
            require(groups.all { it.startY >= 0L && it.startY < it.endY && it.endY <= outputHeight }) {
                "streaming tile plan exceeds output dimensions"
            }
            require(
                groups.zipWithNext().all { (previous, next) ->
                    previous.startY <= next.startY && previous.endY <= next.endY
                },
            ) {
                "streaming tile rows are not ordered"
            }
            var maxSpoolBytes = 0L
            var activeBytes = 0L
            var nextEndingGroup = 0
            groups.forEach { group ->
                while (nextEndingGroup < groups.size && groups[nextEndingGroup].endY <= group.startY) {
                    activeBytes = Math.subtractExact(activeBytes, groups[nextEndingGroup].bytes)
                    nextEndingGroup++
                }
                activeBytes = Math.addExact(activeBytes, group.bytes)
                maxSpoolBytes = maxOf(maxSpoolBytes, activeBytes)
            }
            val pngBytes = Math.multiplyExact(
                Math.multiplyExact(outputWidth, outputHeight),
                STREAMING_PNG_BYTES_PER_PIXEL,
            )
            return Math.addExact(
                Math.addExact(maxSpoolBytes, pngBytes),
                Math.addExact(STREAMING_CODEC_MARGIN_BYTES, STREAMING_SAFETY_MARGIN_BYTES),
            )
        }

        internal fun shouldStreamOutput(scale: ScaleFactor, width: Long, height: Long): Boolean =
            (scale == ScaleFactor.X4 || scale == ScaleFactor.X8) &&
                width in 1..Int.MAX_VALUE.toLong() &&
                height in 1..Int.MAX_VALUE.toLong() &&
                width * height >= STREAMING_OUTPUT_MIN_PIXELS

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

        private fun validateStreamingOutputDimensions(outWidth: Long, outHeight: Long) {
            require(outWidth in 1..Int.MAX_VALUE.toLong()) { "output width must fit Int" }
            require(outHeight in 1..Int.MAX_VALUE.toLong()) { "output height must fit Int" }
            val rowSize = Math.multiplyExact(outWidth, 4L)
            require(rowSize <= Int.MAX_VALUE.toLong()) { "output row buffer exceeds JVM array limit" }
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
                if (itemResult is EnhanceResult.Success) {
                    send(
                        JobProgress(
                            step = ProcessStep.BLENDING,
                            backendUsed = itemResult.backendUsed,
                            outputUri = itemResult.outputUri,
                            batchIndex = batchIndex,
                            batchTotal = total,
                            skippedSmallFaces = itemResult.skippedSmallFaces,
                            itemCompleted = true,
                        ),
                    )
                    if (outputUri == null) outputUri = itemResult.outputUri
                    succeeded++
                }

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
    private suspend fun processOne(
        request: EnhanceRequest,
        inputUri: String,
        outputUri: String,
        maxMegapixels: Int,
        sharpenOutput: Boolean,
        sharpenMaxMegapixels: Double,
        batchIndex: Int,
        batchTotal: Int,
        isCancelled: () -> Boolean,
        emit: suspend (JobProgress) -> Unit,
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
            val measuredOutputWidth = dimensions.width.toLong() * scale
            val measuredOutputHeight = dimensions.height.toLong() * scale
            if (streamingImageIo != null && shouldStreamOutput(request.scale, measuredOutputWidth, measuredOutputHeight)) {
                validateStreamingOutputDimensions(measuredOutputWidth, measuredOutputHeight)
            } else {
                outputBufferSize(measuredOutputWidth, measuredOutputHeight)
            }
        }

        val decoded = imageIo.decode(inputUri, maxMegapixels, dimensions)
        checkPassCancellation(cancellationRequested)
        val working = request.cropPreset?.let { preset ->
            CropProcessor.centerCrop(RgbaImage(decoded.rgba, decoded.width, decoded.height), preset)
        } ?: RgbaImage(decoded.rgba, decoded.width, decoded.height)
        val outputWidth = working.width.toLong() * scale
        val outputHeight = working.height.toLong() * scale
        val streamOutput = streamingImageIo != null &&
            shouldStreamOutput(request.scale, outputWidth, outputHeight)
        if (streamOutput) {
            validateStreamingOutputDimensions(outputWidth, outputHeight)
        }
        val outputBytes = if (streamOutput) 0 else outputBufferSize(outputWidth, outputHeight)
        checkPassCancellation(cancellationRequested)
        val tiling = TilingManager(
            imageWidth = working.width,
            imageHeight = working.height,
            scale = scale,
            tileSize = tileConfig.tileSize,
            overlap = TilingManager.overlapFor(tileConfig.tileSize),
        )
        checkPassCancellation(cancellationRequested)
        val passProvider = if (request.useNeuralEngine) modelProvider ?: NoModelProvider else NoModelProvider
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

        val modelScale = InferenceEngine.scaleFor(modelKey)
        var skippedSmallFaces = 0
        val outMp = outputWidth * outputHeight / 1_000_000.0
        val sharpenTiles = sharpenOutput && (streamOutput || outMp <= sharpenMaxMegapixels)

        if (streamOutput) {
            val streamingIo = checkNotNull(streamingImageIo)
            val tiles = tiling.tiles()
            streamingIo.checkScratchCapacity(
                streamingScratchBytes(
                    tiles = tiles,
                    scale = tiling.scale,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                ),
            )
            if (request.faceRestoreEnabled) {
                checkPassCancellation(cancellationRequested)
                emit(
                    JobProgress(
                        step = ProcessStep.DETECTING_FACES,
                        backendUsed = backendWithPasses(engine.backendName, passStatuses),
                        batchIndex = batchIndex,
                        batchTotal = batchTotal,
                        overallOverride = JobProgress(
                            step = ProcessStep.PROCESSING_TILES,
                            tilesTotal = tiling.tileCount,
                            batchIndex = batchIndex,
                            batchTotal = batchTotal,
                        ).overall,
                    ),
                )
            }

            var faceRestoreDetail: String? = null
            var faceRestoreStatusIndex = -1
            var nextTileIndex = 0
            var tilesDone = 0
            var rowsDone = 0
            val rowSize = Math.toIntExact(Math.multiplyExact(outputWidth, 4L))
            val writer = StreamingTileWriter(tiles, tiling, streamingScratchDirectory)
            val window = tileWindowFor(tiling, outputWidth * outputHeight)
            val pendingTiles = ArrayDeque<Pair<TilingManager.Tile, Deferred<Result<InferredTile>>>>()
            val tileScope = CoroutineScope(currentCoroutineContext())
            val tileDispatcher = tileDispatcher(window)
            fun launchTile(tile: TilingManager.Tile) {
                pendingTiles.addLast(
                    tile to tileScope.async(tileDispatcher) {
                        guard { inferTile(restored, scale, modelKey, modelScale, tile, cancellationRequested) }
                    },
                )
            }
            launchTile(tiles[nextTileIndex++])
            var processingFailure: Throwable? = null
            val encodedUri = try {
                val encoded = streamingIo.encodeStreaming(
                    width = outputWidth.toInt(),
                    height = outputHeight.toInt(),
                    destinationUri = outputUri,
                    policy = request.exportPolicy,
                    exifSourceUri = inputUri,
                ) { rowBuffer ->
                    checkPassCancellation(cancellationRequested)
                    require(rowBuffer.size.toLong() == rowSize.toLong()) { "streaming row buffer size mismatch" }
                    check(rowsDone < tiling.outHeight) { "streaming row count exceeds output height" }
                    while (!writer.isRowReady(rowsDone)) {
                        check(nextTileIndex < tiles.size || pendingTiles.isNotEmpty()) {
                            "streaming row is not ready before all tiles were accepted"
                        }
                        if (pendingTiles.isEmpty()) launchTile(tiles[nextTileIndex++])
                        val (currentTile, deferred) = pendingTiles.removeFirst()
                        val inferred = deferred.await().getOrElse { throw it }
                        tilesDone++
                        var preparedRgba = inferred.rgba
                        if (request.faceRestoreEnabled) {
                            val faceResult = FaceRestorePass(
                                strength = request.faceRestoreStrength,
                                detector = faceDetector,
                                restorer = faceRestorer,
                                isCancelled = cancellationRequested,
                            ).apply(
                                RgbaImage(preparedRgba, inferred.width, inferred.height),
                                PassContext(request.faceRestoreStrength, passProvider),
                            )
                            checkPassCancellation(cancellationRequested)
                            require(faceResult.image.width == inferred.width && faceResult.image.height == inferred.height)
                            require(
                                faceResult.image.pixels.size.toLong() ==
                                    inferred.width.toLong() * inferred.height.toLong() * 4L,
                            )
                            preparedRgba = faceResult.image.pixels
                            skippedSmallFaces += faceResult.skippedSmallFaces
                            if (faceRestoreStatusIndex < 0) {
                                faceRestoreDetail = faceResult.detail
                                passStatuses += "face-restore=${faceResult.detail}"
                                faceRestoreStatusIndex = passStatuses.lastIndex
                            }
                        }
                        if (sharpenTiles) {
                            ImageOps.unsharpMask(
                                preparedRgba,
                                inferred.width,
                                inferred.height,
                                amount = 0.45f,
                                isCancelled = cancellationRequested,
                            )
                        }
                        writer.accept(preparedRgba, currentTile)
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
                        if (nextTileIndex < tiles.size && pendingTiles.size < window) {
                            launchTile(tiles[nextTileIndex++])
                        }
                    }
                    writer.writeRow(rowBuffer, rowsDone)
                    rowsDone++
                }
                check(rowsDone == tiling.outHeight) { "streaming encoder did not consume all rows" }
                writer.finish()
                encoded
            } catch (error: Throwable) {
                processingFailure = error
                throw error
            } finally {
                try {
                    writer.close()
                } catch (closeFailure: Throwable) {
                    val failure = processingFailure
                    if (failure == null) throw closeFailure
                    failure.addSuppressed(closeFailure)
                }
            }
            if (request.faceRestoreEnabled) {
                var detail = checkNotNull(faceRestoreDetail)
                if (detail.startsWith("face restoration seam completed")) {
                    detail = "face restoration seam completed; skippedSmallFaces=$skippedSmallFaces"
                }
                passStatuses[faceRestoreStatusIndex] = "face-restore=$detail"
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
            require(encodedUri.isNotBlank()) { "encode returned a blank output URI" }
            return ProcessedImage(
                outputUri = encodedUri,
                width = tiling.outWidth,
                height = tiling.outHeight,
                backendUsed = backendWithPasses(engine.backendName, passStatuses),
                skippedSmallFaces = skippedSmallFaces,
            )
        }

        var out = ByteArray(outputBytes)
        val tiles = tiling.tiles()
        val window = tileWindowFor(tiling, outputWidth * outputHeight)
        var tilesDone = 0
        forEachOrderedWindowed(
            items = tiles,
            window = window,
            dispatcher = tileDispatcher(window),
            produce = { tile -> inferTile(restored, scale, modelKey, modelScale, tile, cancellationRequested) },
        ) { tile, inferred ->
            tilesDone++
            TileBlender.blend(inferred.rgba, tile, tiling, out, tiling.outWidth)
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

        if (sharpenTiles) {
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
        val encodedUri = imageIo.encode(
            out,
            tiling.outWidth,
            tiling.outHeight,
            outputUri,
            request.exportPolicy,
            inputUri,
        )
        require(encodedUri.isNotBlank()) { "encode returned a blank output URI" }
        return ProcessedImage(
            outputUri = encodedUri,
            width = tiling.outWidth,
            height = tiling.outHeight,
            backendUsed = backendWithPasses(engine.backendName, passStatuses),
            skippedSmallFaces = skippedSmallFaces,
        )
    }

    /** One upscaled tile, ready to blend. Holds no reference to the source image. */
    private class InferredTile(val rgba: ByteArray, val width: Int, val height: Int)

    /**
     * The expensive half of a tile: cut it out, run the neural passes, convert back.
     * Deliberately free of ordering so it can run on any thread — everything that
     * depends on tile order happens in the consumer.
     */
    private fun inferTile(
        restored: RgbaImage,
        scale: Int,
        modelKey: InferenceEngine.ModelKey,
        modelScale: Int,
        tile: TilingManager.Tile,
        isCancelled: () -> Boolean,
    ): InferredTile {
        checkPassCancellation(isCancelled)

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
        return InferredTile(TensorCodec.chwToRgba(outChw, tileOutW, tileOutH), tileOutW, tileOutH)
    }

    /**
     * Runs [produce] over [items] with at most [window] results alive at once, then
     * hands them to [consume] strictly in the original order.
     *
     * Order is the whole point. TileBlender blends each tile's feathered ramp against
     * what neighbouring tiles already wrote, and StreamingTileWriter rejects a tile
     * that is not the one it expects next — so consuming out of order would corrupt
     * every seam. Inferring in parallel is safe; blending stays exactly as it was.
     *
     * Failures are captured rather than thrown on the worker so that one bad tile
     * cannot cancel its siblings, then rethrown on the consumer — which keeps the
     * existing per-image error handling and the OOM/cancellation contract intact.
     */
    private suspend fun <I, O> forEachOrderedWindowed(
        items: List<I>,
        window: Int,
        dispatcher: CoroutineDispatcher,
        produce: (I) -> O,
        consume: suspend (I, O) -> Unit,
    ) {
        if (items.isEmpty()) return
        val scope = CoroutineScope(currentCoroutineContext())
        val pending = ArrayDeque<Deferred<Result<O>>>()
        var launched = 0

        fun launch(item: I) {
            pending.addLast(scope.async(dispatcher) { guard { produce(item) } })
        }

        repeat(minOf(window, items.size)) {
            launch(items[launched])
            launched++
        }
        for (item in items) {
            val outcome = pending.removeFirst().await()
            consume(item, outcome.getOrElse { throw it })
            if (launched < items.size) {
                launch(items[launched])
                launched++
            }
        }
    }

    private inline fun <T> guard(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }

    private fun tileDispatcher(window: Int): CoroutineDispatcher =
        if (window > 1) Dispatchers.Default.limitedParallelism(window) else Dispatchers.Default

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
