# Bounded Tile Spooling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make 4×/8× streaming assembly use bounded RAM by spooling tile rows to disk, while adding storage preflight, streamed sharpening, setup-time OOM handling, and release CI gates.

**Architecture:** Keep the existing row-major tile generation and temporary-PNG/Spectrum export. Replace `StreamingTileWriter`’s in-memory row groups and `EnhanceImage.pendingRows` with per-group raw-RGBA spool files; the writer pulls one row directly into the encoder-owned buffer. Production passes the app cache directory to the writer and checks available space before the first streamed tile.

**Tech Stack:** Kotlin 2.0.20, Android API 26+, WorkManager, MediaStore, JUnit 4, Spectrum 1.3.0, `StatFs`, `RandomAccessFile`.

**Spec:** `docs/superpowers/specs/2026-09-25-bounded-tile-spooling-design.md`

## Global Constraints

- Keep the app offline: no `INTERNET` permission, network client, upload, or runtime download.
- Never silently downscale, crop, or substitute an output format.
- Preserve sequential batch order, progress, cancellation, terminal `OutOfMemoryError`, EXIF allowlists, MediaStore publication, history/cache behavior, and the legacy small-output path.
- Do not change tile geometry, model selection, or restoration semantics beyond tile-local sharpening and bounded spooling.
- Use Kotlin/JVM 17-compatible code and existing test style.
- Do not add production comments.
- Local Android tooling may be unavailable; CI must provide Android compilation evidence.
- Do not stage or commit the untracked design/plan documents without explicit user instruction.

---

## File Map

Create:

- `docs/superpowers/specs/2026-09-25-bounded-tile-spooling-design.md` — approved redesign.
- `app/src/test/java/com/rimuru/twobytwo/StreamingScratchBudgetTest.kt` — checked storage arithmetic/preflight boundary tests.
- `app/src/test/java/com/rimuru/twobytwo/StreamingTileWriterTest.kt` — replace/extend with 4×/8× equivalence, bounded-group, and cleanup cases.

Modify:

- `app/src/main/java/com/rimuru/twobytwo/domain/engine/TileBlender.kt` — add source-row blending without changing legacy math.
- `app/src/main/java/com/rimuru/twobytwo/domain/engine/StreamingTileWriter.kt` — replace live tile arrays/row callback with disk-backed spools and pull API.
- `app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt` — remove `pendingRows`, pull rows, add scratch directory/budget, and sharpen streamed tiles.
- `app/src/main/java/com/rimuru/twobytwo/data/media/MediaStoreImageIo.kt` — check `StatFs` capacity before rows and improve failure cleanup diagnostics.
- `app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt` — move setup into guarded lifecycle and close an initialized engine.
- `app/src/test/java/com/rimuru/twobytwo/EnhanceScaleTest.kt` — verify wide streamed requests do not call legacy encode and complete row counts.
- `app/src/test/java/com/rimuru/twobytwo/ExportPolicyTest.kt` — use the default sharpen ceiling in the streamed face/sharpen ordering test.
- `app/src/test/java/com/rimuru/twobytwo/EnhanceWorkerFailureTest.kt` — cover setup-time cleanup/structured OOM helper behavior.
- `app/src/androidTest/java/com/rimuru/twobytwo/LargeOutputExportTest.kt` — identify spool/temp files by prefix and add format signature checks where practical.
- `.github/workflows/build.yml` — compile/package Android tests and assemble release.

---

