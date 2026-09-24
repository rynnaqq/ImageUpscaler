package com.rimuru.twobytwo.domain.engine

import kotlin.math.roundToInt

class DenoisePass(strength: Int) : ImagePass {
    private val configuredStrength = strength.coerceIn(0, 100)

    override val id = "denoise"

    override fun apply(image: RgbaImage, context: PassContext): PassResult {
        val effectiveStrength = minOf(context.strength, configuredStrength).coerceIn(0, 100)
        if (effectiveStrength == 0) return PassResult(image, false, "disabled")

        require(image.width > 0 && image.height > 0) { "image dimensions must be positive" }
        val pixelCount = image.width.toLong() * image.height.toLong()
        require(pixelCount * 4L == image.pixels.size.toLong()) { "RGBA buffer size does not match dimensions" }

        val output = image.pixels.copyOf()
        val blend = 0.15f + 0.85f * effectiveStrength / 100f
        for (y in 0 until image.height) {
            val ym1 = (y - 1).coerceAtLeast(0)
            val yp1 = (y + 1).coerceAtMost(image.height - 1)
            for (x in 0 until image.width) {
                val xm1 = (x - 1).coerceAtLeast(0)
                val xp1 = (x + 1).coerceAtMost(image.width - 1)
                for (channel in 0 until 3) {
                    var sum = 0
                    for (ny in ym1..yp1) {
                        val row = ny * image.width
                        for (nx in xm1..xp1) {
                            sum += image.pixels[(row + nx) * 4 + channel].toInt() and 0xFF
                        }
                    }
                    val blurred = sum / 9f
                    val index = (y * image.width + x) * 4 + channel
                    val source = image.pixels[index].toInt() and 0xFF
                    val value = source + (blurred - source) * blend
                    output[index] = value.roundToInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return PassResult(RgbaImage(output, image.width, image.height), true, "classical denoise fallback")
    }
}
