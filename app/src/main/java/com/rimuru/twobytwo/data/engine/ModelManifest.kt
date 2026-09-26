package com.rimuru.twobytwo.data.engine

import com.rimuru.twobytwo.domain.engine.InferenceEngine

/**
 * Model manifest (PRD §5.5): name, version, sha256 per model asset.
 * `models/README.md` documents how to regenerate checksums after conversion.
 */
data class ModelManifest(
    val entries: Map<InferenceEngine.ModelKey, Entry>,
) {
    data class Entry(
        val fileName: String,
        val version: String,
        val sha256: String,
    )

    companion object {
        val BUNDLED = ModelManifest(
            InferenceEngine.ModelKey.entries.associateWith { key ->
                when (key) {
                    InferenceEngine.ModelKey.CREATIVE_X2 -> Entry(
                        fileName = "realesrgan_compact_x2.onnx",
                        version = "2plus-fp32-op20",
                        sha256 = "fb3ce45a465b7b5a30a6c6ae8aa09cd0df192824fefdb07e16bae0af18ef60e9",
                    )
                    InferenceEngine.ModelKey.CREATIVE_X4 -> Entry(
                        fileName = "realesrgan_compact_x4.onnx",
                        version = "4plus-fp32-op20",
                        sha256 = "01be1ebcddc7a08663818ac96e7ab95e4f2c812aa4423c9b7e2e59a716a6626c",
                    )
                    else -> Entry(fileName = key.assetName, version = "0.0.0-placeholder", sha256 = "")
                }
            },
        )

        /**
         * The registry rejects blank checksums, so placeholder entries select the
         * classical fallback until real SHA-256 values are supplied.
         */
        val PLACEHOLDER = ModelManifest(
            InferenceEngine.ModelKey.entries.associateWith { key ->
                Entry(fileName = key.assetName, version = "0.0.0-placeholder", sha256 = "")
            },
        )
    }
}
