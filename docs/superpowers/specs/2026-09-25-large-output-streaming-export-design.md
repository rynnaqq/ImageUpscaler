# Large-Output Streaming Export Design

**Date:** 2026-09-25  
**Status:** Design approved in conversation  
**Scope:** Fix 4× and 8× enhancement failures for large photos without silently downscaling or changing the requested output format.

## Problem

`EnhanceImage` currently allocates one full-resolution RGBA `ByteArray` for the output and `MediaStoreImageIo.encode` creates a second full-resolution Android `Bitmap`. A large photo at 4× can exhaust the app heap; 8× can exceed the single-buffer representation limit before decoding. The worker deliberately rethrows `OutOfMemoryError`, so WorkManager often has no structured error and the UI falls back to “Enhancement failed”.

The scale arithmetic and tile chaining are correct. The failure is the output materialization/export boundary.

## Goals

- Make 4× and 8× large-photo jobs complete when the device can process the requested dimensions.
- Support PNG, JPEG, and WebP without changing the selected format or quality semantics.
- Keep the existing in-memory path for small outputs.
- Preserve sequential batch processing, cancellation, explicit OOM/technical errors, EXIF handling, MediaStore publication, and history/cache behavior.
- Add no network permission and no runtime download.

## Non-goals

- No automatic downscaling, crop reduction, or format substitution.
- No new neural model assets or model-quality changes.
- No redesign of the existing tile model or restoration-pass semantics beyond the streaming adaptations documented below.

## Selected approach

Use a disk-backed PNG intermediate and Spectrum 1.3.0 for final encoding.

1. `EnhanceImage` detects a high-output request and streams completed tile rows instead of allocating the full output RGBA array.
2. A small pure-Kotlin PNG row writer writes the rows to a temporary PNG file. It emits PNG signature/IHDR/IDAT/IEND chunks, filter byte 0, zlib-deflated scanlines, and CRC32-validated chunks.
3. `MediaStoreImageIo` passes the temporary PNG to Spectrum’s native transcoder and writes the requested PNG, JPEG, or WebP result to the pending MediaStore row.
4. Existing EXIF allowlist copying, post-write verification, publication, and cleanup run after transcoding.
5. The temporary PNG and any pending MediaStore row are deleted on every failure path.

Spectrum is MIT-licensed and provides native PNG/JPEG/WebP plugins. The app initializes Spectrum through SoLoader from an `Application` class. The dependency is build-time only and does not add `INTERNET`.

## Processing path

### Output strategy selection

The legacy in-memory path remains the default for ordinary 2×/small outputs. The streaming path is selected when both are true:

- requested scale is 4× or 8×; and
- projected output is at least `64,000,000` pixels.

The threshold is an execution-strategy boundary, not a product cap. Requests above it are processed at their requested dimensions; they are never rejected or downscaled solely because of the threshold. The boundary is a named constant so device calibration can be revisited without changing serialization or UI contracts.

The streaming path does not call the single-buffer `outputBufferSize` guard. It validates each output dimension and each row-buffer size with long arithmetic, while allowing the total pixel count to exceed `Int.MAX_VALUE / 4`; the legacy path retains the existing single-buffer guard. The implementation must also fail before processing if the temporary file cannot fit in available app storage.

### Row-band tile assembly

`TilingManager` remains unchanged. The streaming implementation groups its existing tiles by tile row:

1. Upscale and convert every tile in the current input tile row.
2. Retain only the active tile rows needed for vertical feather overlap.
3. Blend each final output row through the same cosine weights and RGBA blend formula as `TileBlender`.
4. Pass the row to the PNG writer and release tile arrays as soon as their overlap window closes.

A small `StreamingTileWriter` owns the row-band lifecycle. It is tested against the existing full-buffer `TileBlender` on a multi-tile, multi-row fixture so seams and byte values remain equivalent.

### Pass ordering

Crop, denoise, deblur, scratch repair, and colorization continue to run on the decoded source before tiling. In the streaming path:

- sharpening runs on each upscaled tile before blending; and
- face restoration runs on each upscaled tile before blending, retaining the existing detector/restorer fallback and skipped-face accounting.

This avoids a second full-resolution allocation while keeping the pass order relative to upscaling. Tile overlap gives boundary faces the same surrounding context available to the existing seam logic. If a future face model requires image-wide detection, that limitation is handled explicitly rather than silently falling back to an in-memory allocation.

## Export integration

Add a narrow `StreamingImageIo` capability to the existing `ImageIo` boundary. `EnhanceImage` uses it only for the high-output path; existing test/fake implementations retain the current `encode(ByteArray, …)` contract.

`MediaStoreImageIo` implements the capability by:

1. inserting a pending MediaStore row;
2. opening a temporary PNG file;
3. feeding rows from `EnhanceImage`;
4. transcoding the PNG with Spectrum to the requested output format and quality;
5. copying the existing EXIF allowlist and verifying saved tags;
6. clearing `IS_PENDING` and returning the URI;
7. deleting temporary files and pending rows on failure.

Format mapping:

- PNG: lossless.
- JPEG: lossy at the existing clamped `ExportPolicy.jpegQuality`.
- WebP: lossless on the existing modern API path, lossy on older APIs, matching current `Bitmap.CompressFormat` behavior.

If Spectrum cannot initialize or transcode, the high-output job fails with the codec error and cleans up. It must not silently switch formats or retry with a full in-memory bitmap.

## Dependency and app initialization

Add:

```kotlin
implementation("com.facebook.spectrum:spectrum-default:1.3.0")
```

Add `RimuruApplication` and register it in the manifest. Initialize `SpectrumSoLoader` before workers or ViewModels use the transcoder. Keep the existing ABI filters and verify that Spectrum’s native libraries are packaged for them.

No manifest permission changes are allowed beyond the existing offline permissions.

## Error handling

- Cancellation and `OutOfMemoryError` remain terminal.
- A failed row write, PNG finalization, insufficient temporary storage, Spectrum transcode, EXIF verification, or MediaStore publication returns a structured `Result.failure` with the real message.
- Temporary files and pending rows are cleaned up in `finally`/failure paths.
- No automatic downscaling, format substitution, or generic-only error masking.

## Testing and verification

### Pure/unit tests

- PNG row writer produces a valid signature, IHDR dimensions, decompressible IDAT rows, and IEND.
- Row-band tile output matches the existing full-buffer blend for 4× and 8× multi-tile fixtures.
- Streaming is selected for large 4×/8× requests and not selected for ordinary 2× output.
- Streaming format/quality mapping and cleanup are deterministic.
- Existing scale, restoration, batch, persistence, and export tests remain green.

### Android/integration tests

- MediaStore creates and publishes PNG, JPEG, and WebP outputs from a large synthetic image.
- A canceled/failed transcode deletes the pending row and temporary file.
- EXIF allowlist verification succeeds for all supported formats.
- A representative large-photo fixture completes on a device/emulator at 4× and 8× without the prior generic failure.

Run `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`; GitHub Actions remains authoritative when the local Android toolchain is unavailable.

## Acceptance criteria

- A large photo at 4× and 8× completes without a full output RGBA allocation.
- The output URI, dimensions, format, quality, EXIF policy, and history/cache records remain correct.
- No `INTERNET` permission or runtime network access is introduced.
- Failures are actionable and clean up all temporary/pending artifacts.
- Full unit tests and debug assembly pass.
