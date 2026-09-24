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
