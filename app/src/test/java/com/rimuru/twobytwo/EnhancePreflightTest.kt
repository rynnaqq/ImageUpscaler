package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.ScaleFactor
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancePreflightTest {

    @Test
    fun `technical buffer boundary rejects before decoding or inference`() = runBlocking {
        var decodeCalled = false
        var encodeCalled = false
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(20_000, 20_000)

            override fun decode(uri: String, maxMegapixels: Int): EnhanceImage.DecodedImage {
                decodeCalled = true
                return EnhanceImage.DecodedImage(ByteArray(4), 1, 1)
            }

            override fun encode(
                rgba: ByteArray,
                width: Int,
                height: Int,
                destinationUri: String,
                format: EnhanceImage.OutputFormat,
                exifSourceUri: String?,
            ): String {
                encodeCalled = true
                return "content://output/$destinationUri"
            }
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
            ): FloatArray = error("inference should not run")
        }

        val progress = EnhanceImage(engine, imageIo).run(
            request = EnhanceRequest(inputUris = listOf("content://input/photo")),
            outputNameFor = { _, _ -> "output.png" },
        ).toList()

        assertFalse(decodeCalled)
        assertFalse(encodeCalled)
        assertTrue(progress.last().outputUri == null)
        assertTrue(progress.any { it.backendUsed?.contains("JVM array limit") == true })
        assertTrue(progress.last().error?.contains("JVM array limit") == true)
    }

    @Test
    fun `output above legacy cap reaches decode before allocation`() = runBlocking {
        var decodeCalled = false
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(12_000, 2_000)

            override fun decode(uri: String, maxMegapixels: Int): EnhanceImage.DecodedImage {
                decodeCalled = true
                error("decode reached")
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
            ): FloatArray = error("inference should not run")
        }

        val progress = EnhanceImage(engine, imageIo).run(
            request = EnhanceRequest(
                inputUris = listOf("content://input/photo"),
                scale = ScaleFactor.X4,
            ),
            outputNameFor = { _, _ -> "output.png" },
        ).toList()

        assertTrue(decodeCalled)
        assertTrue(progress.last().error == "decode reached")
    }

    @Test
    fun `out of memory terminates batch before next image`() = runBlocking {
        var decodeCount = 0
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(1, 1)

            override fun decode(uri: String, maxMegapixels: Int): EnhanceImage.DecodedImage {
                decodeCount++
                throw OutOfMemoryError("simulated allocation failure")
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
            ): FloatArray = error("inference should not run")
        }

        val error = runCatching {
            EnhanceImage(engine, imageIo).run(
                request = EnhanceRequest(
                    inputUris = listOf(
                        "content://input/first",
                        "content://input/second",
                    ),
                ),
                outputNameFor = { index, _ -> "output-$index.png" },
            ).toList()
        }.exceptionOrNull()

        assertTrue(error is OutOfMemoryError)
        assertEquals(1, decodeCount)
    }
}
