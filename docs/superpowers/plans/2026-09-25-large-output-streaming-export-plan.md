# Large-Output Streaming Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make large-photo 4× and 8× enhancement complete without allocating the full output RGBA image, while preserving PNG/JPEG/WebP output, quality, EXIF, MediaStore, history, and cache behavior.

**Architecture:** Keep the existing in-memory path for ordinary outputs. For 4×/8× outputs at or above 64,000,000 pixels, assemble tile rows in bounded bands, write a temporary PNG incrementally, and use Spectrum 1.3.0 to transcode that file to the requested MediaStore format. A small streaming image-I/O capability keeps the domain pipeline independent from the Android encoder.

**Tech Stack:** Kotlin 2.0.20, Android API 26+, WorkManager, MediaStore, JUnit 4, Compose test dependencies, `com.facebook.spectrum:spectrum-default:1.3.0`, SoLoader.

**Spec:** `docs/superpowers/specs/2026-09-25-large-output-streaming-export-design.md`

## Global Constraints

- Keep the app offline: no `INTERNET` permission, network client, upload, or runtime download.
- Never silently downscale, crop, or substitute an output format.
- Preserve sequential batch order, zero-based progress, cancellation propagation, terminal `OutOfMemoryError`, EXIF allowlists, MediaStore publication, history, and cache contracts.
- Preserve existing 2×/small-output behavior and `EnhanceImage.outputBufferSize` for the legacy single-buffer path.
- Use Kotlin/JVM 17-compatible code and the repository’s existing test style.
- Do not add comments to production code unless explicitly requested.
- Run `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`; use GitHub Actions when the local Android toolchain is unavailable.

---

## File Map

Create:

- `app/src/main/java/com/rimuru/twobytwo/data/media/PngRowWriter.kt` — pure Kotlin streaming PNG scanline/chunk writer.
- `app/src/main/java/com/rimuru/twobytwo/domain/usecase/StreamingImageIo.kt` — row-producing export capability.
- `app/src/main/java/com/rimuru/twobytwo/domain/engine/StreamingTileWriter.kt` — bounded tile-row overlap/blend lifecycle.
- `app/src/main/java/com/rimuru/twobytwo/RimuruApplication.kt` — Spectrum/SoLoader initialization.
- `app/src/test/java/com/rimuru/twobytwo/PngRowWriterTest.kt` — PNG chunk and scanline tests.
- `app/src/test/java/com/rimuru/twobytwo/StreamingTileWriterTest.kt` — legacy-vs-streaming blend equivalence.
- `app/src/androidTest/java/com/rimuru/twobytwo/LargeOutputExportTest.kt` — MediaStore/Spectrum format and publication coverage.

Modify:

- `gradle/libs.versions.toml` — Spectrum version and library alias.
- `app/build.gradle.kts` — Spectrum implementation dependency.
- `app/src/main/AndroidManifest.xml` — register `RimuruApplication`.
- `app/src/main/java/com/rimuru/twobytwo/domain/engine/TileBlender.kt` — expose one-row blending without changing legacy blending.
- `app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt` — choose streaming output, process tile rows, and avoid the full output allocation.
- `app/src/main/java/com/rimuru/twobytwo/data/media/MediaStoreImageIo.kt` — implement the row-producing capability, temporary PNG, Spectrum transcode, metadata, publication, and cleanup.
- `app/src/test/java/com/rimuru/twobytwo/EnhanceScaleTest.kt` — cover streaming selection and 4×/8× row production.
- `app/src/test/java/com/rimuru/twobytwo/ExportPolicyTest.kt` — cover format/quality mapping in the streaming capability.
- `app/src/test/java/com/rimuru/twobytwo/EnhanceFailureTest.kt` — cover cleanup/error propagation at the domain boundary.
- `app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt` — surface terminal OOM messages in WorkManager output.
- `app/src/test/java/com/rimuru/twobytwo/EnhanceWorkerFailureTest.kt` — verify actionable terminal memory messages.

