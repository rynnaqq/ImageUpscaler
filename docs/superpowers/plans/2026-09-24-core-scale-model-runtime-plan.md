# Core Scale and Model Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 8× chained upscaling, remove the fixed product cap while retaining the JVM representation guard, and make model/runtime failures explicit and testable.

**Architecture:** Extend `ScaleFactor`, `EnhanceRequest`, `EnhanceImage`, and `EnhanceWorker` without changing the existing tile/blend contract. Add a lazy `ModelRegistry` wrapper around the current ONNX session loading, and expose 8× as three chained 2× passes when no dedicated 8× model exists.

**Tech Stack:** Kotlin, ONNX Runtime Android, WorkManager, JUnit 4, existing pure-Kotlin image pipeline.

**Spec:** `docs/superpowers/specs/2026-09-24-offline-image-restoration-platform-design.md`

## Global Constraints

- No cloud/network/runtime model download behavior.
- No fixed 256 MP product cap; use only `Int.MAX_VALUE / 4` pixels as the technical buffer limit.
- `OutOfMemoryError` is terminal and must not enter the per-image fallback catch.
- Keep lazy model loading so missing assets do not initialize native ONNX.

---

### Task 1: Extend scale and request models

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/model/Models.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/engine/InferenceEngine.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/EnhanceScaleTest.kt`

**Interfaces:**
- Produces `ScaleFactor.X8` with multiplier `8`.
- Keeps `InferenceEngine.scaleFor` limited to actual model scales; 8× is expressed by chaining existing 2× model keys.

- [ ] **Step 1: Write failing tests**

Add tests asserting `ScaleFactor.X8.multiplier == 8`, `EnhanceImage.outputBufferSize` accepts 4×, and a 2× chained calculation produces 8× dimensions.

- [ ] **Step 2: Run the focused tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*EnhanceScaleTest'`

Expected: FAIL because `X8` does not exist.

- [ ] **Step 3: Implement the model changes**

Add `X8(8)` to `ScaleFactor`. Do not add a fake 8× ONNX key; add a helper that maps 8× requests to three 2× passes in the next task.

- [ ] **Step 4: Run the focused tests**

Run the same Gradle command and expect PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/model/Models.kt app/src/main/java/com/rimuru/twobytwo/domain/engine/InferenceEngine.kt app/src/test/java/com/rimuru/twobytwo/EnhanceScaleTest.kt
git commit -m "Add 8x scale model"
```

### Task 2: Replace the fixed cap with a technical buffer boundary

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt`
- Modify: `app/src/test/java/com/rimuru/twobytwo/EnhanceMemoryBudgetTest.kt`
- Modify: `app/src/test/java/com/rimuru/twobytwo/EnhancePreflightTest.kt`

**Interfaces:**
- `outputBufferSize(outWidth: Long, outHeight: Long, maxOutputMegapixels: Double)` remains callable for focused tests, but the default product cap is removed from the `run` API.
- The only unconditional rejection is a pixel count above `Int.MAX_VALUE / 4L` or a non-positive/invalid dimension.

- [ ] **Step 1: Write failing boundary tests**

Cover a 12 MP × 4× output, a 12 MP × 8× output when the dimensions fit the JVM limit, and a pixel count above `Int.MAX_VALUE / 4L` that fails before decode.

- [ ] **Step 2: Run the tests and confirm failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*EnhanceMemoryBudgetTest' --tests '*EnhancePreflightTest'`

Expected: FAIL because `run` still applies `DEFAULT_MAX_OUTPUT_MEGAPIXELS`.

- [ ] **Step 3: Implement the boundary**

Remove the fixed default megapixel constant from the runtime path. Keep the existing long arithmetic and `Int.MAX_VALUE / 4L` guard, and use a named technical-limit constant only if it improves the error message.

- [ ] **Step 4: Run the tests and confirm pass**

Run the same command and expect PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt app/src/test/java/com/rimuru/twobytwo/EnhanceMemoryBudgetTest.kt app/src/test/java/com/rimuru/twobytwo/EnhancePreflightTest.kt
git commit -m "Remove fixed output product cap"
```

### Task 3: Implement chained 8× tile processing

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/EnhanceScaleTest.kt`

