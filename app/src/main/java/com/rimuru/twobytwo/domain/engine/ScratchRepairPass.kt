package com.rimuru.twobytwo.domain.engine

import kotlin.math.roundToInt

class ScratchRepairPass(
    strength: Int,
    private val isCancelled: () -> Boolean = { false },
) : ImagePass {
    private val configuredStrength = strength.coerceIn(0, 100)

    override val id = "scratch-repair"

    override fun apply(image: RgbaImage, context: PassContext): PassResult {
        checkPassCancellation(isCancelled)
        val effectiveStrength = minOf(context.strength, configuredStrength).coerceIn(0, 100)
        if (effectiveStrength == 0) return PassResult(image, false, "disabled")

        require(image.width > 0 && image.height > 0) { "image dimensions must be positive" }
        val pixelCount = image.width.toLong() * image.height.toLong()
        require(pixelCount * 4L == image.pixels.size.toLong()) { "RGBA buffer size does not match dimensions" }

        checkPassCancellation(isCancelled)
        val mask = BooleanArray(pixelCount.toInt())
        for (y in 0 until image.height) {
            checkPassCancellation(isCancelled)
            for (x in 0 until image.width) {
                if (isDefect(image, x, y)) {
                    mask[y * image.width + x] = true
                }
            }
        }

        val output = image.pixels.copyOf()
        val blend = effectiveStrength / 100f
        for (y in 0 until image.height) {
            checkPassCancellation(isCancelled)
            for (x in 0 until image.width) {
                val pixel = y * image.width + x
                if (!mask[pixel]) continue
                var red = 0
                var green = 0
                var blue = 0
                var neighbors = 0
                for (ny in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(image.height - 1)) {
                    for (nx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(image.width - 1)) {
                        if (nx == x && ny == y) continue
                        val neighborPixel = ny * image.width + nx
                        if (mask[neighborPixel]) continue
                        val neighborIndex = neighborPixel * 4
                        red += image.pixels[neighborIndex].toInt() and 0xFF
                        green += image.pixels[neighborIndex + 1].toInt() and 0xFF
                        blue += image.pixels[neighborIndex + 2].toInt() and 0xFF
                        neighbors++
                    }
                }
                if (neighbors == 0) continue
                val index = pixel * 4
                output[index] = blendChannel(image.pixels[index].toInt() and 0xFF, red.toFloat() / neighbors, blend)
                output[index + 1] = blendChannel(
                    image.pixels[index + 1].toInt() and 0xFF,
                    green.toFloat() / neighbors,
                    blend,
                )
                output[index + 2] = blendChannel(
                    image.pixels[index + 2].toInt() and 0xFF,
                    blue.toFloat() / neighbors,
                    blend,
                )
            }
        }
        checkPassCancellation(isCancelled)
        return PassResult(
            RgbaImage(output, image.width, image.height),
            true,
            "classical scratch repair fallback (local defect mask/inpaint; model execution deferred)",
        )
    }

    private fun isDefect(image: RgbaImage, x: Int, y: Int): Boolean {
        val center = luminance(image.pixels, (y * image.width + x) * 4)
        val horizontalOutlier = when {
            image.width == 1 -> false
            x == 0 -> isOutlier(center, luminance(image.pixels, (y * image.width + 1) * 4))
            x == image.width - 1 -> isOutlier(center, luminance(image.pixels, (y * image.width + x - 1) * 4))
            else -> isOutlier(
                center,
                luminance(image.pixels, (y * image.width + x - 1) * 4),
                luminance(image.pixels, (y * image.width + x + 1) * 4),
            )
        }
        val verticalOutlier = when {
            image.height == 1 -> false
            y == 0 -> isOutlier(center, luminance(image.pixels, (image.width + x) * 4))
            y == image.height - 1 -> isOutlier(center, luminance(image.pixels, ((y - 1) * image.width + x) * 4))
            else -> isOutlier(
                center,
                luminance(image.pixels, ((y - 1) * image.width + x) * 4),
                luminance(image.pixels, ((y + 1) * image.width + x) * 4),
            )
        }
        return horizontalOutlier || verticalOutlier
    }

    private fun isOutlier(center: Int, neighbor: Int): Boolean =
        center >= neighbor + 32 || center <= neighbor - 32

    private fun isOutlier(center: Int, first: Int, second: Int): Boolean {
        val minimum = minOf(first, second)
        val maximum = maxOf(first, second)
        return center >= maximum + 32 || center <= minimum - 32
    }

    private fun luminance(pixels: ByteArray, index: Int): Int {
        val red = pixels[index].toInt() and 0xFF
        val green = pixels[index + 1].toInt() and 0xFF
        val blue = pixels[index + 2].toInt() and 0xFF
        return (54 * red + 183 * green + 19 * blue) / 256
    }

    private fun blendChannel(source: Int, average: Float, amount: Float): Byte =
        (source + (average - source) * amount).roundToInt().coerceIn(0, 255).toByte()
}