---

### Task 1: Add the Spectrum dependency and application bootstrap

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/rimuru/twobytwo/RimuruApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces `com.rimuru.twobytwo.RimuruApplication`, registered as the manifest application.
- Produces a process-wide initialized Spectrum runtime before any worker/activity uses `MediaStoreImageIo`.

- [ ] **Step 1: Add the dependency alias and implementation**

Add the exact version and library entries to the version catalog:

```toml
spectrum = "1.3.0"
spectrum-default = { module = "com.facebook.spectrum:spectrum-default", version.ref = "spectrum" }
```

Add this implementation dependency to `app/build.gradle.kts`:

```kotlin
implementation(libs.spectrum.default)
```

Do not add an `INTERNET` permission or any repository other than the existing `google()`/`mavenCentral()` repositories.

- [ ] **Step 2: Add the Application bootstrap**

Create:

```kotlin
package com.rimuru.twobytwo

import android.app.Application
import com.facebook.spectrum.SpectrumSoLoader

class RimuruApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SpectrumSoLoader.init(this)
    }
}
```

Register it in the manifest:

```xml
android:name=".RimuruApplication"
```

- [ ] **Step 3: Verify the dependency and manifest without changing runtime behavior**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`; inspect the merged manifest and confirm there is still no `android.permission.INTERNET`.

- [ ] **Step 4: Commit the isolated bootstrap change**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/com/rimuru/twobytwo/RimuruApplication.kt
git commit -m "Add Spectrum export runtime"
```

---

### Task 2: Implement and verify the pure PNG row writer

**Files:**
- Create: `app/src/main/java/com/rimuru/twobytwo/data/media/PngRowWriter.kt`
- Create: `app/src/test/java/com/rimuru/twobytwo/PngRowWriterTest.kt`

**Interfaces:**

```kotlin
class PngRowWriter(
    private val output: OutputStream,
    private val width: Int,
    private val height: Int,
) : Closeable {
    fun writeRgbaRow(row: ByteArray)
    fun finish()
    override fun close()
}
```

- [ ] **Step 1: Write failing PNG tests**

Cover these exact behaviors:

```kotlin
@Test
fun `writes valid png chunks and original rgba rows`() {
    val bytes = ByteArrayOutputStream()
    PngRowWriter(bytes, 2, 2).use { writer ->
        writer.writeRgbaRow(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        writer.writeRgbaRow(byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16))
    }

    val png = bytes.toByteArray()
    assertContentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), png.copyOfRange(0, 8))
    assertEquals(2, intAt(png, 16))
    assertEquals(2, intAt(png, 20))
    assertTrue(chunkTypes(png).contains("IHDR"))
    assertTrue(chunkTypes(png).contains("IDAT"))
    assertTrue(chunkTypes(png).contains("IEND"))
    assertContentEquals(expectedInflatedRows(), inflateIdat(png))
}

@Test
fun `rejects wrong row size and extra rows`() {
    val writer = PngRowWriter(ByteArrayOutputStream(), 1, 1)
    assertThrows(IllegalArgumentException::class.java) {
        writer.writeRgbaRow(ByteArray(3))
    }
    writer.writeRgbaRow(ByteArray(4))
    assertThrows(IllegalStateException::class.java) { writer.writeRgbaRow(ByteArray(4)) }
}
```

