package com.rimuru.twobytwo.data.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.rimuru.twobytwo.domain.usecase.EnhanceImage
import java.io.InputStream

/**
 * Scoped-storage image I/O (PRD §5.2): decode picked content:// to RGBA, encode
 * PNG/JPEG(q=97) into MediaStore, copy Exif orientation/timestamps (US-08).
 */
class MediaStoreImageIo(private val context: Context) : EnhanceImage.ImageIo {

    override fun measure(uri: String, maxMegapixels: Int): EnhanceImage.Dimensions {
        val resolver = context.contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(Uri.parse(uri))!!.use { BitmapFactory.decodeStream(it, null, opts) }
        require(opts.outWidth > 0 && opts.outHeight > 0) { "decode failed for $uri" }

        val mp = opts.outWidth.toLong() * opts.outHeight / 1_000_000.0
        require(mp <= maxMegapixels) { "input ${"%.1f".format(mp)} MP exceeds cap $maxMegapixels MP" }
        val orientation = resolver.openInputStream(Uri.parse(uri))!!.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        }
        val swapsDimensions = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        return EnhanceImage.Dimensions(
            width = if (swapsDimensions) opts.outHeight else opts.outWidth,
            height = if (swapsDimensions) opts.outWidth else opts.outHeight,
        )
    }

    override fun decode(uri: String, maxMegapixels: Int): EnhanceImage.DecodedImage {
        val dimensions = measure(uri, maxMegapixels)
        val resolver = context.contentResolver
        var sample = 1
        while (dimensions.width.toLong() * dimensions.height / (sample.toLong() * sample) > maxMegapixels.toLong() * 1_000_000L) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(Uri.parse(uri))!!.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: error("decode failed for $uri")

        // Honor Exif orientation
        val oriented = when (
            resolver.openInputStream(Uri.parse(uri))!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotate(bitmap, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotate(bitmap, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotate(bitmap, 270f)
            else -> bitmap
        }

        val rgba = ByteArray(oriented.width * oriented.height * 4)
        val buffer = java.nio.ByteBuffer.wrap(rgba)
        oriented.copyPixelsToBuffer(buffer)
        oriented.recycle()

        return EnhanceImage.DecodedImage(rgba, oriented.width, oriented.height)
    }

    override fun encode(
        rgba: ByteArray,
        width: Int,
        height: Int,
        destinationUri: String,
        format: EnhanceImage.OutputFormat,
        exifSourceUri: String?,
    ): String {
        val resolver = context.contentResolver
        val (mime, jpgQuality) = when (format) {
            EnhanceImage.OutputFormat.PNG -> "image/png" to 100
            EnhanceImage.OutputFormat.JPEG -> "image/jpeg" to 97 // PRD: q >= 95
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, destinationUri)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Rimuru2x")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var insertedUri: Uri? = null
        try {
            val outUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            insertedUri = outUri
            java.nio.ByteBuffer.wrap(rgba).rewind()
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba))

            resolver.openOutputStream(outUri)!!.use { os ->
                check(
                    bitmap.compress(
                        if (format == EnhanceImage.OutputFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                        jpgQuality,
                        os,
                    ),
                ) { "image compression failed" }
            }

            // Copy capture metadata from the source photo (US-08). Output orientation is
            // already baked in at decode time, so orientation is deliberately not copied.
            if (exifSourceUri != null) {
                try {
                    val srcExif = resolver.openInputStream(Uri.parse(exifSourceUri))!!.use { input ->
                        ExifInterface(input)
                    }
                    val pfd = resolver.openFileDescriptor(outUri, "rw")
                    if (pfd != null) {
                        pfd.use {
                            val outExif = ExifInterface(it.fileDescriptor)
                            val tags = listOf(
                                ExifInterface.TAG_DATETIME_ORIGINAL,
                                ExifInterface.TAG_MAKE,
                                ExifInterface.TAG_MODEL,
                            )
                            for (tag in tags) {
                                val value = srcExif.getAttribute(tag)
                                if (value != null) outExif.setAttribute(tag, value)
                            }
                            outExif.saveAttributes()
                        }
                    }
                } catch (_: Exception) {
                    // Metadata loss is non-fatal; image already saved.
                }
            }

            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                check(resolver.update(outUri, values, null, null) == 1) { "MediaStore publish failed" }
            }
            return outUri.toString()
        } catch (t: Throwable) {
            insertedUri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            throw t
        } finally {
            bitmap.recycle()
        }
    }

    private fun rotate(b: Bitmap, degrees: Float): Bitmap {
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }
}
