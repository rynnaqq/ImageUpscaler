package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.work.EnhanceRequestJson
import com.rimuru.twobytwo.data.work.enhanceOutputName
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.ExportPolicy
import com.rimuru.twobytwo.domain.model.OutputFormat
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPolicyTest {

    @Test
    fun `default policy is PNG quality 97 with EXIF and without GPS`() {
        val policy = ExportPolicy()

        assertEquals(OutputFormat.PNG, policy.format)
        assertEquals(97, policy.jpegQuality)
        assertEquals(97, policy.effectiveJpegQuality)
        assertTrue(policy.keepExif)
        assertFalse(policy.keepGps)
    }

    @Test
    fun `JPEG quality clamps to the supported range`() {
        assertEquals(80, ExportPolicy(OutputFormat.JPEG, 79).effectiveJpegQuality)
        assertEquals(80, ExportPolicy(OutputFormat.JPEG, 80).effectiveJpegQuality)
        assertEquals(97, ExportPolicy(OutputFormat.JPEG, 97).effectiveJpegQuality)
        assertEquals(100, ExportPolicy(OutputFormat.JPEG, 100).effectiveJpegQuality)
        assertEquals(100, ExportPolicy(OutputFormat.JPEG, 101).effectiveJpegQuality)
    }

    @Test
    fun `PNG and WebP always encode with quality 100`() {
        listOf(OutputFormat.PNG, OutputFormat.WEBP).forEach { format ->
            assertEquals(100, ExportPolicy(format, 79).encoderQuality)
            assertEquals(100, ExportPolicy(format, 101).encoderQuality)
        }
        assertEquals(80, ExportPolicy(OutputFormat.JPEG, 79).encoderQuality)
        assertEquals(100, ExportPolicy(OutputFormat.JPEG, 101).encoderQuality)
    }

    @Test
    fun `format MIME types and extensions are exact`() {
        assertEquals("image/png", OutputFormat.PNG.mimeType)
        assertEquals("png", OutputFormat.PNG.fileExtension)
        assertEquals("image/jpeg", OutputFormat.JPEG.mimeType)
        assertEquals("jpg", OutputFormat.JPEG.fileExtension)
        assertEquals("image/webp", OutputFormat.WEBP.mimeType)
        assertEquals("webp", OutputFormat.WEBP.fileExtension)
    }

    @Test
    fun `EXIF and GPS policy flags remain independent`() {
        assertEquals(ExportPolicy(keepExif = true, keepGps = false), ExportPolicy())
        assertEquals(ExportPolicy(keepExif = true, keepGps = true), ExportPolicy(keepGps = true))
        assertEquals(ExportPolicy(keepExif = false, keepGps = true), ExportPolicy(keepExif = false, keepGps = true))
        assertEquals(ExportPolicy(keepExif = false, keepGps = false), ExportPolicy(keepExif = false))
    }

    @Test
    fun `non-default export policy survives request JSON round trip`() {
        val request = EnhanceRequest(
            inputUris = listOf("content://input/one"),
            exportPolicy = ExportPolicy(
                format = OutputFormat.WEBP,
                jpegQuality = 79,
                keepExif = false,
                keepGps = true,
            ),
        )

        assertEquals(request, EnhanceRequestJson.decode(EnhanceRequestJson.encode(request)))
    }

    @Test
    fun `legacy request without export keys uses the default policy`() {
        val legacyJson = JSONObject(
            EnhanceRequestJson.encode(EnhanceRequest(inputUris = listOf("content://input/legacy"))),
        ).apply {
            remove("exportFormat")
            remove("jpegQuality")
            remove("keepExif")
            remove("keepGps")
        }.toString()

        assertEquals(ExportPolicy(), EnhanceRequestJson.decode(legacyJson)?.exportPolicy)
    }

    @Test
    fun `missing or null export values use safe defaults`() {
        val base = JSONObject(EnhanceRequestJson.encode(EnhanceRequest(inputUris = listOf("content://input/defaults"))))
            .apply {
                put("exportFormat", JSONObject.NULL)
                put("jpegQuality", JSONObject.NULL)
                put("keepExif", JSONObject.NULL)
                put("keepGps", JSONObject.NULL)
            }

        assertEquals(ExportPolicy(), EnhanceRequestJson.decode(base.toString())?.exportPolicy)
    }

    @Test
    fun `unknown export format and non-integral quality are rejected`() {
        val base = JSONObject(EnhanceRequestJson.encode(EnhanceRequest(inputUris = listOf("content://input/invalid"))))

        assertNull(
            EnhanceRequestJson.decode(
                JSONObject(base.toString()).put("exportFormat", "BMP").toString(),
            ),
        )
        assertNull(
            EnhanceRequestJson.decode(
                JSONObject(base.toString()).put("jpegQuality", 97.5).toString(),
            ),
        )
        assertNull(
            EnhanceRequestJson.decode(
                JSONObject(base.toString()).put("jpegQuality", "97").toString(),
            ),
        )
    }

    @Test
    fun `settings include export policy and exclude input URIs`() {
        val settings = JSONObject(
            EnhanceRequestJson.encodeSettings(
                EnhanceRequest(
                    inputUris = listOf("content://input/one"),
                    exportPolicy = ExportPolicy(
                        format = OutputFormat.JPEG,
                        jpegQuality = 80,
                        keepExif = false,
                        keepGps = true,
                    ),
                ),
            ),
        )

        assertEquals(
            setOf(
                "scale",
                "mode",
                "denoise",
                "faceRestore",
                "faceStrength",
                "accelerator",
                "neural",
                "sharpen",
                "deblurEnabled",
                "deblurStrength",
                "scratchRepairEnabled",
                "scratchRepairStrength",
                "colorizeEnabled",
                "colorizeStrength",
                "cropPreset",
                "cacheLimitBytes",
                "exportFormat",
                "jpegQuality",
                "keepExif",
                "keepGps",
            ),
            settings.keys().asSequence().toSet(),
        )
        assertEquals("JPEG", settings.getString("exportFormat"))
        assertEquals(80, settings.getInt("jpegQuality"))
        assertFalse(settings.getBoolean("keepExif"))
        assertTrue(settings.getBoolean("keepGps"))
        assertFalse(settings.has("inputUris"))
    }

    @Test
    fun `pipeline passes the request policy and source URI to encode`() = runBlocking {
        val policy = ExportPolicy(
            format = OutputFormat.JPEG,
            jpegQuality = 80,
            keepExif = false,
            keepGps = true,
        )
        var receivedPolicy: ExportPolicy? = null
        var receivedSourceUri: String? = null
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(1, 1)

            override fun decode(uri: String, maxMegapixels: Int) = EnhanceImage.DecodedImage(ByteArray(4), 1, 1)

            override fun encode(
                rgba: ByteArray,
                width: Int,
                height: Int,
                destinationUri: String,
                policy: ExportPolicy,
                exifSourceUri: String?,
            ): String {
                receivedPolicy = policy
                receivedSourceUri = exifSourceUri
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
            ): FloatArray = FloatArray(3 * tileWidth * tileHeight * 4)
        }

        val progress = EnhanceImage(engine, imageIo).run(
            request = EnhanceRequest(
                inputUris = listOf("content://input/source"),
                denoise = com.rimuru.twobytwo.domain.model.DenoiseStrength.OFF,
                sharpen = false,
                exportPolicy = policy,
            ),
            outputNameFor = { _, _ -> "output.jpg" },
        ).toList()

        assertEquals("done", progress.last().step.name.lowercase())
        assertSame(policy, receivedPolicy)
        assertEquals("content://input/source", receivedSourceUri)
    }

    @Test
    fun `worker output names use the requested format extension`() {
        assertEquals("photo_1.png", enhanceOutputName("photo", 0, OutputFormat.PNG))
        assertEquals("photo_1.jpg", enhanceOutputName("photo", 0, OutputFormat.JPEG))
        assertEquals("photo_1.webp", enhanceOutputName("photo", 0, OutputFormat.WEBP))
    }

}
