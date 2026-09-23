package com.rimuru.twobytwo.domain.model

/**
 * Core domain models. Pure Kotlin — no Android imports (PRD §5.3).
 */

enum class ScaleFactor(val multiplier: Int) {
    X2(2),
    X4(4);
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

/** Full enhancement request — everything S2 collects. */
data class EnhanceRequest(
    val inputUri: String,
    val scale: ScaleFactor = ScaleFactor.X2,
    val mode: EngineMode = EngineMode.CREATIVE,
    val denoise: DenoiseStrength = DenoiseStrength.DEFAULT,
    val faceRestoreEnabled: Boolean = false,
    /** 0 = identity-fidelity, 100 = maximum enhancement (FR-4.2). */
    val faceRestoreStrength: Int = 50,
    val accelerator: Accelerator = Accelerator.AUTO,
    val useNeuralEngine: Boolean = true,
)

enum class ProcessStep {
    PREPARING,
    DETECTING_FACES,
    PROCESSING_TILES,
    RESTORING_FACES,
    BLENDING,
    DONE;
}

/** Job progress reported to UI (US-06). */
data class JobProgress(
    val step: ProcessStep,
    val tilesDone: Int = 0,
    val tilesTotal: Int = 0,
    val backendUsed: String? = null,
) {
    val overall: Float
        get() = when (step) {
            ProcessStep.PREPARING -> 0.02f
            ProcessStep.DETECTING_FACES -> 0.05f
            ProcessStep.PROCESSING_TILES -> 0.05f + 0.85f * (tilesDone.toFloat() / tilesTotal.coerceAtLeast(1))
            ProcessStep.RESTORING_FACES -> 0.93f
            ProcessStep.BLENDING -> 0.97f
            ProcessStep.DONE -> 1f
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
