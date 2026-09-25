package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.cache.RenderCacheStore
import com.rimuru.twobytwo.data.history.FileHistoryStore
import com.rimuru.twobytwo.data.history.HistoryRecord
import com.rimuru.twobytwo.data.history.HistoryStore
import com.rimuru.twobytwo.data.work.EnhanceRequestJson
import com.rimuru.twobytwo.data.work.WorkflowPersistenceService
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.model.Accelerator
import com.rimuru.twobytwo.domain.model.CropPreset
import com.rimuru.twobytwo.domain.model.DenoiseStrength
import com.rimuru.twobytwo.domain.model.EnhanceRequest
import com.rimuru.twobytwo.domain.model.EnhanceResult
import com.rimuru.twobytwo.domain.model.ExportPolicy
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WorkflowIntegrationTest {

    @Test
    fun `request JSON round trips crop preset and cache limit`() {
        val request = EnhanceRequest(
            inputUris = listOf("content://input/one", "content://input/two"),
            cropPreset = CropPreset.SQUARE,
            cacheLimitBytes = EnhanceRequest.DEFAULT_CACHE_LIMIT_BYTES + 1L,
        )

        val encoded = EnhanceRequestJson.encode(request)
        val json = JSONObject(encoded)
        val decoded = EnhanceRequestJson.decode(encoded)

        assertEquals(CropPreset.SQUARE.name, json.getString("cropPreset"))
        assertEquals(request.cacheLimitBytes, json.getLong("cacheLimitBytes"))
        assertEquals(request, decoded)
    }

    @Test
    fun `legacy request without new fields decodes with no crop and the default cache limit`() {
        val legacyJson = JSONObject(
            EnhanceRequestJson.encode(EnhanceRequest(inputUris = listOf("content://input/legacy"))),
        ).apply {
            remove("cropPreset")
            remove("cacheLimitBytes")
        }.toString()

        val decoded = EnhanceRequestJson.decode(legacyJson)

        assertNull(decoded?.cropPreset)
        assertEquals(EnhanceRequest.DEFAULT_CACHE_LIMIT_BYTES, decoded?.cacheLimitBytes)
    }

    @Test
    fun `settings snapshot excludes input uris and keeps stable key order`() {
        val settings = JSONObject(
            EnhanceRequestJson.encodeSettings(
                EnhanceRequest(inputUris = listOf("content://input/one"), cropPreset = CropPreset.SQUARE),
            ),
        )

        assertEquals(
            listOf(
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
            settings.keys().asSequence().toList(),
        )
        assertFalse(settings.has("inputUris"))
    }

    @Test
    fun `invalid crop and cache values are rejected`() {
        val base = JSONObject(EnhanceRequestJson.encode(EnhanceRequest(inputUris = listOf("content://input/invalid"))))
        val invalid = listOf(
            JSONObject(base.toString()).put("cropPreset", "UNKNOWN"),
            JSONObject(base.toString()).put("cacheLimitBytes", JSONObject.NULL),
            JSONObject(base.toString()).put("cacheLimitBytes", 1.5),
            JSONObject(base.toString()).put("cacheLimitBytes", -1),
            JSONObject(base.toString()).put("cacheLimitBytes", EnhanceRequest.MIN_CACHE_LIMIT_BYTES - 1),
            JSONObject(base.toString()).put("cacheLimitBytes", EnhanceRequest.MAX_CACHE_LIMIT_BYTES + 1),
        )

        invalid.forEach { assertNull(EnhanceRequestJson.decode(it.toString())) }
    }

    @Test
    fun `cache minimum and maximum are accepted`() {
        listOf(EnhanceRequest.MIN_CACHE_LIMIT_BYTES, EnhanceRequest.MAX_CACHE_LIMIT_BYTES).forEach { limit ->
            val encoded = EnhanceRequestJson.encode(
                EnhanceRequest(inputUris = listOf("content://input/limit"), cacheLimitBytes = limit),
            )

            assertEquals(limit, EnhanceRequestJson.decode(encoded)?.cacheLimitBytes)
        }
    }

    @Test
    fun `persistence callback failure escapes before the next item`() {
        var decodeCount = 0
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
            ): String = "content://media/$destinationUri"
        }
        val failure = runCatching {
            runBlocking {
                EnhanceImage(testEngine(), imageIo).run(
                    request = EnhanceRequest(
                        inputUris = listOf("content://input/first", "content://input/second"),
                        denoise = DenoiseStrength.OFF,
                        sharpen = false,
                    ),
                    outputNameFor = { index, _ -> "output-$index.png" },
                    onItemCompleted = { index, _ ->
                        if (index == 0) error("history failed")
                    },
                ).toList()
            }
        }.exceptionOrNull()

        assertEquals("history failed", failure?.message)
        assertEquals(1, decodeCount)
    }

    @Test
    fun `crop preset is applied before restoration and upscale`() = runBlocking {
        val observation = runObservation(CropPreset.SQUARE)

        assertEquals(listOf(2 to 2), observation.engineInputSizes)
        assertEquals(listOf(4 to 4), observation.encoderSizes)
        val success = observation.results.single() as EnhanceResult.Success
        assertEquals(4, success.width)
        assertEquals(4, success.height)
        assertTrue(observation.events.indexOf("decode") < observation.events.indexOf("encode:4x4"))
        assertTrue(observation.events.none { it.startsWith("export") })
    }

    @Test
    fun `null crop preserves the existing pipeline dimensions`() = runBlocking {
        val observation = runObservation(null)

        assertEquals(listOf(4 to 2), observation.engineInputSizes)
        assertEquals(listOf(8 to 4), observation.encoderSizes)
        val success = observation.results.single() as EnhanceResult.Success
        assertEquals(8, success.width)
        assertEquals(4, success.height)
    }

    @Test
    fun `successful item writes one metadata record after encode`() = runBlocking {
        val historyRoot = Files.createTempDirectory("workflow-history-success").toFile()
        val cacheRoot = Files.createTempDirectory("workflow-cache-success").toFile()
        val events = mutableListOf<String>()
        val history = RecordingHistoryStore(FileHistoryStore(historyRoot), events)
        val service = WorkflowPersistenceService(
            historyStore = history,
            cacheStore = RenderCacheStore(cacheRoot, 1024),
            clock = { 1_700_000_000_000L },
        )
        val request = EnhanceRequest(
            inputUris = listOf("content://input/success"),
            cropPreset = CropPreset.SQUARE,
        )
        val results = runPersistedBatch(
            request = request,
            service = service,
            events = events,
            encodedUri = { "content://media/success.png" },
        )
        val record = requireNotNull(history.find("job-1-0"))

        assertEquals(1, results.size)
        assertTrue(events.indexOf("encode") < events.indexOf("save"))
        assertEquals(listOf("job-1-0"), history.list().map { it.id })
        assertEquals("content://input/success", record.sourceUri)
        assertEquals("content://media/success.png", record.outputUri)
        assertEquals(2, record.width)
        assertEquals(2, record.height)
        assertEquals(1_700_000_000_000L, record.createdAt)
        assertNull(record.parentId)
        val settings = JSONObject(record.settingsJson)
        assertEquals(CropPreset.SQUARE.name, settings.getString("cropPreset"))
        assertEquals(EnhanceRequest.DEFAULT_CACHE_LIMIT_BYTES, settings.getLong("cacheLimitBytes"))
        assertFalse(settings.has("inputUris"))
        assertTrue(historyRoot.listFiles().orEmpty().none { it.name.endsWith(".render") || it.name.endsWith(".png") })
    }

    @Test
    fun `failed item does not write history and later item can still write history`() = runBlocking {
        val historyRoot = Files.createTempDirectory("workflow-history-failure").toFile()
        val cacheRoot = Files.createTempDirectory("workflow-cache-failure").toFile()
        val events = mutableListOf<String>()
        val history = RecordingHistoryStore(FileHistoryStore(historyRoot), events)
        val service = WorkflowPersistenceService(
            historyStore = history,
            cacheStore = RenderCacheStore(cacheRoot, 1024),
            clock = { 1L },
        )
        val request = EnhanceRequest(
            inputUris = listOf("content://input/first", "content://input/second"),
            denoise = DenoiseStrength.OFF,
            sharpen = false,
        )

        runPersistedBatch(
            request = request,
            service = service,
            events = events,
            decodeFailureUri = "content://input/first",
            runId = "job-2",
            encodedUri = { "content://media/$it" },
        )

        assertEquals(listOf("job-2-1"), history.list().map { it.id })
        assertEquals(1, events.count { it == "save" })
    }

    @Test
    fun `blank encoded URI is not a confirmed output`() = runBlocking {
        val historyRoot = Files.createTempDirectory("workflow-history-blank").toFile()
        val cacheRoot = Files.createTempDirectory("workflow-cache-blank").toFile()
        val events = mutableListOf<String>()
        val history = RecordingHistoryStore(FileHistoryStore(historyRoot), events)
        val service = WorkflowPersistenceService(
            historyStore = history,
            cacheStore = RenderCacheStore(cacheRoot, 1024),
            clock = { 1L },
        )

        val results = runPersistedBatch(
            request = EnhanceRequest(inputUris = listOf("content://input/blank")),
            service = service,
            events = events,
            encodedUri = { "" },
        )

        assertEquals(1, results.size)
        assertTrue(results.single() is EnhanceResult.Failure)
        assertTrue(history.list().isEmpty())
        assertTrue(events.none { it == "save" })
    }

    @Test
    fun `cache eviction leaves the confirmed MediaStore output untouched`() = runBlocking {
        val historyRoot = Files.createTempDirectory("workflow-cache-eviction-history").toFile()
        val cacheRoot = Files.createTempDirectory("workflow-cache-eviction").toFile()
        val history = FileHistoryStore(historyRoot)
        val cache = RenderCacheStore(cacheRoot, 6)
        val service = WorkflowPersistenceService(history, cache) { 1L }
        val outputRegistry = OutputRegistry()
        val results = runPersistedBatch(
            request = EnhanceRequest(
                inputUris = listOf("content://input/cache"),
                denoise = DenoiseStrength.OFF,
                sharpen = false,
            ),
            service = service,
            events = mutableListOf<String>(),
            encodedUri = { uri -> outputRegistry.register(uri); uri },
        )
        val outputUri = (results.single() as EnhanceResult.Success).outputUri

        cache.put("old", byteArrayOf(1, 2, 3))
        assertTrue(cache.totalBytes() <= 6)
        cache.put("new", byteArrayOf(4, 5, 6))
        assertTrue(cache.totalBytes() <= 6)
        cache.put("latest", byteArrayOf(7, 8, 9))

        assertTrue(cache.totalBytes() <= 6)
        assertNull(cache.get("old"))
        assertTrue(outputRegistry.uris.contains(outputUri))
        assertTrue(outputRegistry.deleted.isEmpty())
        assertTrue(history.find("job-1-0")?.outputUri == outputUri)
    }

    @Test
    fun `cache purge does not delete history or MediaStore output`() {
        val historyRoot = Files.createTempDirectory("workflow-cache-purge-history").toFile()
        val cacheRoot = Files.createTempDirectory("workflow-cache-purge").toFile()
        val history = FileHistoryStore(historyRoot)
        val cache = RenderCacheStore(cacheRoot, 32)
        val service = WorkflowPersistenceService(history, cache) { 1L }
        val outputRegistry = OutputRegistry()
        val result = EnhanceResult.Success(
            outputUri = "content://media/confirmed",
            width = 4,
            height = 4,
            backendUsed = "test",
        )
        val record = service.saveCompletedItem(
            runId = "job-purge",
            batchIndex = 0,
            sourceUri = "content://input/purge",
            settingsJson = "{}",
            result = result,
        )
        cache.put("entry", byteArrayOf(1, 2, 3))
        outputRegistry.register(result.outputUri)

        service.purgeCache()

        assertEquals(0L, cache.totalBytes())
        assertEquals(record, history.find(record.id))
        assertTrue(outputRegistry.uris.contains(result.outputUri))
        assertTrue(outputRegistry.deleted.isEmpty())
    }

    private suspend fun runPersistedBatch(
        request: EnhanceRequest,
        service: WorkflowPersistenceService,
        events: MutableList<String>,
        decodeFailureUri: String? = null,
        runId: String = "job-1",
        encodedUri: (String) -> String,
    ): List<EnhanceResult> {
        val results = mutableListOf<EnhanceResult>()
        val settingsJson = EnhanceRequestJson.encodeSettings(request)
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int) = EnhanceImage.Dimensions(1, 1)

            override fun decode(uri: String, maxMegapixels: Int): EnhanceImage.DecodedImage {
                events += "decode:$uri"
                if (uri == decodeFailureUri) error("decode failed")
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
                events += "encode"
                return encodedUri(exifSourceUri.orEmpty())
            }
        }

        EnhanceImage(testEngine(), imageIo).run(
            request = request,
            outputNameFor = { index, _ -> "output-$index.png" },
            onItemCompleted = { batchIndex, result ->
                results += result
                if (result is EnhanceResult.Success) {
                    service.saveCompletedItem(
                        runId = runId,
                        batchIndex = batchIndex,
                        sourceUri = request.inputUris[batchIndex],
                        settingsJson = settingsJson,
                        result = result,
                    )
                }
            },
        ).toList()
        return results
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

    private class OutputRegistry {
        val uris = mutableSetOf<String>()
        val deleted = mutableListOf<String>()

        fun register(uri: String) {
            uris += uri
        }
    }

    private class RecordingHistoryStore(
        private val delegate: HistoryStore,
        private val events: MutableList<String>,
    ) : HistoryStore {
        override fun save(record: HistoryRecord) {
            events += "save"
            delegate.save(record)
        }

        override fun list(): List<HistoryRecord> = delegate.list()
        override fun find(id: String): HistoryRecord? = delegate.find(id)
        override fun duplicateSettings(id: String, newId: String): HistoryRecord? = delegate.duplicateSettings(id, newId)
    }

    private suspend fun runObservation(cropPreset: CropPreset?): PipelineObservation {
        val events = mutableListOf<String>()
        val engineInputSizes = mutableListOf<Pair<Int, Int>>()
        val encoderSizes = mutableListOf<Pair<Int, Int>>()
        val results = mutableListOf<EnhanceResult>()
        val imageIo = object : EnhanceImage.ImageIo {
            override fun measure(uri: String, maxMegapixels: Int): EnhanceImage.Dimensions {
                events += "measure"
                return EnhanceImage.Dimensions(4, 2)
            }

            override fun decode(uri: String, maxMegapixels: Int): EnhanceImage.DecodedImage {
                events += "decode"
                return EnhanceImage.DecodedImage(ByteArray(4 * 2 * 4), 4, 2)
            }

            override fun encode(
                rgba: ByteArray,
                width: Int,
                height: Int,
                destinationUri: String,
                policy: ExportPolicy,
                exifSourceUri: String?,
            ): String {
                events += "encode:${width}x$height"
                encoderSizes += width to height
                return "content://media/${destinationUri}"
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
                events += "inference:${tileWidth}x$tileHeight"
                engineInputSizes += tileWidth to tileHeight
                val scale = InferenceEngine.scaleFor(modelKey)
                return FloatArray(3 * tileWidth * scale * tileHeight * scale)
            }
        }

        EnhanceImage(engine, imageIo).run(
            request = EnhanceRequest(
                inputUris = listOf("content://input/photo"),
                denoise = DenoiseStrength.OFF,
                faceRestoreEnabled = true,
                sharpen = false,
                cropPreset = cropPreset,
            ),
            outputNameFor = { _, _ -> "output.png" },
            onItemCompleted = { _, result -> results += result },
        ).toList()

        return PipelineObservation(events, engineInputSizes, encoderSizes, results)
    }

    private data class PipelineObservation(
        val events: List<String>,
        val engineInputSizes: List<Pair<Int, Int>>,
        val encoderSizes: List<Pair<Int, Int>>,
        val results: List<EnhanceResult>,
    )
}
