package com.rimuru.twobytwo.domain.engine

interface ModelProvider : AutoCloseable {
    fun load(key: InferenceEngine.ModelKey): ModelHandle?

    override fun close() = Unit
}

interface ModelHandle : AutoCloseable {
    val backendName: String
    val inputScale: Int
    val modelPath: String? get() = null

    override fun close()
}
