# Workflow and Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 10–50 image queues, crop presets, local history/versioning, and bounded render-cache cleanup.

**Architecture:** Keep WorkManager as the queue owner and use app-private JSON metadata plus binary cache files rather than duplicating image bytes. Crop runs before the restoration pipeline; history records completed jobs and links child versions to their parent.

**Tech Stack:** Kotlin coroutines, WorkManager, Android `Context.filesDir/cacheDir`, `org.json`, MediaStore URIs, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-24-offline-image-restoration-platform-design.md`

## Global Constraints

- History and cache are local-only and contain no credentials or network calls.
- MediaStore outputs are never deleted by cache eviction.
- Queue order is deterministic and cancellation remains responsive.
- Crop presets are applied before upscaling and are included in the history settings snapshot.

---

### Task 1: Add crop models and pure crop math

**Files:**
- Create: `app/src/main/java/com/rimuru/twobytwo/domain/model/CropPreset.kt`
- Create: `app/src/main/java/com/rimuru/twobytwo/domain/usecase/CropProcessor.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/model/Models.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/CropProcessorTest.kt`

**Interfaces:**

```kotlin
enum class CropPreset(val ratio: Double) {
    SQUARE(1.0), PORTRAIT_9_16(9.0 / 16.0), PORTRAIT_4_5(4.0 / 5.0),
    PRINT_4_6(4.0 / 6.0), PRINT_8_10(8.0 / 10.0)
}
data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)
```

- [ ] **Step 1: Write failing crop tests**

Cover center crops for each preset, odd dimensions, minimum one-pixel dimensions, and exact requested aspect ratios within one pixel.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*CropProcessorTest'`

Expected: FAIL because crop types do not exist.

- [ ] **Step 3: Implement `CropProcessor.centerCrop`**

Calculate the largest centered rectangle matching the preset ratio, clamp to source bounds, and return a new RGBA buffer.

- [ ] **Step 4: Run the focused test**

Run the same command and expect PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/model/CropPreset.kt app/src/main/java/com/rimuru/twobytwo/domain/usecase/CropProcessor.kt app/src/main/java/com/rimuru/twobytwo/domain/model/Models.kt app/src/test/java/com/rimuru/twobytwo/CropProcessorTest.kt
git commit -m "Add crop presets"
```

### Task 2: Add local history storage and versions

**Files:**
- Create: `app/src/main/java/com/rimuru/twobytwo/data/history/HistoryStore.kt`
- Create: `app/src/main/java/com/rimuru/twobytwo/data/history/HistoryRecord.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/HistoryStoreTest.kt`

**Interfaces:**

```kotlin
data class HistoryRecord(
    val id: String,
    val sourceUri: String,
    val settingsJson: String,
    val outputUri: String?,
    val width: Int,
    val height: Int,
    val createdAt: Long,
    val parentId: String?,
)
interface HistoryStore {
    fun save(record: HistoryRecord)
    fun list(): List<HistoryRecord>
    fun find(id: String): HistoryRecord?
}
```

- [ ] **Step 1: Write failing serialization tests**

Round-trip all fields, list newest first, find a parent by ID, and reject malformed JSON without deleting existing records.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*HistoryStoreTest'`

Expected: FAIL because the store does not exist.

- [ ] **Step 3: Implement atomic JSON persistence**

Write each record to a temporary file under `filesDir/history`, then rename it into place. Use a small index file or directory scan for list operations; never store image bytes in history.

- [ ] **Step 4: Add version creation**

Expose `duplicateSettings(id, newId)` that copies the settings JSON and sets `parentId` without copying or overwriting the old output URI.

- [ ] **Step 5: Run tests and assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/data/history/HistoryStore.kt app/src/main/java/com/rimuru/twobytwo/data/history/HistoryRecord.kt app/src/test/java/com/rimuru/twobytwo/HistoryStoreTest.kt
git commit -m "Add local enhancement history"
```

### Task 3: Add bounded LRU render cache

**Files:**
- Create: `app/src/main/java/com/rimuru/twobytwo/data/cache/RenderCacheStore.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/RenderCacheStoreTest.kt`

**Interfaces:**

```kotlin
data class CacheEntry(val path: String, val bytes: Long, val lastAccess: Long)
class RenderCacheStore(private val root: File, private val maxBytes: Long) {
    fun put(key: String, source: ByteArray): String
    fun get(key: String): ByteArray?
    fun touch(key: String)
    fun clear()
    fun totalBytes(): Long
}
```

- [ ] **Step 1: Write failing LRU tests**

Cover put/get, touch ordering, eviction at the byte limit, clear, and protection of files outside the cache root.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*RenderCacheStoreTest'`

Expected: FAIL because the store does not exist.

- [ ] **Step 3: Implement atomic cache writes and LRU eviction**

Store each entry under a sanitized key, write to a temporary file, rename atomically, update access metadata, and evict least-recently-used entries until `totalBytes() <= maxBytes`.

- [ ] **Step 4: Run tests and assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/data/cache/RenderCacheStore.kt app/src/test/java/com/rimuru/twobytwo/RenderCacheStoreTest.kt
git commit -m "Add bounded render cache"
```

### Task 4: Expand batch queue to 50 images

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/presentation/EnhanceViewModel.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/presentation/AppRoot.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/BatchQueueTest.kt`

**Interfaces:**
- Photo Picker accepts `maxItems = 50`.
- `EnhanceRequest.inputUris` preserves picker order.
- `JobProgress.batchIndex` and `batchTotal` identify each item; a normal item failure does not stop later items.

- [ ] **Step 1: Write failing queue tests**

Assert 50 URIs are serialized unchanged, progress indices are zero-based, failed item N does not prevent item N+1, and cancellation stops before the next item.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*BatchQueueTest'`

Expected: FAIL because the current UI caps selection at 20 and the queue lacks the required status model.

- [ ] **Step 3: Implement queue state**

Add a `BatchItemState` list to `UiState`, update it from worker progress, and render per-item status in the processing screen. Keep sequential processing to bound memory.

- [ ] **Step 4: Run tests and assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/presentation/EnhanceViewModel.kt app/src/main/java/com/rimuru/twobytwo/presentation/AppRoot.kt app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt app/src/test/java/com/rimuru/twobytwo/BatchQueueTest.kt
git commit -m "Expand offline batch queue"
```

### Task 5: Wire crop, history, and cache into the worker

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/model/Models.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/WorkflowIntegrationTest.kt`

**Interfaces:**
- Worker input JSON includes `cropPreset` and `cacheLimitBytes`.
- Each successful item writes one `HistoryRecord` and may write named intermediate cache entries.
- Cache purge is callable independently of history deletion.

- [ ] **Step 1: Write failing integration tests**

Run a fake `ImageIo` through crop → process → encode, assert the history record dimensions/settings, then fill the cache and assert eviction leaves the MediaStore URI untouched.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*WorkflowIntegrationTest'`

Expected: FAIL because the worker does not yet pass crop/cache/history data.

- [ ] **Step 3: Implement worker wiring**

Construct stores once per worker, apply crop before `EnhanceImage`, save history only after confirmed output URI, and expose cache purge through a small application service.

- [ ] **Step 4: Run the full verification**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt app/src/main/java/com/rimuru/twobytwo/domain/model/Models.kt app/src/test/java/com/rimuru/twobytwo/WorkflowIntegrationTest.kt
git commit -m "Wire crop history and cache"
```
