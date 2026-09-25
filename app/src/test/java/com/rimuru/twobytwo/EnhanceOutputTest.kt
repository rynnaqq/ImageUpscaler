package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.InferenceEngine.ModelKey
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.ExportPolicy
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EnhanceOutputTest {

    @Test
    fun `completed job carries encoded output uri`() = runBlocking {
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(2, 2)

            override fun decode(uri: String, maxMegapixels: Int) =
                EnhanceImage.DecodedImage(ByteArray(2 * 2 * 4) { 0x7F }, 2, 2)

            override fun encode(
                rgba: ByteArray,
                width: Int,
                height: Int,
                destinationUri: String,
                policy: ExportPolicy,
                exifSourceUri: String?,
            ): String = "content://output/$destinationUri"
        }
        val engine = object : InferenceEngine {
            override val backendName = "test"
            override fun isAvailable(accelerator: Accelerator) = true
            override fun close() = Unit

            override fun upscaleTile(
                input: FloatArray,
                tileWidth: Int,
                tileHeight: Int,
                modelKey: ModelKey,
            ): FloatArray {
                val outputWidth = tileWidth * 2
                val outputHeight = tileHeight * 2
                val inputPlane = tileWidth * tileHeight
                val outputPlane = outputWidth * outputHeight
                return FloatArray(3 * outputPlane).also { output ->
                    for (y in 0 until outputHeight) {
                        for (x in 0 until outputWidth) {
                            val sourceX = (x / 2).coerceAtMost(tileWidth - 1)
                            val sourceY = (y / 2).coerceAtMost(tileHeight - 1)
                            val source = sourceY * tileWidth + sourceX
                            val target = y * outputWidth + x
                            output[target] = input[source]
                            output[outputPlane + target] = input[inputPlane + source]
                            output[2 * outputPlane + target] = input[2 * inputPlane + source]
                        }
                    }
                }
            }
        }

        val progress = EnhanceImage(engine, imageIo).run(
            request = EnhanceRequest(inputUris = listOf("content://input/photo")),
            outputNameFor = { _, _ -> "output.png" },
        ).toList()

        assertEquals("content://output/output.png", progress.last().outputUri)
    }
}
