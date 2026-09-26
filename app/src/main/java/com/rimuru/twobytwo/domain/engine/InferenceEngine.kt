package com.rimuru.twobytwo.domain.engine

import com.rimuru.twobytwo.domain.model.Accelerator

/**
 * Swappable inference backend (PRD TC-2). Implementations: ONNX Runtime today,
 * NCNN/TFLite later — callers never see the runtime.
 */
interface InferenceEngine : AutoCloseable {

    /** Human-readable backend name for job status ("ORT CPU FP16" etc., HW-3). */
    val backendName: String

    /**
     * Run super-resolution on a single tile.
     *
     * @param input RGBA float CHW tensor, values [0,1], dims [3, h, w]
     * @param tileWidth/tileHeight tile dimensions
     * @return output RGBA float CHW tensor [3, h*scale, w*scale]
     */
    fun upscaleTile(
        input: FloatArray,
        tileWidth: Int,
        tileHeight: Int,
        modelKey: ModelKey,
    ): FloatArray

    /** True if the given accelerator probed OK on this device (HW-1). */
    fun isAvailable(accelerator: Accelerator): Boolean

    enum class ModelKey(val assetName: String) {
        CREATIVE_X2("realesrgan_compact_x2.onnx"),
        CREATIVE_X4("realesrgan_compact_x4.onnx"),
        PRECISION_X2("swin2sr_light_x2.onnx"),
        PRECISION_X4("swin2sr_light_x4.onnx"),
        FACE_RESTORE("gfpgan_mobile.onnx"),
        FACE_DETECT("blazeface_short_range.onnx"),
        SCRATCH_REPAIR("scratch_repair.onnx"),
        COLORIZE("colorize.onnx"),
    }

    companion object {
        /** Deterministic tile dims for a model key; models are fixed-input or dynamic. */
        fun scaleFor(modelKey: ModelKey): Int = when (modelKey) {
            ModelKey.CREATIVE_X2, ModelKey.PRECISION_X2 -> 2
            ModelKey.CREATIVE_X4, ModelKey.PRECISION_X4 -> 4
            ModelKey.FACE_RESTORE,
            ModelKey.FACE_DETECT,
            ModelKey.SCRATCH_REPAIR,
            ModelKey.COLORIZE -> 1
        }
    }
}
