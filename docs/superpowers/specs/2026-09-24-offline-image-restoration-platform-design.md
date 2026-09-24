# Offline Image Restoration Platform Design

**Status:** Approved in conversation on 2026-09-24

## Goal

Expand Rimuru 2x into an offline-first image restoration platform with 2×/4×/8× upscaling, independent restoration passes, batch processing, crop presets, local history, cache controls, and configurable export. The app must never upload image data or require a cloud API.

## Scope

- 2×, 4×, and 8× output paths.
- Neural super-resolution when approved local model assets are present.
- Denoise/de-JPEG, deblur, scratch repair, colorization, and face restoration passes.
- Fast bicubic/classical fallbacks when a model is unavailable or a device cannot run a backend.
- Batch selection and sequential background processing for 10–50 images.
- Crop presets: 1:1, 9:16, 4:5, 4×6, and 8×10.
- Synchronized before/after split comparison, zoom, and pan.
- Local export history, version relationships, cache limits, and cache cleanup.
- PNG, JPEG quality 80–100, and WebP export.
- EXIF date, camera, and GPS retention controls.
- CPU, Vulkan, and NNAPI selection where the installed runtime/device supports them, with automatic fallback.

## Non-goals

- Cloud inference, cloud API settings, accounts, uploads, and network permissions.
- Automatic downloading of models at runtime.
- Claiming neural quality when a model asset is absent or fails validation.

## Constraints

- The release manifest must contain no `INTERNET` permission.
- Model files are local assets or app-private cached files verified by SHA-256.
- The fixed product output cap is removed. The only output limit is the technical Kotlin/JVM representation limit: `Int.MAX_VALUE / 4` pixels, approximately 536.87 MP for a four-byte RGBA buffer. Device allocation failures are reported as job failures; there is no silent auto-downscale.
- `OutOfMemoryError` is terminal for the current image and is never swallowed as a successful fallback.
- Existing tiling, feather blending, WorkManager, MediaStore, and MVI patterns remain the foundation.

## Architecture

### Domain processing

`EnhanceImage` remains the orchestration boundary. It receives an `EnhanceRequest`, measures the source before decoding, applies the configured passes in order, upscales tiles, blends the result, and delegates export to `ImageIo`.

A new `ImagePass` interface represents an optional transformation over an RGBA image buffer. Each pass has a pure-Kotlin implementation for tests and a model-backed implementation behind `ModelRegistry`. Missing models select a documented classical/no-op fallback and report the backend used.

The pass order is:

1. Crop selection.
2. Denoise/de-JPEG.
3. Deblur.
4. Scratch repair.
5. Colorization.
6. Tiled super-resolution.
7. Face detection and restoration on upscaled face crops.
8. Final blend and export.

### Scale handling

`ScaleFactor` gains `X8`. Existing 2× and 4× model keys remain unchanged. An 8× request uses three chained 2× passes unless a dedicated 8× model is registered later. The output dimension is calculated from the selected scale and is covered by tests for 2×, 4×, and 8×.

### Model loading

`ModelRegistry` owns lazy sessions, asset materialization, SHA-256 validation, and backend availability. ONNX initialization is lazy so a missing model can use the classical path without loading the native runtime. Model keys cover super-resolution, face detection/restoration, denoise, deblur, scratch repair, and colorization. Model binaries are bundled only after license, size, and device benchmarks are approved.

### Failure handling

`EnhanceImage` emits structured `JobProgress` values with an error message and stage. The worker stores the error in WorkManager output data, and the ViewModel displays the real failure rather than a generic message. Per-image failures remain isolated for normal batch items; cancellation and `OutOfMemoryError` are terminal.

## Persistence and cache

- `HistoryStore` stores one metadata record per job under `filesDir/history` and references MediaStore output URIs instead of duplicating image bytes.
- Records include source URI/hash, settings snapshot, operation pass results, output dimensions, output URI, timestamps, parent job ID, and cache paths.
- A history version is a new job record with `parentJobId`; existing outputs are never overwritten.
- `RenderCacheStore` stores intermediate artifacts under `cacheDir/renders`, tracks last-access time, and evicts least-recently-used files when the configured 500 MB–2 GB limit is exceeded.
- Cache cleanup never deletes MediaStore outputs or history metadata.

## UI and interaction

- Configuration adds 8×, operation toggles, model profile, accelerator, crop preset, format, quality, metadata, and cache settings.
- Batch selection accepts 10–50 images and presents a deterministic queue with per-item status.
- The comparison viewer keeps original and processed transforms synchronized, supports pan and zoom up to 400%, and preserves the existing split slider.
- History provides restore, duplicate-settings, export, and parent-version navigation.
- All controls retain accessible labels, minimum touch targets, and localized strings.

## Export

`ImageIo` supports PNG, JPEG quality 80–100, and WebP. EXIF retention is explicit: date/camera/GPS fields are copied only when enabled. Output names are unique, MediaStore publication is verified, and failed inserts are deleted.

## Testing strategy

### Pure unit tests

- Scale dimensions and chained 8× output.
- Pass ordering, no-op behavior, and fallback selection.
- Crop math for every supported preset.
- Output buffer technical limit and arithmetic boundaries.
- Batch isolation, cancellation, error propagation, and OOM terminal handling.
- History serialization, parent versions, and LRU cache eviction.
- EXIF policy and filename generation.

### Android tests

- WorkManager foreground execution, progress, cancellation, and failure output.
- MediaStore PNG/JPEG/WebP creation, publication, and cleanup.
- Photo Picker and share-to-app input for batch jobs.
- Split slider, synchronized zoom/pan, crop controls, and history navigation.
- CPU, Vulkan, and NNAPI capability fallback on representative devices/emulators.

### Release gates

- No cloud/network code or `INTERNET` permission.
- Model checksums and licenses documented before assets are bundled.
- Unit tests and debug APK assembly pass.
- Manual airplane-mode run completes for a single image and a batch.

## Delivery slices

1. **Core scale and model runtime:** X8, model registry, lazy backend selection, output arithmetic, and device-safe failure reporting.
2. **Restoration passes:** denoise/deblur, scratch repair, colorization, and face restoration with fallbacks.
3. **Workflow and persistence:** batch queue, crop presets, history/versioning, and cache controls.
4. **Export and polish:** WebP/JPEG quality, EXIF policy, settings, comparison UX, accessibility, and device QA.

Each slice must leave the app buildable and usable with its existing classical fallback.
