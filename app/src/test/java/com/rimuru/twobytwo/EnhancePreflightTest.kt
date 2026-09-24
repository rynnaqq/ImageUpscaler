package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancePreflightTest {

    @Test
    fun `output budget rejects before decoding or inference`() = runBlocking {
        var decodeCalled = false
        var encodeCalled = false
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(5_000, 5_000)

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
        assertTrue(progress.last().backendUsed?.contains("exceeds") == true)
    }
}
