# Product Requirements Document (PRD)

# Rimuru 2x — On‑Device AI Image Upscaler & Restoration for Android

| Field | Detail |
|---|---|
| **Product Name** | Rimuru 2x |
| **Platform** | Android (Native) |
| **Document Version** | v1.0 (Draft) |
| **Status** | Ready for Review |
| **Author** | Product / Engineering |
| **Last Updated** | 2026-09-23 |
| **Stakeholders** | Product Owner, Senior Android Engineer, Embedded ML Engineer, QA, Designer |

---

## 1. Overview

### 1.1 Executive Summary

**Rimuru 2x** is a native Android application that performs **high-quality, fully offline image upscaling and restoration** using on-device neural networks. It enhances old, low-resolution, noisy, or compressed photos by (a) increasing resolution 2×/4×, (b) recovering fine detail and texture, (c) removing noise and compression artifacts, and (d) optionally restoring degraded faces — all **without ever sending a single byte to a server**.

The app targets three pillars:

1. **Privacy by architecture** — zero network dependency; all inference runs locally.
2. **Quality** — state-of-the-art super-resolution models (Real-ESRGAN-class, Swin2SR-class) with specialized *Precision* and *Creative* engine profiles.
3. **Reliability on mobile hardware** — a tile-based inference pipeline with seamless overlap blending that guarantees no `OutOfMemoryError`, even on large photos and low-spec devices.

### 1.2 Problem Statement

- Cloud-based upscalers (Topaz, waifu2x web, various web APIs) require uploading personal photos — a privacy risk, plus latency, cost, and offline unavailability.
- Simple bicubic scaling produces soft, blurry results; JPEG artifacts and sensor noise get magnified, not removed.
- Naive on-device ML inference on full-resolution photos exhausts mobile RAM/GPU memory (OOM crashes) and freezes the UI thread.
- Existing mobile enhancers often "hallucinate" unwanted detail on text, line art, and documents, or offer no control over how creative the reconstruction is.

### 1.3 Goals & Objectives

| # | Goal | Measure |
|---|---|---|
| G1 | 100% offline operation | App functions with airplane mode on; release build requests **no network access** |
| G2 | Artifact-free large-image upscaling | Zero OOM crashes for inputs up to 48 MP; no visible tile seams in output |
| G3 | Visible, best-in-class quality improvement | Internal blind test: neural output preferred over bicubic/Lanczos in ≥ 90% of comparisons |
| G4 | Respect user intent on detail | *Precision* mode preserves text/line-art fidelity; *Creative* mode adds plausible texture |
| G5 | Smooth UX during long jobs | Foreground progress UI + WorkManager background jobs; no ANRs; UI thread never blocked |
| G6 | Broad device reach | Runs on Android 8.0+ (API 26+); graceful fallback (bicubic/Lanczos + CPU) on low-spec hardware |

### 1.4 Non-Goals (Out of Scope for v1.0)

- ❌ Any cloud/API processing, account system, or telemetry requiring network.
- ❌ Video upscaling or live camera real-time enhancement.
- ❌ Batch processing of multiple images in one job (v1.1 candidate).
- ❌ RAW/DNG decoding (v1.x candidate — v1.0 accepts JPEG/PNG/WebP/HEIF via MediaStore).
- ❌ iOS or other platforms.
- ❌ Model training/fine-tuning on user devices.

### 1.5 Success Metrics (post-launch)

| Metric | Target |
|---|---|
| Crash-free sessions | ≥ 99.5% (zero OOM on supported inputs) |
| Median 2× neural upscale time, 12 MP input, mid-range device (e.g., Snapdragon 7-gen class, 8 GB RAM) | ≤ 60 s |
| Median 4× neural upscale time, same device | ≤ 180 s |
| Fast-path (Bicubic/Lanczos preview) 12 MP | ≤ 1 s |
| Task completion rate (pick → export) | ≥ 85% |
| Offline integrity | 100% of features usable in airplane mode (verified in QA) |

