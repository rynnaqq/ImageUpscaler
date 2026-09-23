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
         * ponytail: placeholder manifest with empty sha256 = "accept any file".
         * Replace checksums with real values from models/README.md conversion step;
         * before Play release, empty sha256 must hard-fail (see ModelsApi TODO in README).
         */
        val PLACEHOLDER = ModelManifest(
            InferenceEngine.ModelKey.entries.associateWith { key ->
                Entry(fileName = key.assetName, version = "0.0.0-placeholder", sha256 = "")
            },
        )
    }
}
