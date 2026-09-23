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

    private fun toByte(v: Float): Byte {
        val i = (v.coerceIn(0f, 1f) * 255f).roundToInt()
        return i.toByte()
    }
}
