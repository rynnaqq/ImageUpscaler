package com.rimuru.twobytwo.domain.engine

interface ImagePass {
    val id: String
    fun apply(image: RgbaImage, context: PassContext): PassResult
}

data class RgbaImage(val pixels: ByteArray, val width: Int, val height: Int)

data class PassContext(
    val strength: Int,
    val models: ModelProvider,
) {
    init {
        require(strength in 0..100) { "pass strength must be 0..100, got $strength" }
    }
}

data class PassResult(
    val image: RgbaImage,
    val usedFallback: Boolean,
    val detail: String,
    val skippedSmallFaces: Int = 0,
)

internal fun checkPassCancellation(isCancelled: () -> Boolean) {
    if (isCancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
}