---

## 2. Users & Personas

| Persona | Description | Primary Needs |
|---|---|---|
| **"Memory Keeper" (primary)** | Restores old family photos; privacy-sensitive about personal images | Face restoration, denoise, offline guarantee, easy before/after comparison |
| **"Content Creator"** | Upscales artwork/screenshots for social media or printing | 4× scale, texture quality, PNG export, fast turnaround |
| **"Document/Line-art User"** | Enhances scans of notes, manga, typography, screenshots | *Precision* mode — no hallucinated details, crisp readable text |
| **"Low-end Device Owner"** | Budget phone, limited RAM | Reliable operation, fast non-neural fallback, no crashes |

### 2.1 Key User Stories (with acceptance criteria)

| ID | Story | Priority |
|---|---|---|
| US-01 | As a user, I can pick a photo from my gallery (or share one into the app) so I can enhance it. | P0 |
| US-02 | As a user, I can choose 2× or 4× upscaling so I control output size and speed. | P0 |
| US-03 | As a user, I can switch between **Precision** and **Creative** engines so text/artwork stays faithful or photos gain realistic texture. | P0 |
| US-04 | As a user, I can set denoise strength (Off/Low/Medium/Aggressive or 0–100% slider) to clean JPEG/noise artifacts. | P0 |
| US-05 | As a user, I can toggle **Face Restore** and control the Fidelity↔Enhancement strength so faces look natural but still like the person. | P0 |
| US-06 | As a user, I see step-by-step progress ("Detecting faces…", "Processing tiles (4/16)…", "Blending output…") so long jobs feel transparent and interruptible. | P0 |
| US-07 | As a user, I can compare original vs. result with a draggable before/after split slider at full resolution. | P0 |
| US-08 | As a user, I can save the result to my gallery as PNG or high-quality JPEG, with Exif metadata retained. | P0 |
| US-09 | As a user on a low-spec device, the app offers a fast Bicubic/Lanczos path instead of crashing or taking forever. | P0 |
| US-10 | As a privacy-conscious user, the app works fully in airplane mode and never uploads my photos. | P0 |
| US-11 | As an advanced user, I can force the accelerator (Auto / Vulkan GPU / NPU / CPU) for debugging or performance. | P1 |
| US-12 | As a user, I can keep using my phone while processing continues in the background and get notified on completion. | P1 |

---

## 3. Functional Requirements

Priorities: **P0 = must ship in v1.0 · P1 = should ship · P2 = nice to have.**

### 3.1 Feature 1 — Resolution Upscaling (*Peningkatan Resolusi*)

| ID | Requirement | Priority |
|---|---|---|
| FR-1.1 | User selects scale factor **2×** or **4×** via a segmented control. | P0 |
| FR-1.2 | **Neural super-resolution path** using lightweight quantized models (e.g., Real-ESRGAN Compact / Swin2SR-class, FP16 or INT8) produces crisp, artifact-free results. | P0 |
| FR-1.3 | **Fast fallback path** using Bicubic/Lanczos resampling for low-spec devices, quick previews, or when the user explicitly chooses "Fast" quality. | P0 |
| FR-1.4 | The 4× path may be implemented as native 4× single-pass **or** as two chained 2× passes; behavior and output dimensions must be identical to the selected factor (W×H → 2W×2H or 4W×4H). | P0 |
| FR-1.5 | App shows estimated output dimensions and a rough time estimate before the user taps *Enhance*. | P1 |
| FR-1.6 | Input safety valve: inputs above a configurable megapixel cap (default ≈ 24–48 MP) prompt the user to downscale first (protects processing time and disk). | P1 |

**Acceptance criteria**
- Given a 1080×1920 JPEG, when 2× is selected, output is 2160×3840 with no visible tiling seams at 100% zoom.
- On a device classified "low-spec" (see §6.2), the default recommendation switches to the fast path with a one-tap option to still try the neural engine.

