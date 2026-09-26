# Bounded Tile Spooling Design

**Date:** 2026-09-25  
**Status:** Approved in conversation  
**Scope:** Correct the 4×/8× large-output memory bound discovered during final review.

## Problem

The current streaming implementation writes each generated tile to `StreamingTileWriter.RowGroup.buffers` and queues completed output rows in `EnhanceImage.pendingRows`. With 512-pixel input tiles at 8×, one tile can be about 64 MiB. A wide image therefore retains more than a gigabyte of live tile data before the PNG writer can consume rows. The implementation is CI-green but does not meet the bounded-memory requirement.

## Decision

Replace in-memory row-group retention and the pending-row queue with disk-backed raw-RGBA row-group spools. Keep generation row-major, but write each accepted tile to a temporary spool file before releasing its `ByteArray`. Pull one output row at a time directly into the encoder-owned row buffer, reading only the needed source row from each active spool.

This is the smallest design that preserves the existing tile schedule, exact cosine feather blending, sequential inference, cancellation, and all output formats while making assembly RAM independent of image width.

## Data flow

1. `EnhanceImage` measures/crops the input and builds the existing `TilingManager` tile plan.
2. Before the first streamed tile, it computes a conservative scratch requirement and asks `StreamingImageIo` to verify available cache space.
3. It creates one `StreamingTileWriter` using the app cache directory.
4. `MediaStoreImageIo.encodeStreaming` allocates one reusable output-row buffer and requests rows from the domain.
5. For each requested row, the domain generates only enough tiles to make that row ready, applies face restoration and tile-local sharpening, and passes each tile to the writer.
6. `StreamingTileWriter` appends tile RGBA bytes to the active row-group spool and retains only file metadata/offsets.
7. The writer zeroes the caller-owned row, reads one row from each covering tile spool, applies the unchanged `TileBlender` formula, and advances the output row.
8. Groups whose vertical overlap has ended are closed and deleted immediately.
9. After all rows are consumed, `finish()` verifies the complete tile plan; `close()` is idempotent and removes every spool on success, failure, or cancellation.
10. The existing temporary PNG, Spectrum transcode, EXIF, MediaStore publication, and pending-row cleanup remain in `MediaStoreImageIo`.

At most three adjacent row groups are active under the current tile geometry. Two is the common case, but the last tile row is pulled back to the image edge (`min(row * stride, imageHeight - tileSize)`), so its group can start less than `stride` after its predecessor and three groups can cover one output row; the enforced `tileSize > overlap * 2` keeps the count at three or fewer for any geometry. RAM does not depend on that count: it is one generated tile, model/runtime buffers, one output row, and one source-row scratch buffer, plus the retained `TileEntry` metadata of the open groups. Disk use is the active raw tile groups plus the temporary PNG and is checked before processing.

## Interfaces

`StreamingTileWriter` becomes `Closeable` and uses:

```kotlin
class StreamingTileWriter(
    tiles: List<TilingManager.Tile>,
    tiling: TilingManager,
    scratchDirectory: File,
) : Closeable {
    fun accept(tileRgba: ByteArray, tile: TilingManager.Tile)
    fun isRowReady(outY: Int): Boolean
    fun writeRow(outRow: ByteArray, outY: Int)
    fun finish()
    internal val activeGroupCount: Int
    internal val maxActiveGroupCount: Int
    override fun close()
}
```

The writer retains no tile `ByteArray`: accepted tiles are released as soon as they are spooled, so the bound is structural rather than a counted value.

`TileBlender` gains a source-row overload:

```kotlin
fun blendRow(
    sourceRow: ByteArray,
    tile: TilingManager.Tile,
    tiling: TilingManager,
    outRow: ByteArray,
    outY: Int,
)
```

The existing full-tile `blendRow` remains source-compatible and delegates to the same pixel calculation with a full-tile source stride.

`StreamingImageIo` gains a default no-op capacity hook so existing fakes remain source-compatible:

```kotlin
fun checkScratchCapacity(requiredBytes: Long) = Unit
```

`EnhanceImage` accepts an optional `streamingScratchDirectory: File` after its existing constructor parameters. Production passes `applicationContext.cacheDir`; tests may pass a temporary directory.

## Scratch budget

Compute the maximum raw bytes held by overlapping row-group plans from tile metadata using checked `Long` arithmetic, independently of how many groups are active at once. Add a conservative PNG bound of five bytes per pixel (four RGBA bytes plus one filter byte), a fixed 1 MiB codec/chunk margin, and a fixed 16 MiB safety margin. `MediaStoreImageIo` compares this requirement with `StatFs.availableBytes` before the row callback starts. Failure is an explicit `insufficient temporary storage` error; there is no downscale, format substitution, or full-buffer retry.

## Error and resource rules

- `CancellationException` propagates unchanged.
- `close()` closes and deletes every spool file and is safe in `finally`.
- A partial writer, missing/out-of-order tile, row-size mismatch, or incomplete finish fails explicitly.
- `EnhanceWorker` wraps request decoding, registry/engine construction, and processing in the terminal OOM guard; any initialized engine is closed in the outer `finally`.
- Streamed sharpening is enabled per tile regardless of the legacy full-image 24 MP ceiling; legacy behavior remains unchanged.
- CI compiles/packages Android tests and assembles the minified release APK. Device instrumentation remains a separate required runtime check when an emulator/device is available.

## Verification

- Pure JVM tests compare 4× and 8× spooled rows byte-for-byte with `TileBlender.blend` on multi-tile fixtures with edge tiles and varying alpha.
- A wide fixture uses the production 512-pixel tile tier at an image height that triggers the edge pull-back, asserts that three row groups are simultaneously active, and asserts the three-group bound is never exceeded. No `rimuru2x_tiles_` spool survives `close()` after a full run, a partial accept, or a failed spool deletion that is retried.
- Scratch-budget tests assert the exact expected total — independently computed peak raw overlap, plus the PNG bound, plus the 1 MiB and 16 MiB margins — for a small and a tall fixture, so a broken interval sweep cannot pass on the fixed margins. The tall sweep carries a JUnit timeout. Preflight acceptance and failure are tested through the extracted pure helpers without Android dependencies.
- Worker tests cover setup-time OOM cleanup and structured failure.
- The default streamed face/sharpen ordering test runs with the production default sharpen ceiling.
- CI must pass `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:assembleRelease`; Android instrumentation and a representative 64 MP device stress run remain required before release claims.

## Non-goals

- No neural model changes, format changes, downscaling, or redesign of tile geometry.
- No claim that native ONNX memory is constant; the bounded guarantee applies to streaming assembly.
- No network permission or runtime download.
