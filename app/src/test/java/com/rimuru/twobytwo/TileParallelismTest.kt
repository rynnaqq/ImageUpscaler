package com.rimuru.twobytwo

import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.RgbaImage
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.EngineMode
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.ExportPolicy
import com.rimuru.twobytwo.domain.model.OutputFormat
import com.rimuru.twobytwo.domain.model.ScaleFactor
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tiles are now inferred concurrently but must still be *consumed* in tile order:
 * TileBlender blends each tile's feathered ramp against what its neighbours already
 * wrote, so consuming out of order silently corrupts every seam. These tests pin the
 * guarantee that parallel inference cannot change a single output byte.
 */
class TileParallelismTest {

    /** Deterministic per-pixel output so any ordering difference shows up as a diff. */
    private class FakeEngine(
        private val failOnCall: Int = -1,
    ) : InferenceEngine {
        val callCount = AtomicInteger()
        val concurrentPeak = AtomicInteger()
        private val inFlight = AtomicInteger()

        override val backendName = "FAKE"

        override fun isAvailable(accelerator: Accelerator) = true

        override fun upscaleTile(
            input: FloatArray,
            tileWidth: Int,
            tileHeight: Int,
            modelKey: InferenceEngine.ModelKey,
        ): FloatArray {
            val n = callCount.incrementAndGet()
            if (n == failOnCall) throw IllegalStateException("tile $n exploded")
            val live = inFlight.incrementAndGet()
            concurrentPeak.updateAndGet { maxOf(it, live) }
            try {
                // Uneven work so tiles finish out of order if the window really is parallel.
                Thread.sleep(if (n % 2 == 0) 12L else 2L)
                val scale = InferenceEngine.scaleFor(modelKey)
                val out = FloatArray(3 * tileWidth * scale * tileHeight * scale)
                for (c in 0 until 3) {
                    for (y in 0 until tileHeight * scale) {
                        for (x in 0 until tileWidth * scale) {
                            out[c * tileWidth * scale * tileHeight * scale + y * tileWidth * scale + x] =
                                ((c * 31 + y * 7 + x * 3) % 251).toFloat() / 255f
                        }
                    }
                }
                return out
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun close() = Unit
    }

    companion object {
        /** 128 px at tileSize 64 / overlap 24 gives a 3x3 = 9 tile plan. */
        const val SOURCE_SIDE = 128
    }

    private class CapturingIo : EnhanceImage.ImageIo {
        var encoded: ByteArray? = null
        var encodeCount = 0

        override fun measure(uri: String, maxMegapixels: Int) =
            EnhanceImage.Dimensions(SOURCE_SIDE, SOURCE_SIDE)

        override fun decode(uri: String, maxMegapixels: Int) = EnhanceImage.DecodedImage(
            ByteArray(SOURCE_SIDE * SOURCE_SIDE * 4) { i -> (i % 251).toByte() },
            SOURCE_SIDE,
            SOURCE_SIDE,
        )
        override fun encode(
            rgba: ByteArray,
            width: Int,
            height: Int,
            destinationUri: String,
            policy: ExportPolicy,
            exifSourceUri: String?,
        ): String {
            encoded = rgba.copyOf()
            encodeCount++
            return destinationUri
        }
    }

    private fun request() = EnhanceRequest(
        inputUris = listOf("content://one"),
        scale = ScaleFactor.X2,
        mode = EngineMode.CREATIVE,
        useNeuralEngine = true,
        sharpen = false,
        exportPolicy = ExportPolicy(format = OutputFormat.PNG),
    )

    private fun runWith(window: Int, engine: InferenceEngine): ByteArray = runBlocking {
        val io = CapturingIo()
        val useCase = EnhanceImage(
            engine = engine,
            imageIo = io,
            tileConfig = EnhanceImage.TileConfig(tileSize = 64),
            tileWindowFor = { _, _ -> window },
        )
        val progress = useCase.run(
            request = request(),
            outputNameFor = { _, _ -> "out" },
            sharpenMaxMegapixels = 0.0,
        ).toList()
        io.encoded ?: error(
            "encode never ran; pipeline error = " +
                (progress.lastOrNull { it.error != null }?.error ?: "<none>"),
        )
    }

    @Test
    fun `parallel tiles produce byte identical output to sequential`() {
        val sequential = runWith(window = 1, engine = FakeEngine())
        val parallel = runWith(window = 4, engine = FakeEngine())

        assertArrayEquals(
            "parallel tile inference changed the output bytes",
            sequential,
            parallel,
        )
    }

    @Test
    fun `a wide window really does overlap inferences`() {
        val engine = FakeEngine()
        runWith(window = 4, engine = engine)

        assertTrue(
            "expected concurrent inference, peak was ${engine.concurrentPeak.get()}",
            engine.concurrentPeak.get() > 1,
        )
    }

    @Test
    fun `a window of one never overlaps inferences`() {
        val engine = FakeEngine()
        runWith(window = 1, engine = engine)

        assertEquals(1, engine.concurrentPeak.get())
    }

    @Test
    fun `a failing tile surfaces its error instead of silently degrading`() {
        val error = runCatching {
            runWith(window = 4, engine = FakeEngine(failOnCall = 2))
        }.exceptionOrNull()

        assertTrue("expected the tile failure to propagate, got $error", error is IllegalStateException)
    }
}