The test helper must parse big-endian PNG lengths, validate chunk CRCs, concatenate all `IDAT` payloads, inflate them, and compare filter-byte-prefixed rows against the input.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.rimuru.twobytwo.PngRowWriterTest
```

Expected: compilation/test failure because `PngRowWriter` does not exist.

- [ ] **Step 3: Implement the minimal PNG writer**

Implement the following exact behavior:

- Reject non-positive dimensions and a row buffer whose size is not `width * 4`.
- Write the eight-byte PNG signature on construction.
- Write an IHDR chunk with width, height, bit depth 8, color type 6, compression 0, filter 0, and interlace 0.
- Feed each row as `0x00` followed by the RGBA bytes into `Deflater`.
- Drain deflater output into bounded chunks (maximum 64 KiB), writing each chunk as IDAT with a CRC32 over the chunk type and payload.
- On `finish`, drain until `Deflater.finished()`, write IEND, and make subsequent row writes fail.
- On `close`, finish if needed, call `Deflater.end()`, and leave ownership of the supplied `OutputStream` with the caller.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the same Gradle command. Expected: all `PngRowWriterTest` tests pass.

- [ ] **Step 5: Commit the writer**

```bash
git add app/src/main/java/com/rimuru/twobytwo/data/media/PngRowWriter.kt app/src/test/java/com/rimuru/twobytwo/PngRowWriterTest.kt
git commit -m "Add streaming PNG row writer"
```

---

### Task 3: Add bounded tile-row streaming and prove blend equivalence

**Files:**
- Create: `app/src/main/java/com/rimuru/twobytwo/domain/engine/StreamingTileWriter.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/engine/TileBlender.kt`
- Create: `app/src/test/java/com/rimuru/twobytwo/StreamingTileWriterTest.kt`

**Interfaces:**

```kotlin
fun blendRow(
    tileRgba: ByteArray,
    tile: TilingManager.Tile,
    tiling: TilingManager,
    outRow: ByteArray,
    outY: Int,
)
```

```kotlin
class StreamingTileWriter(
    tiles: List<TilingManager.Tile>,
    tiling: TilingManager,
    writeRow: (ByteArray) -> Unit,
) {
    internal val retainedTileCount: Int
    fun accept(tileRgba: ByteArray, tile: TilingManager.Tile)
    fun finish()
}
```

- [ ] **Step 1: Write the failing equivalence test**

Build a 300×200 fixture with tile size 64 and overlap 8. For each tile, create a deterministic RGBA tile buffer. Feed tiles in `tiling.tiles()` order to `StreamingTileWriter`; separately allocate the legacy full output and call the existing `TileBlender.blend` for every tile. Assert the captured row bytes equal the corresponding legacy output bytes exactly.

Also assert:

- captured row count is `tiling.outHeight`;
- each captured row is `tiling.outWidth * 4` bytes;
- after `finish()`, `retainedTileCount == 0`;
- a tile accepted out of row order fails with `IllegalArgumentException`.

- [ ] **Step 2: Run the focused test and verify RED**

```bash
./gradlew :app:testDebugUnitTest --tests com.rimuru.twobytwo.StreamingTileWriterTest
```

Expected: compilation failure because the new writer/interface does not exist.

- [ ] **Step 3: Implement `blendRow` using the existing formula**

Refactor only the pixel-blend calculation needed by both paths. For each output pixel covered by the tile, calculate the existing `featherWeight`, fast-path full weights at `>= 0.999f`, and apply the existing signed-byte/unsigned-byte blend expression. Do not change `TileBlender.blend` behavior.

- [ ] **Step 4: Implement the row-band lifecycle**

Implement the lifecycle exactly as follows:

1. Group `tiles` by `row`, preserving column order.
2. Keep completed row groups in a small queue.
3. When a new row group is accepted, emit output rows from the last emitted coordinate up to the next row group’s input-origin output coordinate using all queued groups whose vertical footprints intersect that range.
4. Remove a completed group once its bottom output coordinate is before the next group’s start.
5. On `finish`, emit all remaining rows through `tiling.outHeight - 1`.
6. Require that every tile is accepted exactly once and in the declared row/column order.

- [ ] **Step 5: Run the focused test and verify GREEN**

```bash
./gradlew :app:testDebugUnitTest --tests com.rimuru.twobytwo.StreamingTileWriterTest
```

Expected: all equivalence and lifecycle tests pass, and all existing `TileBlenderTest`/`TilingManagerTest` tests remain green.

- [ ] **Step 6: Commit the tile writer**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/engine/StreamingTileWriter.kt app/src/main/java/com/rimuru/twobytwo/domain/engine/TileBlender.kt app/src/test/java/com/rimuru/twobytwo/StreamingTileWriterTest.kt
git commit -m "Stream blended tile rows"
```