### Task 1: Replace in-memory row groups with disk-backed tile spools

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/engine/TileBlender.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/engine/StreamingTileWriter.kt`
- Modify: `app/src/test/java/com/rimuru/twobytwo/StreamingTileWriterTest.kt`

**Interfaces:**
- Add `TileBlender.blendRow(sourceRow, tile, tiling, outRow, outY)`; the existing full-tile overload delegates to shared pixel math.
- Replace the writer callback with `isRowReady(outY)`, `writeRow(outRow, outY)`, `finish()`, and `Closeable.close()`.

- [ ] **Step 1: Write RED tests for bounded retention and exact bytes**

Add tests that create a `File.createTempFile`-backed directory, feed deterministic RGBA tiles in `tiling.tiles()` order, and call `writeRow` for every output row. Compare each row with the existing `TileBlender.blend` result for 4× and 8× fixtures with at least two tile columns, two tile rows, unequal edge tiles, and varying alpha. Assert:

```kotlin
assertEquals(1, writer.activeGroupCount)
assertTrue(writer.maxActiveGroupCount <= 3)
```

Three is the true bound: the last tile row is pulled back to the image edge, so a third group can cover the same output row, and the enforced `tileSize > overlap * 2` caps the count there. Add a production-geometry fixture (512-pixel tiles, 32-pixel overlap, image height 1480) that walks every output row, asserts three groups are simultaneously active there, and never exceeds three. Accepted tiles are released to disk on `accept`, so there is no retained-tile counter to assert.

After `finish()` and `close()`, assert no `rimuru2x_tiles_` files remain. Add a partial-accept test that closes the writer and verifies spool deletion, plus duplicate/out-of-order/missing-tile failures.

- [ ] **Step 2: Run the focused test to verify RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.rimuru.twobytwo.StreamingTileWriterTest
```

Expected: compilation failure because the new constructor/API and source-row overload do not exist.

- [ ] **Step 3: Implement the minimal spool writer**

Implement these concrete rules:

1. Group immutable tile metadata by `row` in column order and compute each group’s `startY`/`endY` from input coordinates and scale.
2. On the first tile of a group, create `rimuru2x_tiles_*.rgba` under the supplied directory and open `RandomAccessFile` in read/write mode.
3. On every `accept`, validate exact tile order and `(inW*scale)*(inH*scale)*4` size, seek to the group end, write the tile bytes, and store only its offset/length.
4. `isRowReady(y)` returns true when the first incomplete group starts below `y`; otherwise the caller must generate more tiles.
5. `writeRow(outRow, y)` requires the next exact output row, clears it, reads only `tile.inW*scale*4` bytes for each covering tile, and calls the new source-row blend overload. Increment `nextOutputY` and close/delete groups whose `endY <= y + 1`.
6. `finish()` requires all groups/tiles and all output rows consumed. `close()` is idempotent, closes files, and deletes every spool file even after failure.
7. Keep only metadata, file handles, and one source-row scratch buffer; expose `activeGroupCount` and `maxActiveGroupCount` for tests, and do not retain accepted tile `ByteArray`s or emit queued rows. Do not keep a compatibility row-callback path: the writer is pull-only.

- [ ] **Step 4: Run focused and existing blend tests to verify GREEN**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.rimuru.twobytwo.StreamingTileWriterTest --tests com.rimuru.twobytwo.TileBlenderTest --tests com.rimuru.twobytwo.TilingManagerTest
```

Expected: all tests pass, including byte-for-byte 4×/8× equivalence and spool cleanup.

- [ ] **Step 5: Commit the isolated spool change**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/engine/TileBlender.kt app/src/main/java/com/rimuru/twobytwo/domain/engine/StreamingTileWriter.kt app/src/test/java/com/rimuru/twobytwo/StreamingTileWriterTest.kt
git commit -m "Spool streamed tile rows to disk"
```

---

