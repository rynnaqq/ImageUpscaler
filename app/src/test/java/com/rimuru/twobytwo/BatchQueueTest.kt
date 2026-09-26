package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.work.EnhanceRequestJson
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.DenoiseStrength
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.ExportPolicy
import com.rimuru.twobytwo.domain.model.JobProgress
import com.rimuru.twobytwo.domain.model.ProcessStep
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import com.rimuru.twobytwo.presentation.BatchItemStatus
import com.rimuru.twobytwo.presentation.EnhanceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchQueueTest {

    @Test
    fun `worker JSON keeps all 50 picked uris in order`() {
        val uris = (0 until 50).map { "content://input/photo-$it" }

        val decoded = EnhanceRequestJson.decode(
            EnhanceRequestJson.encode(
                EnhanceRequest(
                    inputUris = uris,
                    denoise = DenoiseStrength.OFF,
                    sharpen = false,
                ),
            ),
        )

        assertEquals(uris, decoded?.inputUris)
    }

    @Test
    fun `queue initializes in order and progress uses zero-based indices`() {
        val uris = listOf("content://input/first", "content://input/second", "content://input/third")
        val queued = EnhanceViewModel.initialBatchItems(uris)

        assertEquals(uris, queued.map { it.uri })
        assertTrue(queued.all { it.status == BatchItemStatus.QUEUED })

        val first = EnhanceViewModel.updateBatchItems(
            queued,
            JobProgress(ProcessStep.PREPARING, batchIndex = 0, batchTotal = uris.size),
        )
        assertEquals(BatchItemStatus.PROCESSING, first[0].status)
        assertEquals(BatchItemStatus.QUEUED, first[1].status)

        val second = EnhanceViewModel.updateBatchItems(
            first,
            JobProgress(ProcessStep.PREPARING, batchIndex = 1, batchTotal = uris.size),
        )
        assertEquals(BatchItemStatus.SUCCEEDED, second[0].status)
        assertEquals(BatchItemStatus.PROCESSING, second[1].status)
        assertEquals(BatchItemStatus.QUEUED, second[2].status)
    }

    @Test
    fun `terminal outcome vector restores a skipped failure`() {
        val items = EnhanceViewModel.initialBatchItems(
            listOf("content://input/first", "content://input/second"),
        )

        val updated = EnhanceViewModel.applyBatchOutcomes(items, "FS")

        assertEquals(BatchItemStatus.FAILED, updated[0].status)
        assertEquals(BatchItemStatus.SUCCEEDED, updated[1].status)
    }

    @Test
    fun `failed item N does not prevent item N plus one`() = runBlocking {
        val decodedUris = mutableListOf<String>()
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(1, 1)

            override fun decode(uri: String, maxMegapixels: Int): EnhanceImage.DecodedImage {
                decodedUris += uri
                if (uri == "content://input/first") error("first image failed")
                return EnhanceImage.DecodedImage(ByteArray(4), 1, 1)
            }

            override fun encode(
                rgba: ByteArray,
                width: Int,
                height: Int,
                destinationUri: String,
                policy: ExportPolicy,
                exifSourceUri: String?,
            ): String = "content://output/$destinationUri"
        }
        val queued = EnhanceViewModel.initialBatchItems(
            listOf("content://input/first", "content://input/second"),
        )

        val progress = EnhanceImage(testEngine(), imageIo).run(
            request = EnhanceRequest(
                inputUris = queued.map { it.uri },
                denoise = DenoiseStrength.OFF,
                sharpen = false,
            ),
            outputNameFor = { index, _ -> "output-$index.png" },
        ).toList()

        assertEquals(
            listOf("content://input/first", "content://input/second"),
            decodedUris,
        )
        assertTrue(progress.any { it.batchIndex == 0 && it.itemCompleted && it.error == "first image failed" })
        assertTrue(progress.any { it.batchIndex == 1 && it.itemCompleted && it.error == null })
        val failure = progress.single { it.error == "first image failed" }
        assertEquals(0, failure.batchIndex)
        assertNull(failure.outputUri)
        assertTrue(progress.any { it.batchIndex == 1 })
        assertEquals(ProcessStep.DONE, progress.last().step)
        assertEquals("content://output/output-1.png", progress.last().outputUri)

        val failed = EnhanceViewModel.updateBatchItems(queued, failure)
        val next = EnhanceViewModel.updateBatchItems(
            failed,
            progress.first { it.batchIndex == 1 && it.error == null },
        )
        assertEquals(BatchItemStatus.FAILED, next[0].status)
        assertEquals(BatchItemStatus.PROCESSING, next[1].status)
    }

    @Test
    fun `cancellation stops before the next item`() {
        var cancelRequested = false
        var decodeCount = 0
        var encodeCount = 0
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(1, 1)

            override fun decode(uri: String, maxMegapixels: Int): EnhanceImage.DecodedImage {
                decodeCount++
                return EnhanceImage.DecodedImage(ByteArray(4), 1, 1)
            }

            override fun encode(
                rgba: ByteArray,
                width: Int,
                height: Int,
                destinationUri: String,
                policy: ExportPolicy,
                exifSourceUri: String?,
            ): String {
                encodeCount++
                cancelRequested = true
                return "content://output/$destinationUri"
            }
        }

        val emitted = mutableListOf<JobProgress>()
        val error = runCatching {
            runBlocking {
                EnhanceImage(testEngine(), imageIo).run(
                    request = EnhanceRequest(
                        inputUris = listOf("content://input/first", "content://input/second"),
                        denoise = DenoiseStrength.OFF,
                        sharpen = false,
                    ),
                    outputNameFor = { index, _ -> "output-$index.png" },
                    isCancelled = { cancelRequested },
                ).onEach { emitted += it }.toList()
            }
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(1, decodeCount)
        assertEquals(1, encodeCount)
        assertTrue(emitted.none { it.step == ProcessStep.DONE })
        assertTrue(emitted.none { it.step == ProcessStep.DONE && it.outputUri != null })
        assertTrue(emitted.any { it.itemCompleted && it.outputUri != null })
    }

    private fun testEngine(): InferenceEngine = object : InferenceEngine {
        override val backendName = "test"
        override fun isAvailable(accelerator: Accelerator) = true
        override fun close() = Unit

        override fun upscaleTile(
            input: FloatArray,
            tileWidth: Int,
            tileHeight: Int,
            modelKey: InferenceEngine.ModelKey,
        ): FloatArray = FloatArray(3 * tileWidth * tileHeight * 4)
    }
}
