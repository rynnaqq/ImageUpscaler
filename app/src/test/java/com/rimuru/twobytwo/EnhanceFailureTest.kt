package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.JobProgress
import com.rimuru.twobytwo.domain.model.ProcessStep
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhanceFailureTest {

    @Test
    fun `ordinary image failure is reported and batch continues`() = runBlocking {
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
                format: EnhanceImage.OutputFormat,
                exifSourceUri: String?,
            ): String = "content://output/$destinationUri"
        }

        val progress = EnhanceImage(testEngine(), imageIo).run(
            request = EnhanceRequest(
                inputUris = listOf("content://input/first", "content://input/second"),
            ),
            outputNameFor = { index, _ -> "output-$index.png" },
        ).toList()

        assertEquals(listOf("content://input/first", "content://input/second"), decodedUris)
        val failed = progress.single { it.error == "first image failed" }
        assertEquals(ProcessStep.PREPARING, failed.step)
        assertEquals(0, failed.batchIndex)
        assertNull(failed.outputUri)
        assertEquals(ProcessStep.DONE, progress.last().step)
        assertEquals("content://output/output-1.png", progress.last().outputUri)
        assertEquals(
            "test; denoise=classical denoise fallback (model execution deferred); 1 ok, 1 failed",
            progress.last().backendUsed,
        )
    }

    @Test
    fun `cancellation from inference terminates flow without done output`() {
        val emitted = mutableListOf<JobProgress>()
        var inferenceCalls = 0
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(1, 1)

            override fun decode(uri: String, maxMegapixels: Int) =
                EnhanceImage.DecodedImage(ByteArray(4), 1, 1)

            override fun encode(
                rgba: ByteArray,
                width: Int,
                height: Int,
                destinationUri: String,
                format: EnhanceImage.OutputFormat,
                exifSourceUri: String?,
            ): String = error("encode should not run")
        }
        val engine = object : InferenceEngine {
            override val backendName = "test"
            override fun isAvailable(accelerator: Accelerator) = true
            override fun close() = Unit

            override fun upscaleTile(
                input: FloatArray,
                tileWidth: Int,
                tileHeight: Int,
                modelKey: InferenceEngine.ModelKey,
            ): FloatArray {
                inferenceCalls++
                throw CancellationException("cancelled during inference")
            }
        }

        val error = try {
            runBlocking {
                EnhanceImage(engine, imageIo).run(
                    request = EnhanceRequest(
                        inputUris = listOf("content://input/first", "content://input/second"),
                    ),
                    outputNameFor = { index, _ -> "output-$index.png" },
                ).onEach { emitted += it }.toList()
            }
            null
        } catch (t: Throwable) {
            t
        }

        assertTrue(error is CancellationException)
        assertEquals(1, inferenceCalls)
        assertTrue(emitted.none { it.step == ProcessStep.DONE })
        assertTrue(emitted.none { it.outputUri != null })
    }

    @Test
    fun `out of memory from inference escapes without done output`() {
        val expected = OutOfMemoryError("simulated inference allocation failure")
        val emitted = mutableListOf<JobProgress>()
        var decodeCount = 0
        var inferenceCalls = 0
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
                format: EnhanceImage.OutputFormat,
                exifSourceUri: String?,
            ): String = error("encode should not run")
        }
        val engine = object : InferenceEngine {
            override val backendName = "test"
            override fun isAvailable(accelerator: Accelerator) = true
            override fun close() = Unit

            override fun upscaleTile(
                input: FloatArray,
                tileWidth: Int,
                tileHeight: Int,
                modelKey: InferenceEngine.ModelKey,
            ): FloatArray {
                inferenceCalls++
                throw expected
            }
        }

        val error = try {
            runBlocking {
                EnhanceImage(engine, imageIo).run(
                    request = EnhanceRequest(
                        inputUris = listOf("content://input/first", "content://input/second"),
                    ),
                    outputNameFor = { index, _ -> "output-$index.png" },
                ).onEach { emitted += it }.toList()
            }
            null
        } catch (t: Throwable) {
            t
        }

        assertSame(expected, error)
        assertEquals(1, decodeCount)
        assertEquals(1, inferenceCalls)
        assertTrue(emitted.none { it.step == ProcessStep.DONE })
        assertTrue(emitted.none { it.outputUri != null })
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