### Task 2: Pull streamed rows and preflight scratch capacity

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/usecase/StreamingImageIo.kt`
- Create: `app/src/test/java/com/rimuru/twobytwo/StreamingScratchBudgetTest.kt`
- Modify: `app/src/test/java/com/rimuru/twobytwo/EnhanceScaleTest.kt`
- Modify: `app/src/test/java/com/rimuru/twobytwo/ExportPolicyTest.kt`

**Interfaces:**
- Add `StreamingImageIo.checkScratchCapacity(requiredBytes: Long)` with a default no-op so existing fakes remain source-compatible.
- Add `EnhanceImage(..., streamingScratchDirectory: File = File(System.getProperty("java.io.tmpdir") ?: "."))`.
- Add an internal checked scratch-budget helper based on active tile-group overlap plus a conservative PNG bound.

- [ ] **Step 1: Write RED tests**

Add pure tests for the budget helper: it must reject non-positive/overflowing dimensions, return the exact total of peak raw overlap plus the five-bytes-per-pixel PNG bound plus the fixed 1 MiB and 16 MiB margins, and remain finite for 4×/8× dimensions above `Int.MAX_VALUE`. Compute the expected overlap independently in the test (a start/end event sweep over the same tile plan) and assert equality, not a lower bound, so the fixed margins cannot satisfy the assertion on their own; give the tall-input sweep test a JUnit timeout so a reintroduced quadratic scan fails. Add a fake `StreamingImageIo` that records `checkScratchCapacity` and assert it is called before the first row callback. Change `streamed face restoration runs before tile sharpening` to omit `sharpenMaxMegapixels = Double.MAX_VALUE`; it must still show sharpening under the production default.

- [ ] **Step 2: Run focused tests to verify RED**

```bash
./gradlew :app:testDebugUnitTest --tests com.rimuru.twobytwo.StreamingScratchBudgetTest --tests com.rimuru.twobytwo.EnhanceScaleTest --tests com.rimuru.twobytwo.ExportPolicyTest
```

Expected: compilation/test failure until the budget hook and pull API are integrated.

- [ ] **Step 3: Implement the pull loop and budget**

In `EnhanceImage`:

1. Add the optional scratch directory as the final constructor parameter with a system-temp default.
2. After building `TilingManager` and before `encodeStreaming`, compute checked `maxSpoolBytes` from tile metadata and a conservative PNG bound; call `streamingImageIo.checkScratchCapacity(total)`.
3. Replace `pendingRows` and the writer callback with one `StreamingTileWriter` per export.
4. For each encoder row request, while `!writer.isRowReady(rowsDone)`, generate the next tile, apply existing face restoration then `sharpenTiles`, and call `writer.accept`; never retain a tile or row queue.
5. Call `writer.writeRow(rowBuffer, rowsDone)`, increment `rowsDone`, and preserve all cancellation/progress checks.
6. Call `writer.finish()` after the encoder consumes all rows and always call `writer.close()` in `finally`.
7. Set `sharpenTiles = sharpenOutput && (streamOutput || outMp <= sharpenMaxMegapixels)`.

- [ ] **Step 4: Run focused tests to verify GREEN**

Run the same focused command. Expected: budget, selection, row count, default sharpen ordering, cancellation, and legacy tests all pass without a full output buffer.

- [ ] **Step 5: Commit the domain integration**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt app/src/main/java/com/rimuru/twobytwo/domain/usecase/StreamingImageIo.kt app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt app/src/test/java/com/rimuru/twobytwo/StreamingScratchBudgetTest.kt app/src/test/java/com/rimuru/twobytwo/EnhanceScaleTest.kt app/src/test/java/com/rimuru/twobytwo/ExportPolicyTest.kt
git commit -m "Bound streamed tile assembly memory"
```

---

### Task 3: Enforce Android scratch capacity and cleanup

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/data/media/MediaStoreImageIo.kt`
- Modify: `app/src/androidTest/java/com/rimuru/twobytwo/LargeOutputExportTest.kt`

**Interfaces:**
- `checkScratchCapacity(requiredBytes)` uses `StatFs(context.cacheDir.absolutePath).availableBytes` and throws `insufficient temporary storage: need X bytes, have Y bytes` before `encodeStreaming` invokes its row callback.
- Existing `encodeStreaming` row contract and MediaStore/Spectrum behavior remain unchanged.

- [ ] **Step 1: Write RED Android/static tests**

Add a testable capacity helper or injectable `StorageProbe` whose insufficient-space result throws before the row callback increments. Update the Android test to inspect PNG/JPEG/WebP magic bytes and locate `rimuru2x_stream_`/`rimuru2x_tiles_` files by prefix rather than assuming a singleton cache file.

- [ ] **Step 2: Run the focused checks to verify RED**

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: the new capacity/cleanup assertions fail until the production hook and test helpers exist.

- [ ] **Step 3: Implement the preflight and cleanup**

Add `checkScratchCapacity` before creating the pending MediaStore row/temp PNG. Keep the existing temp-file and pending-row deletion paths, but surface a cleanup failure as an explicit `IllegalStateException` when deletion returns false or throws while no original exception is being propagated. Do not retry with a Bitmap or substitute a format.

- [ ] **Step 4: Run Android compilation and unit checks to verify GREEN**

```bash
./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
```

Expected: Android test sources compile and unit tests pass. Device execution remains a separate runtime check.

- [ ] **Step 5: Commit the Android export fix**

```bash
git add app/src/main/java/com/rimuru/twobytwo/data/media/MediaStoreImageIo.kt app/src/androidTest/java/com/rimuru/twobytwo/LargeOutputExportTest.kt
git commit -m "Preflight large export storage"
```

---

### Task 4: Guard worker setup and terminal cleanup

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt`
- Modify: `app/src/test/java/com/rimuru/twobytwo/EnhanceWorkerFailureTest.kt`

