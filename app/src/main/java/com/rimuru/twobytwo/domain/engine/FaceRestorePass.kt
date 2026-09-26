package com.rimuru.twobytwo.domain.engine

import kotlin.math.cos
import kotlin.math.roundToInt

private const val FACE_PADDING = 8

data class FaceBox(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

interface FaceDetector {
    val modelKey: InferenceEngine.ModelKey
        get() = InferenceEngine.ModelKey.FACE_DETECT

    fun detect(image: RgbaImage): List<FaceBox>
}

interface FaceRestorer {
    val modelKey: InferenceEngine.ModelKey
        get() = InferenceEngine.ModelKey.FACE_RESTORE

    fun restore(crop: RgbaImage): RgbaImage
}

class FaceRestorePass(
    strength: Int,
    private val detector: FaceDetector? = null,
    private val restorer: FaceRestorer? = null,
    private val isCancelled: () -> Boolean = { false },
) : ImagePass {
    private val configuredStrength = strength.coerceIn(0, 100)
    private val cropPadding = FACE_PADDING

    override val id = "face-restore"

    override fun apply(image: RgbaImage, context: PassContext): PassResult {
        checkPassCancellation(isCancelled)
        val effectiveStrength = minOf(context.strength, configuredStrength).coerceIn(0, 100)
        if (effectiveStrength == 0) return PassResult(image, false, "disabled")

        validateImage(image)
        val faceDetector = detector
        val faceRestorer = restorer
        if (faceDetector == null || faceRestorer == null) {
            val missing = when {
                faceDetector == null && faceRestorer == null -> "detector/restorer"
                faceDetector == null -> "detector"
                else -> "restorer"
            }
            return PassResult(
                image = image,
                usedFallback = true,
                detail = "face restoration fallback: $missing seam missing (model execution deferred)",
            )
        }
        if (faceDetector.modelKey != InferenceEngine.ModelKey.FACE_DETECT ||
            faceRestorer.modelKey != InferenceEngine.ModelKey.FACE_RESTORE
        ) {
            return PassResult(
                image = image,
                usedFallback = true,
                detail = "face restoration fallback: detector/restorer model seam mismatch",
            )
        }

        checkPassCancellation(isCancelled)
        val faces = faceDetector.detect(image)
        var skippedSmallFaces = 0
        for (face in faces) {
            checkPassCancellation(isCancelled)
            val box = normalize(face, image)
            if (box == null || box.width < MIN_FACE_SIZE || box.height < MIN_FACE_SIZE) {
                skippedSmallFaces++
                continue
            }

            val crop = extractPaddedCrop(image, box)
            checkPassCancellation(isCancelled)
            val restored = faceRestorer.restore(crop)
            checkPassCancellation(isCancelled)
            validateRestored(restored)
            blendResized(image, box, restored, effectiveStrength)
        }

        checkPassCancellation(isCancelled)
        return PassResult(
            image = image,
            usedFallback = false,
            detail = "face restoration seam completed; skippedSmallFaces=$skippedSmallFaces",
            skippedSmallFaces = skippedSmallFaces,
        )
    }

    private fun validateImage(image: RgbaImage) {
        require(image.width > 0 && image.height > 0) { "image dimensions must be positive" }
        val pixelCount = image.width.toLong() * image.height.toLong()
        require(pixelCount * 4L == image.pixels.size.toLong()) { "RGBA buffer size does not match dimensions" }
    }

    private fun validateRestored(image: RgbaImage) {
        require(image.width > 0 && image.height > 0) { "restored crop dimensions must be positive" }
        val pixelCount = image.width.toLong() * image.height.toLong()
        require(pixelCount * 4L == image.pixels.size.toLong()) { "restored crop buffer size does not match dimensions" }
    }

    private fun normalize(face: FaceBox, image: RgbaImage): FaceBox? {
        if (face.width <= 0 || face.height <= 0) return null
        val left = face.x.toLong().coerceIn(0L, image.width.toLong()).toInt()
        val top = face.y.toLong().coerceIn(0L, image.height.toLong()).toInt()
        val right = (face.x.toLong() + face.width.toLong()).coerceIn(0L, image.width.toLong()).toInt()
        val bottom = (face.y.toLong() + face.height.toLong()).coerceIn(0L, image.height.toLong()).toInt()
        if (right <= left || bottom <= top) return null
        return FaceBox(left, top, right - left, bottom - top)
    }

    private fun extractPaddedCrop(image: RgbaImage, box: FaceBox): RgbaImage {
        val left = (box.x.toLong() - cropPadding.toLong()).coerceAtLeast(0L).toInt()
        val top = (box.y.toLong() - cropPadding.toLong()).coerceAtLeast(0L).toInt()
        val right = (box.x.toLong() + box.width.toLong() + cropPadding.toLong())
            .coerceAtMost(image.width.toLong())
            .toInt()
        val bottom = (box.y.toLong() + box.height.toLong() + cropPadding.toLong())
            .coerceAtMost(image.height.toLong())
            .toInt()
        val width = right - left
        val height = bottom - top
        require(width > 0 && height > 0) { "face crop must have positive dimensions" }
        val pixels = ByteArray(width.toLong().times(height.toLong()).times(4L).toInt())
        for (row in 0 until height) {
            checkPassCancellation(isCancelled)
            val sourceOffset = ((top + row) * image.width + left) * 4
            val targetOffset = row * width * 4
            System.arraycopy(image.pixels, sourceOffset, pixels, targetOffset, width * 4)
        }
        return RgbaImage(pixels, width, height)
    }

    private fun blendResized(
        image: RgbaImage,
        box: FaceBox,
        restored: RgbaImage,
        strength: Int,
    ) {
        val feather = minOf(MAX_FEATHER, minOf(box.width, box.height) / 2)
        for (y in 0 until box.height) {
            checkPassCancellation(isCancelled)
            for (x in 0 until box.width) {
                val sourceX = (x.toLong() * restored.width / box.width)
                    .coerceIn(0L, (restored.width - 1).toLong())
                    .toInt()
                val sourceY = (y.toLong() * restored.height / box.height)
                    .coerceIn(0L, (restored.height - 1).toLong())
                    .toInt()
                val restoredIndex = (sourceY * restored.width + sourceX) * 4
                val targetX = box.x + x
                val targetY = box.y + y
                val targetIndex = (targetY * image.width + targetX) * 4
                val mask = featherWeight(x, y, box.width, box.height, feather) * strength / 100f
                if (mask <= 0f) continue
                for (channel in 0 until 3) {
                    val source = image.pixels[targetIndex + channel].toInt() and 0xFF
                    val restoredValue = restored.pixels[restoredIndex + channel].toInt() and 0xFF
                    image.pixels[targetIndex + channel] =
                        (source + (restoredValue - source) * mask).roundToInt()
                            .coerceIn(0, 255)
                            .toByte()
                }
            }
        }
    }

    private fun featherWeight(x: Int, y: Int, width: Int, height: Int, feather: Int): Float {
        if (feather <= 0) return 1f
        val edge = minOf(x, y, width - 1 - x, height - 1 - y)
        val position = (edge.toFloat() / feather.toFloat()).coerceIn(0f, 1f)
        return (0.5f - 0.5f * cos(Math.PI * position).toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        const val MIN_FACE_SIZE = 48
        const val DEFAULT_PADDING = FACE_PADDING
        private const val MAX_FEATHER = 8
    }
}