---

### Task 4: Route large 4×/8× requests through streaming rows

**Files:**
- Create: `app/src/main/java/com/rimuru/twobytwo/domain/usecase/StreamingImageIo.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt`
- Modify: `app/src/test/java/com/rimuru/twobytwo/EnhanceScaleTest.kt`
- Modify: `app/src/test/java/com/rimuru/twobytwo/ExportPolicyTest.kt`

**Interfaces:**

```kotlin
interface StreamingImageIo {
    suspend fun encodeStreaming(
        width: Int,
        height: Int,
        destinationUri: String,
        policy: ExportPolicy,
        exifSourceUri: String?,
        produceRows: suspend (ByteArray) -> Unit,
    ): String
}
```

`EnhanceImage` must expose an internal companion predicate for deterministic tests:

```kotlin
internal fun shouldStreamOutput(scale: ScaleFactor, width: Long, height: Long): Boolean
```

- [ ] **Step 1: Add failing selection and streaming tests**

Add tests that:

- `shouldStreamOutput(ScaleFactor.X4, 8_000L, 8_000L)` is true;
- `shouldStreamOutput(ScaleFactor.X8, 8_000L, 8_000L)` is true;
- a 2× request is false;
- an X4 request below 64,000,000 output pixels is false;
- an X4 request at exactly 64,000,000 output pixels is true;
- a fake `StreamingImageIo` receives 4× and 8× row producers without its legacy `encode(ByteArray, …)` method being called;
- the fake receives the requested `ExportPolicy` and source URI;
- the emitted rows have the final dimensions and the progress ends in `DONE`.

Use a 2,048×2,048 source for the 4× streaming test and a 1,024×1,024 source for the 8× streaming test; each produces exactly 64,000,000 output pixels without allocating the full output. Use a fake row consumer that records row count and row size, not a full output buffer.

- [ ] **Step 2: Run the focused tests and verify RED**

```bash
./gradlew :app:testDebugUnitTest --tests com.rimuru.twobytwo.EnhanceScaleTest --tests com.rimuru.twobytwo.ExportPolicyTest
```

Expected: compilation failure because `StreamingImageIo`, the predicate, and the high-output branch do not exist.

- [ ] **Step 3: Implement the capability and branch**

Add `StreamingImageIo` as a separate interface so existing `ImageIo` fakes remain source-compatible.

In `EnhanceImage`:

- define `STREAMING_OUTPUT_MIN_PIXELS = 64_000_000L`;
- validate streaming output dimensions with long arithmetic, requiring each dimension to fit `Int` and each row buffer to fit `Int` bytes;
- avoid calling `outputBufferSize` for the streaming branch;
- keep the existing legacy branch unchanged for all other requests;
- after crop/restoration preparation, create `StreamingTileWriter` inside the suspend `produceRows` callback;
- upscale each tile with the existing X4/X8 model chaining, convert to RGBA, apply tile-local sharpen/face restoration before blending, and emit monotonic `PROCESSING_TILES` progress;
- call `finish()` and return the URI supplied by `StreamingImageIo`;
- preserve cancellation checks between every tile and row batch;
- retain the existing `backendUsed`, batch, and pass-status behavior.

The streaming branch must not call the legacy `ImageIo.encode(ByteArray, …)` method.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the same command. Expected: new tests and all existing scale/export tests pass.

- [ ] **Step 5: Commit the domain integration**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/usecase/StreamingImageIo.kt app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt app/src/test/java/com/rimuru/twobytwo/EnhanceScaleTest.kt app/src/test/java/com/rimuru/twobytwo/ExportPolicyTest.kt
git commit -m "Route large scale output through streaming rows"
```

---

### Task 5: Implement MediaStore/Spectrum encoding and cleanup

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/data/media/MediaStoreImageIo.kt`
- Modify: `app/src/test/java/com/rimuru/twobytwo/ExportPolicyTest.kt`
- Create: `app/src/androidTest/java/com/rimuru/twobytwo/LargeOutputExportTest.kt`

