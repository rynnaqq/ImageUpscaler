package com.rimuru.twobytwo.domain.engine

class DeblurPass(strength: Int) : ImagePass {
    private val configuredStrength = strength.coerceIn(0, 100)

    override val id = "deblur"

    override fun apply(image: RgbaImage, context: PassContext): PassResult {
        val effectiveStrength = minOf(context.strength, configuredStrength).coerceIn(0, 100)
        if (effectiveStrength == 0) return PassResult(image, false, "disabled")

        require(image.width > 0 && image.height > 0) { "image dimensions must be positive" }
        val pixelCount = image.width.toLong() * image.height.toLong()
        require(pixelCount * 4L == image.pixels.size.toLong()) { "RGBA buffer size does not match dimensions" }

        val output = image.pixels.copyOf()
        val amount = 0.10f + 0.25f * effectiveStrength / 100f
        ImageOps.unsharpMask(output, image.width, image.height, amount)
        return PassResult(RgbaImage(output, image.width, image.height), true, "classical deblur fallback")
    }
}