### 3.2 Feature 2 — Detail & Texture Recovery / Engine Profiles (*Pemulihan Detail & Tekstur*)

| ID | Requirement | Priority |
|---|---|---|
| FR-2.1 | Segmented button / toggle: **[ Precision ] | [ Creative ]**. | P0 |
| FR-2.2 | **Precision Mode** — faithful reconstruction: sharp geometric edges, clean line-art, readable blurred typography/text; must **not** hallucinate extra details. Served by a conservative restoration-oriented model checkpoint. | P0 |
| FR-2.3 | **Creative Mode** — generative-leaning compact model that plausibly synthesizes fine textures (skin pores, hair strands, fabric weave, foliage). | P0 |
| FR-2.4 | Selected mode is persisted per-session and shown in the configuration summary; only the mode's model is loaded into memory (lazy model loading/swap). | P0 |
| FR-2.5 | Mode description helper text (one line) explains what each mode does, localized (EN + Bahasa Indonesia). | P1 |

**Acceptance criteria**
- Precision mode on blurred text screenshot → text is sharp and letterforms are unchanged (no invented glyphs) in QA sample set.
- Creative mode on a soft portrait → visible natural skin/hair texture without plastic or "AI look" artifacts in ≥ 80% of blind-test samples.

### 3.3 Feature 3 — Denoise & Deblur (*Pengurangan Noise dan Artefak*)

| ID | Requirement | Priority |
|---|---|---|
| FR-3.1 | Control available as **presets**: `Off · Low · Medium · Aggressive`, backed by a continuous **0–100% slider** (presets are anchor points on the slider). | P0 |
| FR-3.2 | Effectively strips **JPEG compression artifacts**, **high-ISO sensor noise**, and **slight motion/optical blur** before or fused into the super-resolution pass. | P0 |
| FR-3.3 | Denoise strength maps to model input parameters (e.g., denoise strength conditioning) or pre/post-pass intensity; mapping is documented for tuning. | P0 |
| FR-3.4 | Default value: `Low` (25%) — conservative so faces/detail are not over-smoothed. | P0 |
| FR-3.5 | Live thumbnail preview (small crop region) showing the denoise effect before running the full job. | P2 |

**Acceptance criteria**
- JPEG q≈50 sample at Aggressive: blocking/ringing visibly eliminated post-upscale.
- At `Off`, the pipeline performs no denoising (bitwise-neutral wrt denoise stage).

### 3.4 Feature 4 — Face Restoration (*Restorasi Wajah*)

| ID | Requirement | Priority |
|---|---|---|
| FR-4.1 | Toggle: **Enable Face Restore (On/Off)**, default Off. | P0 |
| FR-4.2 | **Fidelity vs. Enhancement strength slider** (0–100%) controlling the blend between identity-preserving restoration and aggressive enhancement (analogous to CodeFormer fidelity weight). | P0 |
| FR-4.3 | Face detection via a lightweight on-device detector (e.g., **BlazeFace** or **MediaPipe Face Mesh**); supports multiple faces per image. | P0 |
| FR-4.4 | Pipeline: detect bounding boxes → crop + align face regions from the upscaled image → run quantized face restoration model (e.g., GFPGAN / CodeFormer Mobile class) → **seamlessly blend** restored faces back (feathered/poisson-style blending, color-matched). | P0 |
| FR-4.5 | Faces smaller than a minimum detected size (< ~48 px) are skipped and logged in job status to avoid garbage output. | P1 |
| FR-4.6 | Detection runs on downscaled image for speed; restoration crops are re-extracted at full upscaled resolution. | P1 |

**Acceptance criteria**
- Group photo with 5 faces → all 5 detected and restored; no visible blend boundary at 100% zoom.
- Strength = 0 → output face region ≈ fidelity-preserving (identity retained); strength = 100 → maximum texture enhancement.
- No photo containing a face is ever processed by network code paths (offline guarantee, §5.1).

