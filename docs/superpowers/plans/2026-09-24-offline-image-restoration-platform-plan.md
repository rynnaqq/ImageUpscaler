# Offline Image Restoration Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved offline-only image restoration feature set in four independently testable vertical slices.

**Architecture:** Extend the existing `EnhanceImage` domain orchestrator with typed image passes and a lazy model registry. Keep processing in WorkManager, persistence in app-private local stores, and all UI state in the existing MVI ViewModel. Classical fallbacks remain available when model assets or hardware backends are unavailable.

**Tech Stack:** Kotlin 2.0.20, Android API 26+, Jetpack Compose, WorkManager, ONNX Runtime Android 1.18.0, MediaStore, ExifInterface, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-24-offline-image-restoration-platform-design.md`

## Global Constraints

- No cloud API, uploads, accounts, runtime downloads, or `INTERNET` permission.
- Model assets are local, checksum-verified, and loaded lazily.
- The fixed product output cap is removed; the only hard output limit is `Int.MAX_VALUE / 4` pixels for the RGBA byte buffer.
- `OutOfMemoryError` is terminal for the current image and must not be converted into a successful fallback.
- Use TDD: each behavior gets a failing test before implementation.
- Keep each slice buildable and usable with classical fallbacks.
- Verify with `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`; CI is authoritative because the local wrapper/JDK may be unavailable.

## Delivery Order

1. `docs/superpowers/plans/2026-09-24-core-scale-model-runtime-plan.md`
2. `docs/superpowers/plans/2026-09-24-restoration-passes-plan.md`
3. `docs/superpowers/plans/2026-09-24-workflow-persistence-plan.md`
4. `docs/superpowers/plans/2026-09-24-export-settings-polish-plan.md`

## Global Verification

- [ ] Run the full unit suite after every slice.
- [ ] Assemble the debug APK after every slice.
- [ ] Confirm the release manifest has no `INTERNET` permission.
- [ ] Run one single-image job and one 10-image batch in airplane mode.
- [ ] Record model asset licenses and SHA-256 values before bundling any model.
