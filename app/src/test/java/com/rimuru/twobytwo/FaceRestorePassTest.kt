package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.FaceBox
import com.rimuru.twobytwo.domain.engine.FaceDetector
import com.rimuru.twobytwo.domain.engine.FaceRestorePass
import com.rimuru.twobytwo.domain.engine.FaceRestorer
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.ModelHandle
import com.rimuru.twobytwo.domain.engine.ModelProvider
import com.rimuru.twobytwo.domain.engine.PassContext
import com.rimuru.twobytwo.domain.engine.RgbaImage
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.DenoiseStrength
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.ExportPolicy
import com.rimuru.twobytwo.domain.model.ProcessStep
import com.rimuru.twobytwo.domain.model.ScaleFactor
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceRestorePassTest {

    @Test
    fun `small faces are skipped and the large face is restored without changing dimensions or alpha`() {
        val image = solidImage(128, 64, 20, 30, 40, 173)
        val original = image.pixels.copyOf()
        val largeFace = FaceBox(8, 8, 64, 48)
        val smallFace = FaceBox(88, 8, 32, 32)
        val detector = object : FaceDetector {
            override fun detect(source: RgbaImage): List<FaceBox> = listOf(largeFace, smallFace)
        }
        val crops = mutableListOf<Pair<Int, Int>>()
        val restorer = object : FaceRestorer {
            override fun restore(crop: RgbaImage): RgbaImage {
                crops += crop.width to crop.height
                val restoredWidth = crop.width / 2
                val restoredHeight = crop.height / 2
                return RgbaImage(
                    ByteArray(restoredWidth * restoredHeight * 4) { index -> if (index % 4 == 3) 0.toByte() else 200.toByte() },
                    restoredWidth,
                    restoredHeight,
                )
            }
        }

        val result = FaceRestorePass(
            strength = 100,
            detector = detector,
            restorer = restorer,
        ).apply(image, PassContext(100, verifiedModels()))

        assertSame(image, result.image)
        assertEquals(image.width, result.image.width)
        assertEquals(image.height, result.image.height)
        assertFalse(result.usedFallback)
        assertEquals(1, result.skippedSmallFaces)
        assertEquals(listOf(80 to 64), crops)
        for (index in original.indices step 4) {
            assertEquals(original[index + 3], result.image.pixels[index + 3])
        }
        for (y in smallFace.y until smallFace.y + smallFace.height) {
            for (x in smallFace.x until smallFace.x + smallFace.width) {
                val index = (y * image.width + x) * 4
                for (channel in 0..3) {
                    assertEquals(original[index + channel], result.image.pixels[index + channel])
                }
            }
        }
        val center = ((largeFace.y + largeFace.height / 2) * image.width + largeFace.x + largeFace.width / 2) * 4
        assertNotEquals(original[center], result.image.pixels[center])
    }

    @Test
    fun `strength zero preserves the input reference without invoking seams`() {
        val image = solidImage(64, 64, 10, 20, 30, 40)
        var detectorCalls = 0
        var restorerCalls = 0
        val detector = object : FaceDetector {
            override fun detect(source: RgbaImage): List<FaceBox> {
                detectorCalls++
                return emptyList()
            }
        }
        val restorer = object : FaceRestorer {
            override fun restore(crop: RgbaImage): RgbaImage {
                restorerCalls++
                return crop
            }
        }

        val result = FaceRestorePass(
            strength = 100,
            detector = detector,
            restorer = restorer,
        ).apply(image, PassContext(0, verifiedModels()))

        assertSame(image.pixels, result.image.pixels)
        assertFalse(result.usedFallback)
        assertEquals(0, detectorCalls)
        assertEquals(0, restorerCalls)
    }

    @Test
    fun `missing detector seam reports fallback without changing pixels`() {
        val image = solidImage(64, 64, 10, 20, 30, 40)
        val pixels = image.pixels.copyOf()

        val result = FaceRestorePass(strength = 100).apply(image, PassContext(100, verifiedModels()))

        assertSame(image.pixels, result.image.pixels)
        assertTrue(result.image.pixels.contentEquals(pixels))
        assertTrue(result.usedFallback)
        assertTrue(result.detail.contains("fallback"))
        assertTrue(result.detail.contains("detector"))
    }

    @Test
    fun `cosine feather leaves the face edge unchanged and changes the center`() {
        val image = solidImage(64, 64, 20, 30, 40, 173)
        val face = FaceBox(8, 8, 48, 48)
        val detector = object : FaceDetector {
            override fun detect(source: RgbaImage): List<FaceBox> = listOf(face)
        }
        val restorer = object : FaceRestorer {
            override fun restore(crop: RgbaImage): RgbaImage = RgbaImage(
                ByteArray(crop.pixels.size) { index -> if (index % 4 == 3) 0.toByte() else 220.toByte() },
                crop.width,
                crop.height,
            )
        }
        val original = image.pixels.copyOf()

        val result = FaceRestorePass(100, detector, restorer)
            .apply(image, PassContext(100, verifiedModels()))

        val edge = (face.y * image.width + face.x) * 4
        val center = ((face.y + face.height / 2) * image.width + face.x + face.width / 2) * 4
        assertEquals(original[edge], result.image.pixels[edge])
        assertNotEquals(original[center], result.image.pixels[center])
    }

    @Test
    fun `higher strength blends more of the restored crop`() {
        val detector = object : FaceDetector {
            override fun detect(source: RgbaImage): List<FaceBox> = listOf(FaceBox(8, 8, 48, 48))
        }
        val restorer = object : FaceRestorer {
            override fun restore(crop: RgbaImage): RgbaImage = RgbaImage(
                ByteArray(crop.pixels.size) { index -> if (index % 4 == 3) 0.toByte() else 200.toByte() },
                crop.width,
                crop.height,
            )
        }
        val low = FaceRestorePass(25, detector, restorer)
            .apply(solidImage(64, 64, 20, 30, 40, 173), PassContext(25, verifiedModels()))
        val high = FaceRestorePass(100, detector, restorer)
            .apply(solidImage(64, 64, 20, 30, 40, 173), PassContext(100, verifiedModels()))
        val center = ((32 * 64) + 32) * 4
        val sourceRed = 20
        val restoredRed = 200
        val lowDistance = kotlin.math.abs((low.image.pixels[center].toInt() and 0xFF) - restoredRed)
        val highDistance = kotlin.math.abs((high.image.pixels[center].toInt() and 0xFF) - restoredRed)

        assertTrue(highDistance < lowDistance)
        assertTrue(low.image.pixels[center].toInt() and 0xFF > sourceRed)
    }

    @Test
    fun `face pass propagates cancellation`() {
        var checks = 0
        val detector = object : FaceDetector {
            override fun detect(source: RgbaImage): List<FaceBox> = listOf(FaceBox(0, 0, 48, 48))
        }
        val restorer = object : FaceRestorer {
            override fun restore(crop: RgbaImage): RgbaImage = crop
        }

        val error = runCatching {
            FaceRestorePass(
                strength = 100,
                detector = detector,
                restorer = restorer,
                isCancelled = {
                    checks++
                    checks >= 3
                },
            ).apply(solidImage(64, 64, 20, 30, 40, 173), PassContext(100, verifiedModels()))
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertTrue(checks >= 3)
    }

    @Test
    fun `face pass propagates out of memory from the restorer`() {
        val expected = OutOfMemoryError("simulated face allocation failure")
        val detector = object : FaceDetector {
            override fun detect(source: RgbaImage): List<FaceBox> = listOf(FaceBox(0, 0, 48, 48))
        }
        val restorer = object : FaceRestorer {
            override fun restore(crop: RgbaImage): RgbaImage = throw expected
        }

        val error = runCatching {
            FaceRestorePass(100, detector, restorer)
                .apply(solidImage(64, 64, 20, 30, 40, 173), PassContext(100, verifiedModels()))
        }.exceptionOrNull()

        assertSame(expected, error)
    }

    @Test
    fun `enhancement runs face restoration on the upscaled buffer before export`() = runBlocking {
        var detectedWidth = 0
        var detectedHeight = 0
        val detector = object : FaceDetector {
            override fun detect(source: RgbaImage): List<FaceBox> {
                detectedWidth = source.width
                detectedHeight = source.height
                return listOf(FaceBox(0, 0, 48, 48), FaceBox(64, 64, 32, 32))
            }
        }
        val restorer = object : FaceRestorer {
            override fun restore(crop: RgbaImage): RgbaImage = RgbaImage(
                ByteArray(crop.pixels.size) { index -> if (index % 4 == 3) 0.toByte() else 210.toByte() },
                crop.width,
                crop.height,
            )
        }
        var encodedWidth = 0
        var encodedHeight = 0
        var encodedPixels: ByteArray? = null
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(48, 48)

            override fun decode(uri: String, maxMegapixels: Int) =
                EnhanceImage.DecodedImage(solidImage(48, 48, 20, 30, 40, 173).pixels, 48, 48)

            override fun encode(
                rgba: ByteArray,
                width: Int,
                height: Int,
                destinationUri: String,
                policy: ExportPolicy,
                exifSourceUri: String?,
            ): String {
                encodedWidth = width
                encodedHeight = height
                encodedPixels = rgba.copyOf()
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
            ): FloatArray {
                val scale = InferenceEngine.scaleFor(modelKey)
                val outputWidth = tileWidth * scale
                val outputHeight = tileHeight * scale
                val inputPlane = tileWidth * tileHeight
                val outputPlane = outputWidth * outputHeight
                return FloatArray(3 * outputPlane).also { output ->
                    for (y in 0 until outputHeight) {
                        for (x in 0 until outputWidth) {
                            val sourceX = (x / scale).coerceAtMost(tileWidth - 1)
                            val sourceY = (y / scale).coerceAtMost(tileHeight - 1)
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

        val progress = EnhanceImage(
            engine = engine,
            imageIo = imageIo,
            modelProvider = verifiedModels(),
            faceDetector = detector,
            faceRestorer = restorer,
        ).run(
            request = EnhanceRequest(
                inputUris = listOf("content://input/photo"),
                scale = ScaleFactor.X2,
                denoise = DenoiseStrength.OFF,
                faceRestoreEnabled = true,
                faceRestoreStrength = 100,
                sharpen = false,
            ),
            outputNameFor = { _, _ -> "face-restored.png" },
        ).toList()

        assertEquals(96, detectedWidth)
        assertEquals(96, detectedHeight)
        assertEquals(96, encodedWidth)
        assertEquals(96, encodedHeight)
        assertTrue(progress.indexOfFirst { it.step == ProcessStep.DETECTING_FACES } <
            progress.indexOfFirst { it.step == ProcessStep.RESTORING_FACES })
        assertTrue(progress.indexOfFirst { it.step == ProcessStep.RESTORING_FACES } <
            progress.indexOfFirst { it.step == ProcessStep.BLENDING })
        val restoring = progress.first { it.step == ProcessStep.RESTORING_FACES }
        assertEquals(1, restoring.skippedSmallFaces)
        assertTrue(restoring.backendUsed?.contains("skippedSmallFaces=1") == true)
        assertEquals(1, progress.last().skippedSmallFaces)
        assertTrue(progress.map { it.overall }.zipWithNext().all { (before, after) -> after >= before })
        val output = requireNotNull(encodedPixels)
        val center = ((24 * 96) + 24) * 4
        assertNotEquals(20, output[center].toInt() and 0xFF)
        assertEquals(255, output[center + 3].toInt() and 0xFF)
    }

    private fun solidImage(width: Int, height: Int, red: Int, green: Int, blue: Int, alpha: Int): RgbaImage {
        val pixels = ByteArray(width * height * 4)
        var index = 0
        while (index < pixels.size) {
            pixels[index] = red.toByte()
            pixels[index + 1] = green.toByte()
            pixels[index + 2] = blue.toByte()
            pixels[index + 3] = alpha.toByte()
            index += 4
        }
        return RgbaImage(pixels, width, height)
    }

    private fun verifiedModels(): ModelProvider = object : ModelProvider {
        override fun load(key: InferenceEngine.ModelKey): ModelHandle? = object : ModelHandle {
            override val backendName = "verified fake"
            override val inputScale = 1
            override fun close() = Unit
        }
    }
}
