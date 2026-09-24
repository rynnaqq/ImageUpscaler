# Restoration Passes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add independent denoise/deblur, scratch repair, colorization, and face restoration passes with model-backed and classical/no-op fallbacks.

**Architecture:** Define a pure-Kotlin `ImagePass` contract over the existing RGBA byte representation. `EnhanceImage` invokes enabled passes in the approved order before upscaling, except face restoration, which runs on upscaled face crops. Each pass reports whether it ran or used a fallback.

**Tech Stack:** Kotlin, ONNX Runtime Android, existing `TensorCodec`, `TileBlender`, `ImageOps`, and JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-24-offline-image-restoration-platform-design.md`

## Global Constraints

- All processing is local; no network or runtime model download.
- A missing model must select a documented classical/no-op fallback, not fail the whole app.
- Face restoration must blend by detected face crop and skip faces below the minimum size.
- Normal pass failures remain per-image failures; allocation failures remain terminal per the core plan.

---

### Task 1: Define the pass contract and pass context

**Files:**
- Create: `app/src/main/java/com/rimuru/twobytwo/domain/engine/ImagePass.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/model/Models.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/ImagePassTest.kt`

**Interfaces:**

```kotlin
interface ImagePass {
    val id: String
    fun apply(image: RgbaImage, context: PassContext): PassResult
}

data class RgbaImage(val pixels: ByteArray, val width: Int, val height: Int)
data class PassContext(val strength: Int, val models: ModelProvider)
data class PassResult(val image: RgbaImage, val usedFallback: Boolean, val detail: String)
```

- [ ] **Step 1: Write failing contract tests**

Assert disabled passes return the same pixel reference, enabled no-op passes report a fallback, and invalid strength is rejected at the request boundary.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*ImagePassTest'`

Expected: FAIL because the pass types do not exist.

- [ ] **Step 3: Implement the minimal contract**

Add the types above without creating a registry implementation in this task. Keep the domain package free of Android imports.

- [ ] **Step 4: Run the focused test**

Run the same command and expect PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/engine/ImagePass.kt app/src/main/java/com/rimuru/twobytwo/domain/model/Models.kt app/src/test/java/com/rimuru/twobytwo/ImagePassTest.kt
git commit -m "Define image restoration pass contract"
```

### Task 2: Add denoise and deblur passes

**Files:**
- Create: `app/src/main/java/com/rimuru/twobytwo/domain/engine/DenoisePass.kt`
- Create: `app/src/main/java/com/rimuru/twobytwo/domain/engine/DeblurPass.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/RestorationMathTest.kt`

**Interfaces:**
- `DenoisePass(strength: Int)` maps 0 to no-op and nonzero values to a bounded classical filter unless a verified model is available.
- `DeblurPass(strength: Int)` uses a conservative edge-sharpening fallback and a model key when available.

- [ ] **Step 1: Write failing behavior tests**

Use a constant image and a checkerboard image. Assert denoise strength `0` is bitwise neutral, higher strength reduces variance, and deblur does not change dimensions.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*RestorationMathTest'`

Expected: FAIL because the passes do not exist.

- [ ] **Step 3: Implement classical fallbacks**

Reuse `ImageOps` primitives where possible. Clamp all strength values to `0..100` and avoid an extra full-image allocation in the strength-zero path.

- [ ] **Step 4: Wire pass order**

Invoke denoise then deblur before `processOne` tiling when their request flags are enabled. Include pass IDs and fallback state in `JobProgress.backendUsed`.

- [ ] **Step 5: Run tests and assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/engine/DenoisePass.kt app/src/main/java/com/rimuru/twobytwo/domain/engine/DeblurPass.kt app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt app/src/test/java/com/rimuru/twobytwo/RestorationMathTest.kt
git commit -m "Add denoise and deblur passes"
```

### Task 3: Add scratch repair and colorization passes

**Files:**
- Create: `app/src/main/java/com/rimuru/twobytwo/domain/engine/ScratchRepairPass.kt`
- Create: `app/src/main/java/com/rimuru/twobytwo/domain/engine/ColorizePass.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/data/engine/ModelManifest.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/engine/InferenceEngine.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/ScratchColorPassTest.kt`

**Interfaces:**
- Add `InferenceEngine.ModelKey.SCRATCH_REPAIR` and `COLORIZE` with scale `1`.
- `ScratchRepairPass` falls back to a bounded local defect mask/inpaint routine when no model is verified.
- `ColorizePass` falls back to a deterministic luminance-preserving tint routine only when explicitly enabled without a model; it must not claim semantic colorization.

- [ ] **Step 1: Write failing tests**

Assert disabled passes are no-ops, scratch repair changes only masked pixels, colorization preserves dimensions and alpha, and missing models report `usedFallback = true`.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScratchColorPassTest'`

Expected: FAIL because the passes and model keys do not exist.

- [ ] **Step 3: Implement model keys and fallbacks**

Add manifest entries and deterministic classical fallbacks. Keep model-backed code behind the registry and never download assets.

- [ ] **Step 4: Run tests and assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/engine/ScratchRepairPass.kt app/src/main/java/com/rimuru/twobytwo/domain/engine/ColorizePass.kt app/src/main/java/com/rimuru/twobytwo/data/engine/ModelManifest.kt app/src/main/java/com/rimuru/twobytwo/domain/engine/InferenceEngine.kt app/src/test/java/com/rimuru/twobytwo/ScratchColorPassTest.kt
git commit -m "Add scratch repair and colorization passes"
```

### Task 4: Add face detection/restoration pass

**Files:**
- Create: `app/src/main/java/com/rimuru/twobytwo/domain/engine/FaceRestorePass.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/FaceRestorePassTest.kt`

**Interfaces:**
- `FaceRestorePass.apply` accepts an upscaled `RgbaImage`, detects faces, skips boxes below 48 px, restores each crop, and feathers it back into the source image.
- Strength `0` preserves the original crop; higher strength blends more restored output.

- [ ] **Step 1: Write failing tests**

Use a fake detector with one large and one 32 px face. Assert the small face is unchanged, the large face is restored, and the output dimensions/alpha remain unchanged.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*FaceRestorePassTest'`

Expected: FAIL because the face pass does not exist.

- [ ] **Step 3: Implement crop extraction and blending**

Extract padded face crops, run the model or no-op fallback, resize back to the detected box, and blend with a cosine feather mask. Record skipped-face count in progress data.

- [ ] **Step 4: Wire the pass after upscaling**

Run face restoration after the final tile blend and before export. Do not run it on the full image through a second full-resolution allocation.

- [ ] **Step 5: Run tests and assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/engine/FaceRestorePass.kt app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt app/src/main/java/com/rimuru/twobytwo/data/work/EnhanceWorker.kt app/src/test/java/com/rimuru/twobytwo/FaceRestorePassTest.kt
git commit -m "Add face restoration pass"
```

### Task 5: Verify the restoration slice

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-in/strings.xml`
- Test: `app/src/test/java/com/rimuru/twobytwo/RestorationMathTest.kt`

- [ ] **Step 1: Add localized pass labels and fallback descriptions**

Add English and Indonesian strings for denoise, deblur, scratch repair, colorization, face restoration, and fallback status.

- [ ] **Step 2: Run the full verification**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 3: Check offline policy**

Run: `rg "INTERNET|https?://" app/src/main app/build.gradle.kts`

Expected: no runtime network permission or cloud client reference; documentation URLs are not added to app code.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-in/strings.xml
git commit -m "Localize restoration pass status"
```
