package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.work.EnhanceRequestJson
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.CropPreset
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.ExportPolicy
import com.rimuru.twobytwo.domain.model.ModelProfile
import com.rimuru.twobytwo.domain.model.OutputFormat
import com.rimuru.twobytwo.domain.model.modelProfile
import com.rimuru.twobytwo.presentation.EnhanceViewModel
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetCacheLimitBytes
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetColorize
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetColorizeStrength
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetCropPreset
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetDeblur
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetDeblurStrength
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetExportFormat
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetJpegQuality
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetKeepExif
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetKeepGps
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetModelProfile
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetScratchRepair
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetScratchRepairStrength
import com.rimuru.twobytwo.presentation.EnhanceViewModel.Intent.SetSharpen
import com.rimuru.twobytwo.presentation.EnhanceViewModel.UiState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class SettingsStateTest {

    @Test
    fun `initial settings preserve the legacy defaults`() {
        val state = UiState()

        assertEquals(ModelProfile.ULTRA, state.modelProfile)
        assertEquals(Accelerator.AUTO, state.accelerator)
        assertEquals(ExportPolicy(), state.exportPolicy)
        assertEquals(OutputFormat.PNG, state.outputFormat)
        assertEquals(97, state.quality)
        assertTrue(state.keepExif)
        assertFalse(state.keepGps)
        assertFalse(state.fastPathOfferHandled)
        assertEquals(EnhanceRequest.DEFAULT_CACHE_LIMIT_BYTES, state.cacheLimitBytes)
    }

    @Test
    fun `initial settings preserve the legacy restoration defaults`() {
        val state = UiState()

        assertEquals(null, state.cropPreset)
        assertTrue(state.sharpen)
        assertFalse(state.deblurEnabled)
        assertEquals(50, state.deblurStrength)
        assertFalse(state.scratchRepairEnabled)
        assertEquals(50, state.scratchRepairStrength)
        assertFalse(state.colorizeEnabled)
        assertEquals(50, state.colorizeStrength)
    }

    @Test
    fun `restoration settings reduce and map into the request without defaults`() {
        var state = UiState()
        state = EnhanceViewModel.applySettingsIntent(state, SetCropPreset(CropPreset.PORTRAIT_4_5))
        state = EnhanceViewModel.applySettingsIntent(state, SetSharpen(false))
        state = EnhanceViewModel.applySettingsIntent(state, SetDeblur(true))
        state = EnhanceViewModel.applySettingsIntent(state, SetDeblurStrength(64))
        state = EnhanceViewModel.applySettingsIntent(state, SetScratchRepair(true))
        state = EnhanceViewModel.applySettingsIntent(state, SetScratchRepairStrength(61))
        state = EnhanceViewModel.applySettingsIntent(state, SetColorize(true))
        state = EnhanceViewModel.applySettingsIntent(state, SetColorizeStrength(59))

        val request = EnhanceViewModel.requestFor(state)

        assertEquals(CropPreset.PORTRAIT_4_5, state.cropPreset)
        assertEquals(CropPreset.PORTRAIT_4_5, request.cropPreset)
        assertFalse(request.sharpen)
        assertTrue(request.deblurEnabled)
        assertEquals(64, request.deblurStrength)
        assertTrue(request.scratchRepairEnabled)
        assertEquals(61, request.scratchRepairStrength)
        assertTrue(request.colorizeEnabled)
        assertEquals(59, request.colorizeStrength)
    }

    @Test
    fun `restoration strength intents clamp to zero through one hundred`() {
        val state = listOf(
            SetDeblurStrength(-1),
            SetScratchRepairStrength(101),
            SetColorizeStrength(-1),
        ).fold(UiState(), EnhanceViewModel::applySettingsIntent)

        assertEquals(0, state.deblurStrength)
        assertEquals(100, state.scratchRepairStrength)
        assertEquals(0, state.colorizeStrength)
    }

    @Test
    fun `model profile maps to the legacy neural flag`() {
        val fast = EnhanceViewModel.applySettingsIntent(UiState(), SetModelProfile(ModelProfile.FAST))
        val ultra = EnhanceViewModel.applySettingsIntent(UiState(), SetModelProfile(ModelProfile.ULTRA))

        assertFalse(EnhanceViewModel.requestFor(fast).useNeuralEngine)
        assertTrue(EnhanceViewModel.requestFor(ultra).useNeuralEngine)
    }

    @Test
    fun `model profile choice clears the fast path offer without replacing the choice`() {
        val offered = UiState(showFastPathOffer = true)

        val fast = EnhanceViewModel.applySettingsIntent(offered, SetModelProfile(ModelProfile.FAST))
        val ultra = EnhanceViewModel.applySettingsIntent(offered, SetModelProfile(ModelProfile.ULTRA))

        assertEquals(ModelProfile.FAST, fast.modelProfile)
        assertFalse(fast.showFastPathOffer)
        assertEquals(ModelProfile.ULTRA, ultra.modelProfile)
        assertFalse(ultra.showFastPathOffer)
    }

    @Test
    fun `fast path choices remain handled across subsequent offer checks`() {
        val eligible = UiState(
            pickedUris = listOf("content://input/photo"),
            isLowSpec = true,
        )
        val offered = eligible.copy(showFastPathOffer = true)

        val fast = EnhanceViewModel.applySettingsIntent(offered, SetModelProfile(ModelProfile.FAST))
        val ultra = EnhanceViewModel.applySettingsIntent(offered, SetModelProfile(ModelProfile.ULTRA))

        assertFalse(offered.fastPathOfferHandled)
        assertTrue(EnhanceViewModel.shouldOfferFastPath(eligible))
        assertFalse(EnhanceViewModel.shouldOfferFastPath(offered))
        assertTrue(fast.fastPathOfferHandled)
        assertFalse(fast.showFastPathOffer)
        assertFalse(EnhanceViewModel.shouldOfferFastPath(fast))
        assertTrue(ultra.fastPathOfferHandled)
        assertFalse(ultra.showFastPathOffer)
        assertFalse(EnhanceViewModel.shouldOfferFastPath(ultra))
    }

    @Test
    fun `export settings update one policy field at a time`() {
        val withQuality = EnhanceViewModel.applySettingsIntent(UiState(), SetJpegQuality(86))
        val webp = EnhanceViewModel.applySettingsIntent(withQuality, SetExportFormat(OutputFormat.WEBP))
        val metadata = EnhanceViewModel.applySettingsIntent(webp, SetKeepExif(false))
        val withGps = EnhanceViewModel.applySettingsIntent(metadata, SetKeepGps(true))

        assertEquals(86, withGps.quality)
        assertEquals(OutputFormat.WEBP, withGps.outputFormat)
        assertFalse(withGps.keepExif)
        assertTrue(withGps.keepGps)
    }

    @Test
    fun `jpeg quality intent clamps to 80 through 100`() {
        val below = EnhanceViewModel.applySettingsIntent(UiState(), SetJpegQuality(79))
        val above = EnhanceViewModel.applySettingsIntent(UiState(), SetJpegQuality(101))
        val png = EnhanceViewModel.applySettingsIntent(below, SetExportFormat(OutputFormat.PNG))
        val webp = EnhanceViewModel.applySettingsIntent(above, SetExportFormat(OutputFormat.WEBP))

        assertEquals(80, below.quality)
        assertEquals(100, above.quality)
        assertEquals(100, EnhanceViewModel.requestFor(png).exportPolicy.encoderQuality)
        assertEquals(100, EnhanceViewModel.requestFor(webp).exportPolicy.encoderQuality)
    }

    @Test
    fun `cache limit intent clamps to the existing request bounds`() {
        val minimum = EnhanceViewModel.applySettingsIntent(
            UiState(),
            SetCacheLimitBytes(EnhanceRequest.MIN_CACHE_LIMIT_BYTES - 1L),
        )
        val maximum = EnhanceViewModel.applySettingsIntent(
            UiState(),
            SetCacheLimitBytes(EnhanceRequest.MAX_CACHE_LIMIT_BYTES + 1L),
        )

        assertEquals(EnhanceRequest.MIN_CACHE_LIMIT_BYTES, minimum.cacheLimitBytes)
        assertEquals(EnhanceRequest.MAX_CACHE_LIMIT_BYTES, maximum.cacheLimitBytes)
        listOf(minimum, maximum).forEach { state ->
            val request = EnhanceViewModel.requestFor(state)
            assertEquals(
                state.cacheLimitBytes,
                EnhanceRequestJson.decode(EnhanceRequestJson.encode(request))?.cacheLimitBytes,
            )
        }
    }

    @Test
    fun `accelerator filtering uses the isAvailable probe`() {
        val supported = setOf(Accelerator.AUTO, Accelerator.CPU, Accelerator.GPU)

        val available = EnhanceViewModel.filterAvailableAccelerators { it in supported }

        assertEquals(supported, available)
        assertFalse(Accelerator.NPU in available)
    }

    @Test
    fun `accelerator probe construction failure falls back to auto`() {
        val createEngine: () -> AutoCloseable = { throw AssertionError("construction failed") }

        val available = EnhanceViewModel.probeAvailableAccelerators(createEngine) { _, _ -> true }

        assertEquals(setOf(Accelerator.AUTO), available)
    }

    @Test
    fun `accelerator probe close failure falls back to auto`() {
        val engine = object : AutoCloseable {
            override fun close(): Unit = throw AssertionError("close failed")
        }

        val available = EnhanceViewModel.probeAvailableAccelerators(
            createEngine = { engine },
            probe = { _, accelerator -> accelerator == Accelerator.CPU },
        )

        assertEquals(setOf(Accelerator.AUTO), available)
    }

    @Test
    fun `accelerator probe failure marks only the affected backend unsupported`() {
        val engine = object : AutoCloseable {
            override fun close() = Unit
        }

        val available = EnhanceViewModel.probeAvailableAccelerators(
            createEngine = { engine },
            probe = { _, accelerator ->
                if (accelerator == Accelerator.GPU) throw AssertionError("probe failed")
                accelerator == Accelerator.CPU
            },
        )

        assertEquals(setOf(Accelerator.AUTO, Accelerator.CPU), available)
    }

    @Test
    fun `accelerator probe preserves cancellation`() {
        val cancellation = CancellationException("cancelled")
        val engine = object : AutoCloseable {
            override fun close() = Unit
        }

        val thrown = runCatching {
            EnhanceViewModel.probeAvailableAccelerators(
                createEngine = { engine },
                probe = { _, _ -> throw cancellation },
            )
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun `unavailable accelerator intent is ignored`() {
        val state = UiState(
            accelerator = Accelerator.CPU,
            availableAccelerators = setOf(Accelerator.AUTO, Accelerator.CPU),
        )

        val reduced = EnhanceViewModel.applySettingsIntent(
            state,
            EnhanceViewModel.Intent.SetAccelerator(Accelerator.NPU),
        )
        val impossible = EnhanceViewModel.requestFor(
            state.copy(accelerator = Accelerator.NPU),
        )

        assertEquals(Accelerator.CPU, reduced.accelerator)
        assertEquals(Accelerator.AUTO, impossible.accelerator)
    }

    @Test
    fun `settings request round trips through existing worker JSON`() {
        var state = UiState()
        state = EnhanceViewModel.applySettingsIntent(state, SetModelProfile(ModelProfile.FAST))
        state = EnhanceViewModel.applySettingsIntent(state, SetExportFormat(OutputFormat.JPEG))
        state = EnhanceViewModel.applySettingsIntent(state, SetJpegQuality(80))
        state = EnhanceViewModel.applySettingsIntent(state, SetKeepExif(false))
        state = EnhanceViewModel.applySettingsIntent(state, SetKeepGps(true))
        state = EnhanceViewModel.applySettingsIntent(state, SetCacheLimitBytes(1024L * 1024L * 1024L))
        val request = EnhanceViewModel.requestFor(state)
        val encoded = EnhanceRequestJson.encode(request)
        val decoded = EnhanceRequestJson.decode(encoded)
        val json = JSONObject(encoded)

        assertEquals(request, decoded)
        assertFalse(json.getBoolean("neural"))
        assertEquals(1024L * 1024L * 1024L, json.getLong("cacheLimitBytes"))
        assertEquals("JPEG", json.getString("exportFormat"))
        assertEquals(80, json.getInt("jpegQuality"))
        assertFalse(json.getBoolean("keepExif"))
        assertTrue(json.getBoolean("keepGps"))
    }

    @Test
    fun `legacy worker JSON defaults to ULTRA`() {
        val legacyJson = JSONObject(
            EnhanceRequestJson.encode(EnhanceRequest(inputUris = listOf("content://input/legacy"))),
        ).apply {
            remove("neural")
            remove("exportFormat")
            remove("jpegQuality")
            remove("keepExif")
            remove("keepGps")
        }.toString()

        val decoded = EnhanceRequestJson.decode(legacyJson)

        assertTrue(decoded?.useNeuralEngine == true)
        assertEquals(ModelProfile.ULTRA, decoded?.modelProfile)
        assertEquals(ExportPolicy(), decoded?.exportPolicy)
    }

    @Test
    fun `settings snapshot does not add a second profile wire key`() {
        val settings = JSONObject(EnhanceRequestJson.encodeSettings(EnhanceViewModel.requestFor(UiState())))
        val keys = settings.keys().asSequence().toList()

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
            keys.toSet(),
        )
        assertFalse(settings.has("modelProfile"))
        assertFalse(settings.has("inputUris"))
    }
}
