package com.rimuru.twobytwo.data.work

import com.rimuru.twobytwo.data.cache.RenderCacheStore
import com.rimuru.twobytwo.data.history.HistoryRecord
import com.rimuru.twobytwo.data.history.HistoryStore
import com.rimuru.twobytwo.domain.model.EnhanceResult
import org.json.JSONObject
import java.nio.charset.StandardCharsets

internal fun renderCacheKey(runId: String, batchIndex: Int): String = "render:$runId:$batchIndex"

class WorkflowPersistenceService(
    private val historyStore: HistoryStore,
    private val cacheStore: RenderCacheStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun saveCompletedItem(
        runId: String,
        batchIndex: Int,
        sourceUri: String,
        settingsJson: String,
        result: EnhanceResult.Success,
    ): HistoryRecord {
        require(result.outputUri.isNotBlank()) { "output URI must not be blank" }
        require(result.width > 0 && result.height > 0) { "output dimensions must be positive" }
        val record = HistoryRecord(
            id = "$runId-$batchIndex",
            sourceUri = sourceUri,
            settingsJson = settingsJson,
            outputUri = result.outputUri,
            width = result.width,
            height = result.height,
            createdAt = clock(),
            parentId = null,
        )
        historyStore.save(record)
        cacheStore.put(
            key = renderCacheKey(runId, batchIndex),
            source = JSONObject()
                .put("runId", runId)
                .put("batchIndex", batchIndex)
                .put("outputUri", result.outputUri)
                .put("width", result.width)
                .put("height", result.height)
                .put("backend", result.backendUsed)
                .toString()
                .toByteArray(StandardCharsets.UTF_8),
        )
        return record
    }

    fun purgeCache() {
        cacheStore.clear()
    }
}