**Interfaces:**
- Consumes `StreamingImageIo.encodeStreaming(...)` from Task 4.
- Produces a confirmed MediaStore URI for PNG/JPEG/WebP and deletes temporary/pending artifacts on every failure.

- [ ] **Step 1: Add failing Android coverage**

Create instrumentation coverage that calls `MediaStoreImageIo` through `StreamingImageIo` for PNG, JPEG, and WebP using a deterministic synthetic source. Assert:

- the returned URI decodes to the requested output dimensions;
- the MIME type and extension match `ExportPolicy`;
- JPEG uses the clamped quality;
- EXIF allowlist behavior remains unchanged;
- a transcoder failure deletes the pending row and temporary file;
- no `INTERNET` permission is present in the merged manifest.

Use a temporary `Context` cache directory and clean it in the test teardown.

- [ ] **Step 2: Run the focused instrumentation test and verify RED**

```bash
./gradlew :app:connectedDebugAndroidTest --tests com.rimuru.twobytwo.LargeOutputExportTest
```

Expected: failure because `MediaStoreImageIo` does not implement `StreamingImageIo` and no Spectrum transcode path exists.

- [ ] **Step 3: Implement the MediaStore streaming path**

Make `MediaStoreImageIo` implement both `EnhanceImage.ImageIo` and `StreamingImageIo`. In `encodeStreaming`:

1. Validate `width`, `height`, and policy.
2. Create a unique temporary PNG file under `context.cacheDir`.
3. Insert a pending MediaStore row using the existing `ContentValues` policy.
4. Open the temporary file through `PngRowWriter`.
5. Invoke `produceRows { row -> pngWriter.writeRgbaRow(row) }`.
6. Finish/close the PNG writer.
7. Initialize/use Spectrum with the default PNG/JPEG/WebP plugins and transcode the temporary file to the pending row’s output stream using the existing `ExportPolicy` format and quality.
8. Apply the existing EXIF allowlist and post-write verification.
9. Clear `IS_PENDING`, verify the update count, and return the URI.
10. In `finally`, close Spectrum resources where applicable and delete the temporary file; on any exception, delete the pending URI.

Refactor the existing EXIF copy/verification code into one private helper used by both the legacy and streaming encode paths so metadata behavior cannot diverge.

- [ ] **Step 4: Run unit and instrumentation verification**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest --tests com.rimuru.twobytwo.LargeOutputExportTest
```

Expected: all unit tests and the focused MediaStore/Spectrum instrumentation test pass. If no device is available, run the unit suite and let GitHub Actions compile the Android test source.

- [ ] **Step 5: Commit the export integration**

```bash
git add app/src/main/java/com/rimuru/twobytwo/data/media/MediaStoreImageIo.kt app/src/test/java/com/rimuru/twobytwo/ExportPolicyTest.kt app/src/androidTest/java/com/rimuru/twobytwo/LargeOutputExportTest.kt
git commit -m "Add large output MediaStore transcoding"
```

---

### Task 6: Surface terminal memory errors and run the complete gate

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt`
- Create: `app/src/test/java/com/rimuru/twobytwo/EnhanceWorkerFailureTest.kt`
- Modify: `app/src/test/java/com/rimuru/twobytwo/EnhanceFailureTest.kt`
- Verify only: `app/src/main/java/com/rimuru/twobytwo/presentation/EnhanceViewModel.kt` already prefers the first nonblank WorkManager error; add no production change in this task.

**Interfaces:**
- Consumes structured streaming/codec errors from Tasks 4–5.
- Produces `Result.failure` with the real terminal message and no generic masking.

- [ ] **Step 1: Write the failing terminal-error test**

Create `EnhanceWorkerFailureTest` with this behavior:

