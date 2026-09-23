# Rimuru 2x

On-device AI image upscaler & restoration for Android. 100% offline — the release
build requests **no INTERNET permission** (PRD TC-1).

## Status

Full app scaffold per PRD v1.0: Compose UI (S1–S5), MVI ViewModel, tiling engine
with seam blending, ONNX Runtime inference layer with GPU→CPU fallback, WorkManager
jobs with foreground notifications, MediaStore export, EN/ID localization.

## Build

Open in **Android Studio** (Koala+, AGP 8.5, JDK 17) and run the `app` config.
No API keys, no network needed at build or runtime.

## What works without model files

Everything except neural quality: the pipeline runs end-to-end, but
`OnnxInferenceEngine` falls back to bilinear upscaling when a model asset is
missing/corrupt (PRD §5.5 graceful degradation). Place converted models per
[`models/README.md`](models/README.md) to get Real-ESRGAN-class quality.

## Architecture

```
app/src/main/java/com/rimuru/twobytwo/
├── domain/          # pure Kotlin (unit-testable)
│   ├── model/       # EnhanceRequest, JobProgress, enums
│   ├── engine/      # InferenceEngine, TilingManager, TileBlender, TensorCodec
│   └── usecase/     # EnhanceImage pipeline
├── data/            # Android-facing
│   ├── engine/      # OnnxInferenceEngine + ModelManifest (sha256 verify)
│   ├── media/       # MediaStoreImageIo (decode/encode/Exif)
│   ├── device/      # DeviceTiers (low-spec classification)
│   └── work/        # EnhanceWorker (foreground dataSync, cancelable)
└── presentation/    # Compose UI + MVI ViewModel
```

## Tests

```
./gradlew :app:testDebugUnitTest
```

Covers the §5.4 acceptance criteria: exact-core coverage (no double-writes),
tile-count bounds, feather weights at borders, constant-input seam test,
ramp-tolerance blending test.
