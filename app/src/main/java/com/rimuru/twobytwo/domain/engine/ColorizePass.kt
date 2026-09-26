package com.rimuru.twobytwo.domain.engine

import kotlin.math.roundToInt

class ColorizePass(
    strength: Int,
    private val isCancelled: () -> Boolean = { false },
) : ImagePass {
    private val configuredStrength = strength.coerceIn(0, 100)
    private val redChroma = 0.05f * 255f
    private val greenChroma = -0.01f * 255f
    private val blueChroma = -0.045789474f * 255f

    override val id = "colorize"

    override fun apply(image: RgbaImage, context: PassContext): PassResult {
        checkPassCancellation(isCancelled)
        val effectiveStrength = minOf(context.strength, configuredStrength).coerceIn(0, 100)
        if (effectiveStrength == 0) return PassResult(image, false, "disabled")

        require(image.width > 0 && image.height > 0) { "image dimensions must be positive" }
        val pixelCount = image.width.toLong() * image.height.toLong()
        require(pixelCount * 4L == image.pixels.size.toLong()) { "RGBA buffer size does not match dimensions" }

        checkPassCancellation(isCancelled)
        val output = image.pixels.copyOf()
        val amount = effectiveStrength / 100f
        for (y in 0 until image.height) {
            checkPassCancellation(isCancelled)
            for (x in 0 until image.width) {
                val index = (y * image.width + x) * 4
                val luminance = luminance(image.pixels, index)
                val scale = chromaScale(luminance, amount)
                output[index] = (luminance + redChroma * amount * scale)
                    .roundToInt().coerceIn(0, 255).toByte()
                output[index + 1] = (luminance + greenChroma * amount * scale)
                    .roundToInt().coerceIn(0, 255).toByte()
                output[index + 2] = (luminance + blueChroma * amount * scale)
                    .roundToInt().coerceIn(0, 255).toByte()
            }
        }
        checkPassCancellation(isCancelled)
        return PassResult(
            RgbaImage(output, image.width, image.height),
            true,
            "classical luminance-preserving tint fallback (semantic colorization unavailable; model execution deferred)",
        )
    }

    private fun luminance(pixels: ByteArray, index: Int): Float {
        val red = pixels[index].toInt() and 0xFF
        val green = pixels[index + 1].toInt() and 0xFF
        val blue = pixels[index + 2].toInt() and 0xFF
        return (54f * red + 183f * green + 19f * blue) / 256f
    }

    private fun chromaScale(luminance: Float, amount: Float): Float {
        val redDelta = redChroma * amount
        val greenDelta = greenChroma * amount
        val blueDelta = blueChroma * amount
        var scale = 1f
        scale = limitScale(luminance, redDelta, scale)
        scale = limitScale(luminance, greenDelta, scale)
        scale = limitScale(luminance, blueDelta, scale)
        return scale
    }

    private fun limitScale(luminance: Float, delta: Float, current: Float): Float = when {
        delta > 0f -> minOf(current, (255f - luminance) / delta)
        delta < 0f -> minOf(current, luminance / -delta)
        else -> current
    }
}