**Interfaces:**
- `doWork()` catches setup-time OOM and returns `Result.failure` with `terminalFailureMessage`.
- Any engine assigned before profile/accelerator setup is closed by the outer `finally` through `closeSafely`.

- [ ] **Step 1: Write RED tests**

Add a focused test for a setup helper that throws `OutOfMemoryError` before normal work and verify the returned message is `out of memory: ...`; retain cleanup cancellation and non-cancellation tests.

- [ ] **Step 2: Run the focused test to verify RED**

```bash
./gradlew :app:testDebugUnitTest --tests com.rimuru.twobytwo.EnhanceWorkerFailureTest
```

Expected: the new setup-boundary test fails before the guarded lifecycle is implemented.

- [ ] **Step 3: Implement the outer lifecycle**

Move request decoding, tier/registry creation, and engine creation inside the `try`/`catch` boundary. Declare `var engine: OnnxInferenceEngine? = null` outside it, assign the newly created engine before chained configuration, use a local non-null engine in the body, and close the nullable instance in the outer `finally`. Preserve `CancellationException` propagation, terminal OOM structured failure, progress, and batch data.

- [ ] **Step 4: Run focused tests to verify GREEN**

```bash
./gradlew :app:testDebugUnitTest --tests com.rimuru.twobytwo.EnhanceWorkerFailureTest --tests com.rimuru.twobytwo.EnhanceFailureTest
```

Expected: all worker/domain failure tests pass.

- [ ] **Step 5: Commit the worker fix**

```bash
git add app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt app/src/test/java/com/rimuru/twobytwo/EnhanceWorkerFailureTest.kt
git commit -m "Guard enhancement worker setup failures"
```

---

### Task 5: Add release CI gates and run the complete verification ladder

**Files:**
- Modify: `.github/workflows/build.yml`
- Verify: all changed production/test files and the approved design/plan documents.

- [ ] **Step 1: Add CI steps**

After the existing debug assembly, add:

```yaml
- name: Assemble Android tests
  run: ./gradlew :app:assembleDebugAndroidTest --stacktrace
- name: Assemble release APK
  run: ./gradlew :app:assembleRelease --stacktrace
```

Do not add a network permission or runtime test dependency.

- [ ] **Step 2: Run the complete local/CI ladder**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:assembleRelease
```

If local Gradle/JDK/SDK is unavailable, run the same four commands through GitHub Actions and inspect the complete job log.

- [ ] **Step 3: Verify the final diff and constraints**

Run `git diff --check`, confirm no `INTERNET` permission/network client, verify the branch contains the intended commits, and leave the two untracked design/plan documents unstaged.

- [ ] **Step 4: Commit the CI gate**

```bash
git add .github/workflows/build.yml
git commit -m "Gate Android tests and release build"
```

- [ ] **Step 5: Final review**

Run a fresh read-only review against base `71cfffdd1601f41be6e3cd2df44e2ed51e01f57a` and the new head. Require zero Critical/Important findings before claiming completion; record the Android instrumentation limitation if no device is available.
