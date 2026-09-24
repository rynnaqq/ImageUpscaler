package com.rimuru.twobytwo.domain.engine

interface ModelProvider {
    fun load(key: InferenceEngine.ModelKey): ModelHandle?
}

interface ModelHandle : AutoCloseable {
    val backendName: String
    val inputScale: Int
    val modelPath: String? get() = null

    override fun close()
}