---

## 4. UI / UX Requirements

### 4.1 Screen Inventory

| # | Screen | Contents |
|---|---|---|
| S1 | **Home / Input** | Clean photo picker (Photo Picker / MediaStore), drag-and-drop onto drop zone, **Share-to-app** intent receiver (`ACTION_SEND` image/*), recent-jobs list |
| S2 | **Configuration Sheet** (bottom drawer) | Scale selector `[2x] [4x]` · Mode selector `[Precision] [Creative]` · Face Restore toggle + Fidelity/Enhancement slider (visible when on) · Denoise level slider + presets · Hardware accelerator selector `[Auto / Vulkan GPU / NPU / CPU]` · Estimated output size & time · **Enhance** CTA |
| S3 | **Processing Screen** | Step-by-step progress: *Preparing → Detecting faces… → Processing tiles (n/N)… → Restoring faces… → Blending output… → Done*. Progress is determinate where possible; Cancel button; job continues via WorkManager if user leaves |
| S4 | **Comparison Viewer** | Interactive **before/after split slider** at full resolution (draggable divider, zoom & pan locked between halves), tap-and-hold for original, side-by-side toggle |
| S5 | **Export Screen** | Format choice **PNG / high-quality JPEG (quality ≥ 95)**, Exif metadata retention (orientation, timestamps), save to Gallery via MediaStore, share sheet, "Save a copy" |

### 4.2 UX Rules

| ID | Rule | Priority |
|---|---|---|
| UX-1 | Built with **Jetpack Compose + Material 3**; dynamic color; dark & light themes. | P0 |
| UX-2 | State management follows **MVI/MVVM** with unidirectional data flow; every screen state (Idle / Ready / Processing(step, progress) / Success / Error) is modeled explicitly. | P0 |
| UX-3 | UI thread is never blocked by inference or I/O; all long work is in coroutines on Dispatchers.Default/IO + WorkManager. | P0 |
| UX-4 | Processing survives app backgrounding; a notification shows progress and completion (with thumbnail). | P1 |
| UX-5 | Destructive/cancel actions require confirmation once a job is > 50% complete. | P2 |
| UX-6 | Accessibility: min touch target 48 dp, content descriptions on all controls, slider state announced via TalkBack. | P1 |
| UX-7 | Localization: English + **Bahasa Indonesia** at launch (feature names above are the ID strings). | P1 |

---

## 5. Technical Requirements & Architecture

### 5.1 Core Constraints (hard requirements)

| ID | Constraint |
|---|---|
| TC-1 | **Zero API / 100% Offline.** All image processing, NN inference, and manipulation run locally. No internet connection or cloud API may be used at runtime. Verification: the release build declares **no `INTERNET` permission** in the manifest; QA passes a full regression in airplane mode. |
| TC-2 | **Inference engine** — one of the vetted local runtimes: **ONNX Runtime Mobile**, **TensorFlow Lite** (NNAPI / GPU delegate), or **NCNN with Vulkan** (recommended for Real-ESRGAN-class models on Android). Final choice per model in the Model Matrix (§5.5); the runtime layer must be abstracted behind an interface (`InferenceEngine`) so models/backends are swappable. |
| TC-3 | **Memory safety (anti-OOM).** Tiling/patch-based inference with overlap blending is mandatory for the neural path. Large images are split into tiles (256×256 or 512×512 input tiles) with sufficient overlap (e.g., 16–32 px per side) and stitched back seamlessly. Full-resolution bitmaps must never be allocated per-tile more than once; peak memory stays within budget (§6.1). |

### 5.2 Tech Stack

| Layer | Technology |
|---|---|
| Language | **Kotlin** (official, primary) |
| UI | **Jetpack Compose**, **Material 3** |
| Concurrency / Background | **Kotlin Coroutines & Flow** + **WorkManager** for long-running restoration jobs |
| Storage / Media | **Scoped Storage via MediaStore API** (pick + save, no broad storage permission), Photo Picker |
| Image loading (UI) | **Coil** (preferred, Compose-native) or Glide — previews/thumbnails only |
| Image pipeline (compute) | **NDK / C++ (JNI)** for tiling, color conversion (RGBA↔RGB/CHW), tile blending; Vulkan via NCNN or GPU delegate via TFLite; RenderScript only as legacy fallback where unavailable |
| Build | Gradle (Kotlin DSL), AGP latest stable, minSdk 26, targetSdk 35, ABI filters `arm64-v8a` (P0), `armeabi-v7a` (P2) |

### 5.3 Clean Architecture (module layout)

```
app/
 ├── data/          # MediaStore repos, model asset repo, job persistence, WorkManager workers
 ├── domain/        # Use cases (EnhanceImage, DetectFaces, ExportImage), models, engine interfaces
 │      └── InferenceEngine, TilingManager, FaceRestorer  (interfaces only)
 └── presentation/  # Compose UI, ViewModels (MVI), navigation, theming
cpp/                # NDK: tiling, color conversion, blending, JNI bridge to NCNN/ORT
models/             # Versioned model assets + quantization scripts (see §5.6)
```

- **Data** knows about Android + runtimes; **Domain** is pure Kotlin (unit-testable); **Presentation** talks to Domain via use cases and exposes state to Compose.
- All heavy native calls are wrapped behind JNI boundaries defined in Domain interfaces; mocks exist for UI tests.

### 5.4 Tiling & Blending Pipeline (TilingManager)

**Required behavior — artifact-free seam blending:**

1. Decode input to RGBA (bitmap config `ARGB_8888`), get dimensions.
2. Compute tile grid: input tile **256 or 512 px** (auto-selected by device tier), **overlap ≥ 32 px** scaled to model receptive field.
3. For each tile: extract → normalize (float CHW, [0,1]) → inference → denormalize → write into an output buffer **only in the non-overlapped core region**, using feathered weighted blending (linear/cosine weight ramp) in overlap zones to eliminate seams.
4. Real-time progress callback per tile (`Processing tiles (4/16)…`) exposed to Presentation.
5. Handle edge tiles with zero-padding + mask correction so borders show no halo.
6. Optional large-scale consistency pass: global chroma/luma histogram match between input and stitched output to avoid tile-to-tone drift.

**Acceptance criteria**
- Synthetic flat-gradient + noise test image → output contains no grid seams (SSIM across seam lines within 0.5% of non-seam baseline).
- Peak RSS during a 48 MP 4× job stays under device-tier budget (§6.1); zero OOM in a 100-run stress loop.

### 5.5 Model Matrix (initial)

| Purpose | Candidate model | Format/Backend | Quantization |
|---|---|---|---|
| Upscale 2×/4× — Creative | Real-ESRGAN Compact (or eager-class equivalent) | NCNN + Vulkan (recommended) or ONNX Runtime Mobile | FP16 default, INT8 A/B tested |
| Upscale 2×/4× — Precision | Swin2SR-class lightweight / line-art-tuned ESRGAN variant | ONNX Runtime Mobile (CPU/GPU/NNAPI) | FP16 / INT8 |
| Fast path | Bicubic / Lanczos (CPU, native) | — | — |
| Denoise/Deblur | Fused into upscale model (strength-conditioned) or lightweight pre-pass denoiser | TFLite / NCNN | INT8 |
| Face detection | BlazeFace or MediaPipe Face Mesh | TFLite | INT8 |
| Face restoration | GFPGAN / CodeFormer-class, mobile-quantized | ONNX Runtime Mobile / TFLite | INT8 |

- Models ship as **versioned assets** in the APK/AAB (or Play Asset Delivery if size demands); a model manifest (name, version, sha256, compatible runtimes) is verified at load time.
- **Offline model loading:** models load from assets → cached to `filesDir` on first run; loader is pure-local; corrupted/missing models fall back gracefully (fast path) with a visible notice.

### 5.6 Hardware Acceleration

| ID | Requirement |
|---|---|
| HW-1 | Accelerator selector: **Auto / Vulkan GPU / NPU / CPU**. `Auto` = capability probing (Vulkan ≥ 1.1 → GPU; NNAPI/NPU availability → NPU; else CPU) with device-tier defaults. |
| HW-2 | Explicit NDK C++/JNI bindings for the NCNN path; delegate options for TFLite/ORT paths. |
| HW-3 | Backend failures (driver quirks, OOM on GPU) must auto-fallback one level (GPU → CPU) mid-job without crashing, and report which backend was used. |

### 5.7 Long-Job Management

- Restoration runs as a **WorkManager** long-running job (foreground service type `dataSync` where required by OS); progress reported via `setProgress` → Flow → UI.
- Jobs are **cancelable** and resumable at tile granularity (P1: persist tile completion state).
- Process death recovery: completed outputs are durable in MediaStore; in-progress jobs restart with a clear message (P1: checkpoint resume).

---

## 6. Non-Functional Requirements

### 6.1 Performance & Memory Budgets

| Device tier | Example | Neural default | Tile size | Peak app RAM budget |
|---|---|---|---|---|
| High (flagship) | SD 8-gen, 12 GB | GPU (Vulkan/NPU) | 512 | ≤ 1.5 GB |
| Mid | SD 7-gen, 6–8 GB | GPU or CPU-FP16 | 512→256 adaptive | ≤ 1.0 GB |
| Low | 4 GB, Android 8–10 | CPU-INT8 / fast path offered | 256 | ≤ 600 MB |

### 6.2 Compatibility & Device Tiers

- **minSdk 26 (Android 8.0)**, targetSdk 35; `arm64-v8a` required, `armeabi-v7a` best-effort (P2).
- "Low-spec" classification: total RAM < 4 GB or no Vulkan 1.1 support → default to fast path + CPU INT8.
- Supported input formats: JPEG, PNG, WebP, HEIF (via platform decode). Output: PNG, JPEG (q ≥ 95).

### 6.3 Privacy & Security

- No `INTERNET` permission in release manifest; no analytics/crash reporting SDKs that transmit data (use offline log files the user can export manually).
- No photo data ever leaves the process; temp files in `cacheDir` are wiped after export.
- PRD-level privacy claim for store listing: "All processing happens on your device. Always."

### 6.4 Reliability

- Crash-free sessions ≥ 99.5%; **zero** `OutOfMemoryError` on inputs within the supported cap (48 MP).
- ANR rate < 0.1% (no inference on main thread, enforced by lint + code review checklist).

---

## 7. Implementation Deliverables

| # | Deliverable | Definition of Done |
|---|---|---|
| D1 | Complete Android project — `app` module with clean architecture layers (Data / Domain / Presentation), plus **native C++/JNI bindings** if NCNN path is used | Builds via one command; unit tests for Domain; UI smoke tests for critical flows |
| D2 | **Setup guide + code for model quantization** (FP16/INT8), asset management, and offline model loading | `models/README.md` with conversion scripts (PyTorch → ONNX/NCNN/TFLite), verification steps, and checksums |
| D3 | Fully functional **Jetpack Compose UI** with MVI/MVVM state management | All 5 screens (S1–S5) implemented and passing design QA |
| D4 | **TilingManager** with artifact-free tile seam blending | Passes §5.4 acceptance criteria; progress callbacks wired to UI |

---

## 8. Milestones (indicative)

| Phase | Scope | Exit Criteria |
|---|---|---|
| **M0 — Foundations** (2 wk) | Project skeleton, Compose theme, MediaStore picker + share intent, image decode/encode utils | S1 functional; images pick/save round-trip |
| **M1 — Core pipeline** (3 wk) | NDK tiling + blending, `InferenceEngine` abstraction, first model (2× Creative) on CPU, fast path (Lanczos) | S3 + S4 working; seam test passes; no OOM on 12 MP |
| **M2 — Full feature set** (3 wk) | 4×, Precision model, denoise stages + slider/presets, GPU/Vulkan delegate, accelerator selector | S2 complete; features 1–3 done |
| **M3 — Face restoration** (2 wk) | Detector + face model + alignment + blending + strength slider | Feature 4 acceptance criteria pass |
| **M4 — Polish & export** (2 wk) | Comparison viewer polish, PNG/JPEG export with metadata, notifications, WorkManager hardening, i18n (EN/ID) | Feature-complete beta |
| **M5 — Hardening** (2 wk) | Device-tier tuning, stress tests (100-run OOM loop), airplane-mode regression, low-end device QA | Success metrics (§1.5) met in beta cohort |

---

## 9. Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| **Model licensing** — GFPGAN/CodeFormer-family licenses can restrict commercial use | High | Legal review of model licenses before ship; prefer permissively licensed or self-distilled compact models; document provenance in `models/README.md` |
| Vulkan driver fragmentation across OEMs (GPU artifacts/crashes) | High | Auto-fallback chain GPU→CPU; per-device blacklist/whitelist; extensive device-lab testing |
| INT8 quantization degrades generative quality noticeably | Medium | Ship FP16 as default on capable devices; per-model A/B QA gate before accepting INT8 |
| APK size blowup from multiple models | Medium | Play Asset Delivery / on-demand packs; compact model distillation; strip CPU-unnecessary ABIs |
| Long processing times cause user abandonment | Medium | Accurate time estimates, background jobs + notifications, fast-path option |
| NNAPI deprecation/behavior differences across Android versions | Medium | Abstract backend selection; prefer Vulkan (NCNN/ORT) as primary, NNAPI opportunistic |
| Face blend boundary visible on strong enhancement | Low | Feathered masks + color transfer; QA checklist on group photos |

---

## 10. Open Questions

1. **APK size budget** — ship all models in-base vs. Play Asset Delivery? (Drives D2 packaging.)
2. Face restoration model choice: **GFPGAN vs. CodeFormer** — final decision after license review + quality bake-off (FR-4 acceptance tests run on both).
3. Should `Aggressive` denoise unlock a second dedicated denoiser pass (slower, cleaner) in addition to fused conditioning?
4. v1.1 candidates to confirm: batch queue, RAW/DNG input, custom tile size (advanced settings), resumable jobs.
5. Store listing positioning: emphasize privacy ("never uploads") vs. quality benchmarks?

---

## 11. Glossary

| Term | Definition |
|---|---|
| **Tiling** | Splitting a large image into small overlapping patches for inference to bound memory use |
| **Overlap blending** | Weighted averaging of overlapping tile borders so seams are invisible |
| **FP16 / INT8** | 16-bit float / 8-bit integer quantization — smaller, faster models with (ideally) minimal quality loss |
| **NNAPI** | Android Neural Networks API for hardware-accelerated inference |
| **NCNN / Vulkan** | Mobile-first inference framework / cross-platform GPU compute API used for fast GPU inference |
| **OOM** | Out-of-memory error/crash |
| **Precision vs. Creative** | Engine profiles: faithful reconstruction vs. generative texture synthesis |
| **Fidelity weight** | Face-restore strength controlling identity preservation vs. enhancement |
| **MediaStore / Scoped Storage** | Android APIs for accessing/saving media without broad storage permissions |
| **WorkManager** | Jetpack API for deferrable, persistent background work |

---

*End of document — PRD v1.0 for Rimuru 2x.*