```kotlin
@Test
fun `terminal memory error keeps its actionable message`() {
    val error = OutOfMemoryError("allocation failed")
    assertEquals("out of memory: allocation failed", EnhanceWorker.terminalFailureMessage(error))
}
```

Also add a test that a nonblank error reaches `EnhanceViewModel.failedState` unchanged; retain the existing assertion that cancellation and OOM never produce a successful result in `EnhanceFailureTest`.

- [ ] **Step 2: Run the focused tests and verify RED**

```bash
./gradlew :app:testDebugUnitTest --tests com.rimuru.twobytwo.EnhanceWorkerFailureTest --tests com.rimuru.twobytwo.EnhanceFailureTest
```

Expected: compilation/test failure because `terminalFailureMessage` is not defined.

- [ ] **Step 3: Implement the worker error contract**

Add this internal companion function to `EnhanceWorker`:

```kotlin
internal fun terminalFailureMessage(error: Throwable): String {
    val detail = error.message?.takeIf { it.isNotBlank() }
    return when {
        error is OutOfMemoryError && detail != null -> "out of memory: $detail"
        error is OutOfMemoryError -> "out of memory"
        detail != null -> detail
        else -> error::class.simpleName ?: "Enhancement failed"
    }
}
```

In `doWork`, catch `OutOfMemoryError` before the generic `Throwable` branch and return the existing `failure(...)` result with `terminalFailureMessage(e)`, current backend, batch index/total, and outcomes. This remains a terminal failed result; it must not become `Result.success`. Keep `CancellationException` propagation unchanged. The ViewModel already prefers the first nonblank output/progress error; change it only if the new test demonstrates otherwise.

- [ ] **Step 4: Run the focused tests and verify GREEN**

```bash
./gradlew :app:testDebugUnitTest --tests com.rimuru.twobytwo.EnhanceWorkerFailureTest --tests com.rimuru.twobytwo.EnhanceFailureTest
```

Expected: all focused tests pass.

- [ ] **Step 5: Run the complete verification gate**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Then inspect:

```bash
git diff --check
rg "INTERNET|android.permission.INTERNET|java.net|okhttp|retrofit" app/src/main app/build.gradle.kts
```

Expected: unit tests pass, debug assembly passes, no whitespace errors, and no network permission/client appears.

- [ ] **Step 6: Push the feature branch and monitor Actions**

```bash
git push origin feature/offline-restoration
```

Monitor the PR Build workflow until `:app:testDebugUnitTest` and `:app:assembleDebug` are green. Inspect the job log if either task fails; do not claim completion from local tests alone.

- [ ] **Step 7: Commit the final error/gate changes**

```bash
git add app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt app/src/test/java/com/rimuru/twobytwo/EnhanceWorkerFailureTest.kt app/src/test/java/com/rimuru/twobytwo/EnhanceFailureTest.kt
git commit -m "Preserve large output failure details"
```

Only stage files changed by Step 3; do not create an empty commit.

---

## Plan Self-Review

- **Spec coverage:** The plan covers the dependency/bootstrap, streaming PNG writer, row-band tile assembly, 4×/8× selection, no-downscale behavior, all output formats, EXIF/MediaStore cleanup, cancellation/error propagation, offline permission checks, unit tests, instrumentation tests, and CI verification.
- **Unresolved-marker scan:** No unresolved markers or vague implementation steps remain. All new interfaces and file paths are named before dependent tasks.
- **Type consistency:** `StreamingImageIo.encodeStreaming(..., produceRows)` is the same callback shape used by `EnhanceImage` and `MediaStoreImageIo`; `StreamingTileWriter.accept/finish` and `TileBlender.blendRow` are used by the domain integration; `PngRowWriter.writeRgbaRow/finish/close` are used by MediaStore and tests.
- **Known boundary:** Android instrumentation requires a device/emulator; GitHub Actions is authoritative for compilation, and the plan records that limitation instead of treating unavailable local execution as success.
