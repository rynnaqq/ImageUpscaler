package com.rimuru.twobytwo.domain.engine

import kotlin.math.roundToInt

/**
 * RGBA <-> float CHW [0,1] conversion + tile blending. Pure Kotlin.
 * PRD §5.2 routes heavy pixel math to NDK in a later milestone; this is the
 * correct-but-CPU reference implementation the JNI path must match bit-for-bit
 * in behavior. ponytail: scalar loops ~30-60ms/tile on modern phones; swap in
 * JNI when M1 profiling says so.
 */
object TensorCodec {

    /** RGBA_8888 pixel buffer -> CHW float [0,1], 3 channels (alpha dropped). */
    fun rgbaToChw(rgba: ByteArray, pixelCount: Int): FloatArray {
        val out = FloatArray(3 * pixelCount)
        val plane = pixelCount
        for (i in 0 until pixelCount) {
            val p = i * 4
            out[i] = (rgba[p].toInt() and 0xFF) / 255f
            out[plane + i] = (rgba[p + 1].toInt() and 0xFF) / 255f
            out[2 * plane + i] = (rgba[p + 2].toInt() and 0xFF) / 255f
        }
        return out
    }

    /** CHW float [0,1] -> RGBA_8888 (alpha = opaque). */
    fun chwToRgba(chw: FloatArray, width: Int, height: Int): ByteArray {
        val pixelCount = width * height
        require(chw.size >= 3 * pixelCount) { "buffer too small: ${chw.size} < ${3 * pixelCount}" }
        val out = ByteArray(4 * pixelCount)
        val plane = pixelCount
        for (i in 0 until pixelCount) {
            val p = i * 4
            out[p] = toByte(chw[i])
            out[p + 1] = toByte(chw[plane + i])
            out[p + 2] = toByte(chw[2 * plane + i])
            out[p + 3] = 0xFF.toByte()
        }
        return out
    }

    /**
     * Grow a CHW tile to even width and height by replicating the last row and
     * the last column. The bundled CREATIVE_X2 graph ends in a reshape-based
     * pixel shuffle that cannot run odd H/W, and `TilingManager` hands the engine
     * odd tiles whenever the image width or height is odd.
     *
     * @return the input unchanged when both dimensions are already even.
     */
    fun padChwToEven(input: FloatArray, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0) { "tile dims must be positive, got ${width}x$height" }
        val paddedWidth = width + (width and 1)
        val paddedHeight = height + (height and 1)
        if (paddedWidth == width && paddedHeight == height) return input
        require(input.size >= 3 * width * height) { "buffer too small: ${input.size} < ${3 * width * height}" }

        val out = FloatArray(3 * paddedWidth * paddedHeight)
        for (c in 0 until 3) {
            val src = c * width * height
            val dst = c * paddedWidth * paddedHeight
            for (y in 0 until height) {
                System.arraycopy(input, src + y * width, out, dst + y * paddedWidth, width)
            }
            if (paddedWidth > width) {
                for (y in 0 until height) {
                    out[dst + y * paddedWidth + width] = input[src + y * width + width - 1]
                }
            }
        }
        if (paddedHeight > height) {
            val lastRow = (height - 1) * paddedWidth
            for (c in 0 until 3) {
                val dst = c * paddedWidth * paddedHeight
                System.arraycopy(out, dst + lastRow, out, dst + height * paddedWidth, paddedWidth)
            }
        }
        return out
    }

    /**
     * Crop the top-left [outWidth]x[outHeight] corner out of a CHW plane, undoing
     * [padChwToEven] after inference. The engine contract is exactly
     * `3 * h * scale * w * scale` samples, and callers such as
     * `TensorCodec.chwToRgba` size their read from the requested tile, so the
     * padded tail must be dropped here.
     *
     * @return the input unchanged when the requested size already matches.
     */
    fun cropChw(
        input: FloatArray,
        width: Int,
        height: Int,
        outWidth: Int,
        outHeight: Int,
    ): FloatArray {
        require(width > 0 && height > 0) { "plane dims must be positive, got ${width}x$height" }
        require(outWidth in 1..width && outHeight in 1..height) {
            "crop ${outWidth}x$outHeight must fit inside ${width}x$height"
        }
        if (outWidth == width && outHeight == height) return input
        require(input.size >= 3 * width * height) { "buffer too small: ${input.size} < ${3 * width * height}" }

        val out = FloatArray(3 * outWidth * outHeight)
        for (c in 0 until 3) {
            val src = c * width * height
            val dst = c * outWidth * outHeight
            for (y in 0 until outHeight) {
                System.arraycopy(input, src + y * width, out, dst + y * outWidth, outWidth)
            }
        }
        return out
    }

    private fun toByte(v: Float): Byte {
        val i = (v.coerceIn(0f, 1f) * 255f).roundToInt()
        return i.toByte()
    }
}
