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

    override fun decode(uri: String, maxMegapixels: Int): EnhanceImage.DecodedImage {
        val resolver = context.contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(Uri.parse(uri))!!.use { BitmapFactory.decodeStream(it, null, opts) }

        val mp = opts.outWidth.toLong() * opts.outHeight / 1_000_000.0
        require(mp <= maxMegapixels) { "input ${"%.1f".format(mp)} MP exceeds cap $maxMegapixels MP" }

        // Subsample only if needed to stay within cap (never upscales)
        var sample = 1
        while (opts.outWidth.toLong() * opts.outHeight / (sample.toLong() * sample) > maxMegapixels * 1_000_000) {
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
        val rowBytes = oriented.width * 4
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
    ) {
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
        val outUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        java.nio.ByteBuffer.wrap(rgba).rewind()
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba))

        resolver.openOutputStream(outUri)!!.use { os ->
            bitmap.compress(
                if (format == EnhanceImage.OutputFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                jpgQuality,
                os,
            )
        }

        // Copy orientation + timestamps from the source photo (US-08)
        if (exifSourceUri != null) {
            try {
                // Source is already orientation-corrected at decode; strip rotation so
                // the exported file doesn't get double-rotated.
                val srcExif = resolver.openInputStream(Uri.parse(exifSourceUri))!!.use { input ->
                    ExifInterface(input)
                }
                srcExif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")

                resolver.openOutputStream(outUri, "rw")!!.use { out ->
                    val outExif = ExifInterface(out)
                    val fields = listOf(
                        ExifInterface.TAG_DATETIME_ORIGINAL,
                        ExifInterface.TAG_MAKE,
                        ExifInterface.TAG_MODEL,
                    )
                    for (tag in fields) {
                        val value = srcExif.getAttribute(tag)
                        if (value != null) outExif.setAttribute(tag, value)
                    }
                    outExif.saveAttributes()
                }
            } catch (_: Exception) {
                // Metadata loss is non-fatal; image already saved.
            }
        }

        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(outUri, values, null, null)
        }
        bitmap.recycle()
    }

    private fun rotate(b: Bitmap, degrees: Float): Bitmap {
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }
}