**Interfaces:**
- `EnhanceImage.run` accepts `ScaleFactor.X8` and returns final dimensions `width * 8` and `height * 8`.
- Worker JSON serializes `8` as `X8` and preserves existing 2/4 values.

- [ ] **Step 1: Write a failing end-to-end fake-engine test**

Use a 2×2 fake image and a fake engine that records model keys. Assert X8 calls the 2× model three times per tile and the encoded dimensions are `16×16`.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*EnhanceScaleTest'`

Expected: FAIL because X8 is parsed as 2× and only one pass runs.

- [ ] **Step 3: Implement chained passes**

For X8, run the existing 2× path three times, carrying the intermediate tile dimensions between calls. Keep tile origins and final blend dimensions in input/output space unchanged.

- [ ] **Step 4: Update worker request parsing**

Parse `scale == 8` as `ScaleFactor.X8`; retain the existing 2 and 4 mappings.

- [ ] **Step 5: Run tests and assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS and a debug APK.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt app/src/test/java/com/rimuru/twobytwo/EnhanceScaleTest.kt
git commit -m "Implement chained 8x processing"
```

### Task 4: Make model loading explicitly lazy and observable

**Files:**
- Create: `app/src/main/java/com/rimuru/twobytwo/domain/engine/ModelProvider.kt`
- Create: `app/src/main/java/com/rimuru/twobytwo/data/engine/ModelRegistry.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/data/engine/OnnxInferenceEngine.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/ModelRegistryTest.kt`

**Interfaces:**
- `ModelProvider.load(key: InferenceEngine.ModelKey): ModelHandle?` returns a verified model or `null` without throwing for a missing asset.
- `ModelHandle` exposes `backendName`, `inputScale`, and `close()` through `AutoCloseable`.
- `ModelRegistry` is the data-layer implementation of `ModelProvider`.

- [ ] **Step 1: Write failing registry tests**

Test missing assets return `null`, checksum mismatch deletes the cache file, and a missing model does not request an ONNX environment.

- [ ] **Step 2: Run the focused tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*ModelRegistryTest'`

Expected: FAIL because `ModelRegistry` does not exist.

- [ ] **Step 3: Implement the registry**

Implement `ModelProvider` in the domain package and have `ModelRegistry` implement it. Move asset materialization and SHA-256 checks behind the registry. Keep `OnnxInferenceEngine` as the session implementation and keep its environment property lazy.

- [ ] **Step 4: Report backend/fallback state**

Include the selected backend and fallback reason in `JobProgress.backendUsed`; do not report neural success when a model was missing.

- [ ] **Step 5: Run tests and assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/engine/ModelProvider.kt app/src/main/java/com/rimuru/twobytwo/data/engine/ModelRegistry.kt app/src/main/java/com/rimuru/twobytwo/data/engine/OnnxInferenceEngine.kt app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt app/src/test/java/com/rimuru/twobytwo/ModelRegistryTest.kt
git commit -m "Add lazy model registry"
```

### Task 5: Make OOM terminal and verify the slice

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/EnhanceFailureTest.kt`

**Interfaces:**
- Normal per-image exceptions produce a failed `JobProgress` and continue the batch.
- `CancellationException` and `OutOfMemoryError` terminate the flow/job and are never converted to a successful `DONE` state.

- [ ] **Step 1: Write failing OOM tests**

Inject an `ImageIo` or engine failure of type `OutOfMemoryError` and assert the flow throws/terminates rather than emitting a successful output.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*EnhanceFailureTest'`

Expected: FAIL because the current catch handles `Throwable`.

- [ ] **Step 3: Implement terminal error handling**

Re-throw `OutOfMemoryError` before the per-image catch. Preserve the existing structured error path for ordinary exceptions.

- [ ] **Step 4: Run the full verification**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt app/src/test/java/com/rimuru/twobytwo/EnhanceFailureTest.kt
git commit -m "Treat allocation failures as terminal"
```
