# Export, Settings, and Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the user-facing feature set with configurable export, metadata policy, settings, history navigation, synchronized comparison controls, accessibility, and release verification.

**Architecture:** Extend `ImageIo` and the MVI state without introducing network dependencies. Keep settings in the existing per-session state, persist export/history metadata locally, and add UI only after the domain/export behavior is covered by tests.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, MediaStore, ExifInterface, WorkManager, existing `ComparisonTransform`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-24-offline-image-restoration-platform-design.md`

## Global Constraints

- No cloud API and no `INTERNET` permission.
- JPEG quality is clamped to 80–100; PNG and WebP quality controls are hidden or ignored explicitly.
- EXIF retention is opt-in and never silently strips or uploads metadata.
- Comparison zoom is capped at 400%; original and processed transforms remain synchronized.
- Preserve EN/ID localization and accessibility labels.

---

### Task 1: Add export format, quality, and metadata policy

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/model/Models.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/data/media/MediaStoreImageIo.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/ExportPolicyTest.kt`

**Interfaces:**

```kotlin
enum class OutputFormat { PNG, JPEG, WEBP }
data class ExportPolicy(
    val format: OutputFormat = OutputFormat.PNG,
    val jpegQuality: Int = 97,
    val keepExif: Boolean = true,
    val keepGps: Boolean = false,
)
```

- [ ] **Step 1: Write failing policy tests**

Cover JPEG clamp to 80–100, PNG/WebP quality behavior, EXIF-only, GPS-only, and no-metadata policies.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*ExportPolicyTest'`

Expected: FAIL because WebP and metadata policy are not modeled.

- [ ] **Step 3: Implement policy and MediaStore encoding**

Add WebP lossless/lossy selection using the existing bitmap compression API, validate quality before opening an output stream, and copy only the selected EXIF tags.

- [ ] **Step 4: Verify cleanup and publication**

Keep pending-row deletion on every failure and verify the MediaStore publish update before returning an output URI.

- [ ] **Step 5: Run tests and assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/model/Models.kt app/src/main/java/com/rimuru/twobytwo/domain/usecase/EnhanceImage.kt app/src/main/java/com/rimuru/twobytwo/data/media/MediaStoreImageIo.kt app/src/test/java/com/rimuru/twobytwo/ExportPolicyTest.kt
git commit -m "Add configurable export formats"
```

### Task 2: Add accelerator and model profile settings

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/domain/model/Models.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/presentation/EnhanceViewModel.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/presentation/AppRoot.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-in/strings.xml`
- Test: `app/src/test/java/com/rimuru/twobytwo/SettingsStateTest.kt`

**Interfaces:**
- `ModelProfile` values are `FAST` and `ULTRA`.
- `Accelerator` values remain `AUTO`, `GPU`, `NPU`, and `CPU`; UI only enables a backend when `isAvailable` reports support.
- `UiState` stores profile, format, quality, metadata, and cache limit.

- [ ] **Step 1: Write failing state tests**

Assert default values, backend availability filtering, quality clamping, and JSON round-trip through worker input.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*SettingsStateTest'`

Expected: FAIL because the new settings are absent.

- [ ] **Step 3: Implement state and serialization**

Add immutable state fields and named intents. Keep the existing `Auto → CPU/GPU/NPU → fallback` behavior explicit in status text.

- [ ] **Step 4: Add localized controls**

Add EN/ID labels and descriptions for Fast/Ultra, output format, quality, EXIF, GPS, and cache cleanup.

- [ ] **Step 5: Run tests and assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/domain/model/Models.kt app/src/main/java/com/rimuru/twobytwo/presentation/EnhanceViewModel.kt app/src/main/java/com/rimuru/twobytwo/presentation/AppRoot.kt app/src/main/res/values/strings.xml app/src/main/res/values-in/strings.xml app/src/test/java/com/rimuru/twobytwo/SettingsStateTest.kt
git commit -m "Add restoration settings"
```

### Task 3: Add history navigation and version actions

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/presentation/EnhanceViewModel.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/presentation/AppRoot.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/data/history/HistoryStore.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/HistoryUiStateTest.kt`

**Interfaces:**
- `UiState.history: List<HistoryRecord>`
- Intents: `OpenHistory`, `RestoreHistorySettings`, `DuplicateHistory`, `ExportHistoryItem`.

- [ ] **Step 1: Write failing UI-state tests**

Assert history loads newest first, restoring settings does not change an old output, and duplicating creates a child ID with the same settings.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*HistoryUiStateTest'`

Expected: FAIL because history intents/state are absent.

- [ ] **Step 3: Implement history state/actions**

Load records on screen entry, expose parent/child relationships, and route export through the existing output/share flow.

- [ ] **Step 4: Run tests and assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/presentation/EnhanceViewModel.kt app/src/main/java/com/rimuru/twobytwo/presentation/AppRoot.kt app/src/main/java/com/rimuru/twobytwo/data/history/HistoryStore.kt app/src/test/java/com/rimuru/twobytwo/HistoryUiStateTest.kt
git commit -m "Add history version actions"
```

### Task 4: Finish synchronized comparison and accessibility

**Files:**
- Modify: `app/src/main/java/com/rimuru/twobytwo/presentation/ComparisonTransform.kt`
- Modify: `app/src/main/java/com/rimuru/twobytwo/presentation/AppRoot.kt`
- Test: `app/src/test/java/com/rimuru/twobytwo/ComparisonTransformTest.kt`

**Interfaces:**
- `ComparisonTransform` clamps scale to `0.25..4.0` and exposes the same pan/zoom state to both image layers.
- Split position remains in normalized `[0f, 1f]` coordinates.

- [ ] **Step 1: Write failing transform tests**

Cover 400% clamp, 25% minimum, synchronized pan deltas, split boundaries, and reset behavior.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :app:testDebugUnitTest --tests '*ComparisonTransformTest'`

Expected: FAIL for any missing 400% or synchronization behavior.

- [ ] **Step 3: Implement controls**

Add zoom/pan gesture handling, reset action, split accessibility semantics, and content descriptions for both image layers.

- [ ] **Step 4: Run tests and assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rimuru/twobytwo/presentation/ComparisonTransform.kt app/src/main/java/com/rimuru/twobytwo/presentation/AppRoot.kt app/src/test/java/com/rimuru/twobytwo/ComparisonTransformTest.kt
git commit -m "Polish comparison inspector"
```

### Task 5: Run release verification

**Files:**
- Modify: `app/src/main/res/values/strings.xml` only if QA finds a missing label.
- Modify: `app/src/main/res/values-in/strings.xml` only if QA finds a missing label.

- [ ] **Step 1: Verify manifest policy**

Run: `rg "INTERNET" app/src/main/AndroidManifest.xml app/src/main`

Expected: no permission or network client.

- [ ] **Step 2: Run CI-equivalent checks**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: PASS and a generated debug APK.

- [ ] **Step 3: Run airplane-mode smoke tests**

Process one JPEG and one 10-image batch with Wi-Fi, mobile data, and airplane mode enabled. Verify output URI, progress, cancellation, and history persistence.

- [ ] **Step 4: Verify model and cache evidence**

Confirm model checksum/license records, cache eviction totals, and that cleanup leaves MediaStore outputs intact.

- [ ] **Step 5: Commit only QA fixes**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-in/strings.xml
git commit -m "Fix release QA labels"
```
