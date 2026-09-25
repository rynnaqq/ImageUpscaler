package com.rimuru.twobytwo.domain.model

/**
 * Core domain models. Pure Kotlin — no Android imports (PRD §5.3).
 */

enum class ScaleFactor(val multiplier: Int) {
    X2(2),
    X4(4),
    X8(8);
}

enum class EngineMode {
    PRECISION,
    CREATIVE;
}

enum class Accelerator {
    AUTO,
    GPU,
    NPU,
    CPU;
}

enum class OutputFormat(val mimeType: String, val fileExtension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp"),
}

data class ExportPolicy(
    val format: OutputFormat = OutputFormat.PNG,
    val jpegQuality: Int = 97,
    val keepExif: Boolean = true,
    val keepGps: Boolean = false,
) {
    val effectiveJpegQuality: Int get() = jpegQuality.coerceIn(80, 100)
    val encoderQuality: Int
        get() = if (format == OutputFormat.JPEG) effectiveJpegQuality else 100
}

/** Denoise strength 0..100; presets are anchor points (FR-3.1). */
data class DenoiseStrength(val percent: Int) {
    init {
        require(percent in 0..100) { "denoise must be 0..100, got $percent" }
    }

    val isOff: Boolean get() = percent == 0

    companion object {
        val OFF = DenoiseStrength(0)
        val LOW = DenoiseStrength(25)
        val MEDIUM = DenoiseStrength(50)
        val AGGRESSIVE = DenoiseStrength(100)
        val DEFAULT = LOW
    }
}

data class CropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

data class PassRequest(
    val id: String,
    val strength: Int,
) {
    init {
        require(strength in 0..100) { "pass strength must be 0..100, got $strength" }
    }
}

/** Full enhancement request — everything S2 collects. Batch-capable. */
data class EnhanceRequest(
    val inputUris: List<String>,
    val scale: ScaleFactor = ScaleFactor.X2,
    val mode: EngineMode = EngineMode.CREATIVE,
    val denoise: DenoiseStrength = DenoiseStrength.DEFAULT,
    val faceRestoreEnabled: Boolean = false,
    /** 0 = identity-fidelity, 100 = maximum enhancement (FR-4.2). */
    val faceRestoreStrength: Int = 50,
    val accelerator: Accelerator = Accelerator.AUTO,
    val useNeuralEngine: Boolean = true,
    /** Unsharp-mask post-pass for extra punch (skipped over MP ceiling for memory safety). */
    val sharpen: Boolean = true,
    val deblurEnabled: Boolean = false,
    val deblurStrength: Int = 50,
    val scratchRepairEnabled: Boolean = false,
    val scratchRepairStrength: Int = 50,
    val colorizeEnabled: Boolean = false,
    val colorizeStrength: Int = 50,
    val cropPreset: CropPreset? = null,
    val cacheLimitBytes: Long = DEFAULT_CACHE_LIMIT_BYTES,
    val exportPolicy: ExportPolicy = ExportPolicy(),
) {
    init {
        require(faceRestoreStrength in 0..100) {
            "face restore strength must be 0..100, got $faceRestoreStrength"
        }
        require(deblurStrength in 0..100) {
            "deblur strength must be 0..100, got $deblurStrength"
        }
        require(scratchRepairStrength in 0..100) {
            "scratch repair strength must be 0..100, got $scratchRepairStrength"
        }
        require(colorizeStrength in 0..100) {
            "colorize strength must be 0..100, got $colorizeStrength"
        }
        require(cacheLimitBytes in MIN_CACHE_LIMIT_BYTES..MAX_CACHE_LIMIT_BYTES) {
            "cache limit must be $MIN_CACHE_LIMIT_BYTES..$MAX_CACHE_LIMIT_BYTES bytes, got $cacheLimitBytes"
        }
    }

    companion object {
        const val DEFAULT_CACHE_LIMIT_BYTES: Long = 500L * 1024L * 1024L
        const val MIN_CACHE_LIMIT_BYTES: Long = 500L * 1024L * 1024L
        const val MAX_CACHE_LIMIT_BYTES: Long = 2L * 1024L * 1024L * 1024L
    }
}

enum class ProcessStep {
    PREPARING,
    DETECTING_FACES,
    PROCESSING_TILES,
    RESTORING_FACES,
    BLENDING,
    DONE;
}

/** Job progress reported to UI (US-06); batch-aware. */
data class JobProgress(
    val step: ProcessStep,
    val tilesDone: Int = 0,
    val tilesTotal: Int = 0,
    val backendUsed: String? = null,
    val outputUri: String? = null,
    val error: String? = null,
    val batchIndex: Int = 0,
    val batchTotal: Int = 1,
    val skippedSmallFaces: Int = 0,
    val itemCompleted: Boolean = false,
    val overallOverride: Float? = null,
) {
    val overall: Float
        get() {
            if (step == ProcessStep.DONE) return 1f
            overallOverride?.let { return it.coerceIn(0f, 1f) }
            val total = batchTotal.coerceAtLeast(1)
            val itemProgress = when (step) {
                ProcessStep.PREPARING -> 0.02f
                ProcessStep.DETECTING_FACES -> 0.91f
                ProcessStep.PROCESSING_TILES -> 0.05f + 0.85f * (tilesDone.toFloat() / tilesTotal.coerceAtLeast(1))
                ProcessStep.RESTORING_FACES -> 0.93f
                ProcessStep.BLENDING -> 0.97f
                ProcessStep.DONE -> 1f
            }
            return (batchIndex.toFloat() + itemProgress) / total
        }
}

sealed interface EnhanceResult {
    data class Success(
        val outputUri: String,
        val width: Int,
        val height: Int,
        val backendUsed: String,
        val skippedSmallFaces: Int = 0,
    ) : EnhanceResult

    data class Failure(val message: String, val cause: Throwable? = null) : EnhanceResult
}
