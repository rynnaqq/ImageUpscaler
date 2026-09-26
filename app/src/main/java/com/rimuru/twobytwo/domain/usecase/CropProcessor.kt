package com.rimuru.twobytwo.domain.usecase

import com.rimuru.twobytwo.domain.engine.RgbaImage
import com.rimuru.twobytwo.domain.model.CropPreset
import com.rimuru.twobytwo.domain.model.CropRect
import kotlin.math.floor

object CropProcessor {

    fun centerRect(source: RgbaImage, preset: CropPreset): CropRect {
        validateSource(source)
        val ratio = preset.ratio
        require(ratio.isFinite() && ratio > 0.0) { "crop ratio must be finite and positive" }

        val sourceRatio = source.width.toDouble() / source.height.toDouble()
        val cropWidth: Int
        val cropHeight: Int
        if (sourceRatio > ratio) {
            cropHeight = source.height
            cropWidth = floor(source.height.toDouble() * ratio).toInt().coerceIn(1, source.width)
        } else {
            cropWidth = source.width
            cropHeight = floor(source.width.toDouble() / ratio).toInt().coerceIn(1, source.height)
        }

        val rect = CropRect(
            left = (source.width - cropWidth) / 2,
            top = (source.height - cropHeight) / 2,
            width = cropWidth,
            height = cropHeight,
        )
        check(rect.left >= 0 && rect.top >= 0) { "crop rectangle is outside source bounds" }
        check(rect.left + rect.width <= source.width && rect.top + rect.height <= source.height) {
            "crop rectangle is outside source bounds"
        }
        return rect
    }

    fun centerCrop(source: RgbaImage, preset: CropPreset): RgbaImage {
        val rect = centerRect(source, preset)
        val output = ByteArray((rect.width.toLong() * rect.height.toLong() * 4L).toInt())
        for (row in 0 until rect.height) {
            val sourceOffset = ((rect.top + row).toLong() * source.width + rect.left).toInt() * 4
            val outputOffset = row * rect.width * 4
            System.arraycopy(source.pixels, sourceOffset, output, outputOffset, rect.width * 4)
        }
        return RgbaImage(output, rect.width, rect.height)
    }

    private fun validateSource(source: RgbaImage) {
        require(source.width > 0 && source.height > 0) { "image dimensions must be positive" }
        val pixelCount = source.width.toLong() * source.height.toLong()
        require(pixelCount <= Int.MAX_VALUE.toLong() / 4L) { "image exceeds RGBA buffer limit" }
        require(pixelCount * 4L == source.pixels.size.toLong()) {
            "RGBA buffer size does not match dimensions"
        }
    }
}
