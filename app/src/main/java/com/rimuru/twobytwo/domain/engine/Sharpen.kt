package com.rimuru.twobytwo.domain.engine

/**
 * Image post-processing on RGBA byte buffers. Pure Kotlin.
 *
 * ponytail: scalar loops; JNI port is the upgrade when profiling demands it.
 */
object ImageOps {

    /**
     * Unsharp mask: out = src + amount * (src - blur(src)), 3x3 box blur approximation.
     * amount 0..1. In-place on a copy; alpha preserved.
     */
    fun unsharpMask(
        rgba: ByteArray,
        width: Int,
        height: Int,
        amount: Float,
    ) {
        require(rgba.size == width * height * 4)
        require(amount in 0f..2f)
        if (amount <= 0f) return

        val n = width * height
        // 3x3 box blur per channel (separable: horizontal then vertical)
        val blur = FloatArray(n * 3)

        // Horizontal pass
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val xm1 = (x - 1).coerceIn(0, width - 1)
                val xp1 = (x + 1).coerceIn(0, width - 1)
                for (c in 0 until 3) {
                    val i0 = (row + xm1) * 4 + c
                    val i1 = (row + x) * 4 + c
                    val i2 = (row + xp1) * 4 + c
                    blur[(row + x) * 3 + c] =
                        ((rgba[i0].toInt() and 0xFF) + (rgba[i1].toInt() and 0xFF) + (rgba[i2].toInt() and 0xFF)) / 3f
                }
            }
        }
        // Vertical pass into combined result
        val detail = FloatArray(3)
        for (y in 0 until height) {
            val ym1 = (y - 1).coerceIn(0, height - 1)
            val yp1 = (y + 1).coerceIn(0, height - 1)
            for (x in 0 until width) {
                for (c in 0 until 3) {
                    val b0 = blur[(ym1 * width + x) * 3 + c]
                    val b1 = blur[(y * width + x) * 3 + c]
                    val b2 = blur[(yp1 * width + x) * 3 + c]
                    val blurred = (b0 + b1 + b2) / 3f
                    val srcIdx = (y * width + x) * 4 + c
                    val src = (rgba[srcIdx].toInt() and 0xFF).toFloat()
                    detail[c] = src + amount * (src - blurred) - src // total delta
                    val out = (src + amount * (src - blurred) + 0.5f).toInt().coerceIn(0, 255)
                    rgba[srcIdx] = out.toByte()
                }
            }
        }
    }

    /**
     * Catmull-Rom bicubic upscale (cubic convolution a=-0.5) — notably sharper than
     * bilinear for the no-model fallback path. CHW float in, CHW float out.
     */
    fun bicubicUpscaleChw(
        input: FloatArray,
        w: Int,
        h: Int,
        scale: Int,
    ): FloatArray {
        val ow = w * scale
        val oh = h * scale
        val out = FloatArray(3 * ow * oh)

        // Precompute x contributions
        data class Contrib(val idx: IntArray, val wgt: FloatArray)
        val xc = Array(ow) { ox ->
            val sx = cubicCenter(ox, scale, w)
            val x0 = sx.toInt() - 1
            Contrib(
                IntArray(4) { (x0 + it).coerceIn(0, w - 1) },
                FloatArray(4) { cubicWeight(sx - (x0 + it)) },
            )
        }
        for (c in 0 until 3) {
            val plane = c * w * h
            val outPlane = c * ow * oh
            for (oy in 0 until oh) {
                val sy = cubicCenter(oy, scale, h)
                val y0 = sy.toInt() - 1
                val ys = IntArray(4) { (y0 + it).coerceIn(0, h - 1) }
                val wy = FloatArray(4) { cubicWeight(sy - (y0 + it)) }
                for (ox in 0 until ow) {
                    val xs = xc[ox]
                    var acc = 0f
                    for (j in 0 until 4) {
                        var rowAcc = 0f
                        val rowBase = plane + ys[j] * w
                        for (i in 0 until 4) {
                            rowAcc += input[rowBase + xs.idx[i]] * xs.wgt[i]
                        }
                        acc += rowAcc * wy[j]
                    }
                    out[outPlane + oy * ow + ox] = acc.coerceIn(0f, 1f)
                }
            }
        }
        return out
    }

    /** Source coordinate for output pixel under integer upscale. */
    private fun cubicCenter(outPx: Int, scale: Int, srcDim: Int): Float =
        ((outPx + 0.5f) / scale - 0.5f).coerceIn(0f, (srcDim - 1).toFloat())

    /** Catmull-Rom kernel weight, t = distance from sample point. */
    private fun cubicWeight(t: Float): Float {
        val at = kotlin.math.abs(t)
        val a2 = at * at
        val a3 = a2 * at
        return when {
            at <= 1f -> 1.5f * a3 - 2.5f * a2 + 1f
            at < 2f -> -0.5f * a3 + 2.5f * a2 - 4f * at + 2f
            else -> 0f
        }
    }
}
