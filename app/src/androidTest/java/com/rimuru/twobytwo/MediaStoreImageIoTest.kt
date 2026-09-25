package com.rimuru.twobytwo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.rimuru.twobytwo.data.media.MediaStoreImageIo
import com.rimuru.twobytwo.domain.model.ExportPolicy
import com.rimuru.twobytwo.domain.model.OutputFormat
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreImageIoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outputNames = mutableListOf<String>()
    private val outputUris = mutableListOf<Uri>()
    private val sourceFiles = mutableListOf<File>()

    @After
    fun cleanUp() {
        outputUris.forEach { uri -> context.contentResolver.delete(uri, null, null) }
        outputNames.forEach { name ->
            context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.DISPLAY_NAME}=?",
                arrayOf(name),
            )
        }
        sourceFiles.forEach { it.delete() }
    }

    @Test
    fun eachFormatIsDecodableAndPublished() {
        listOf(OutputFormat.PNG, OutputFormat.JPEG, OutputFormat.WEBP).forEach { format ->
            val name = trackName("${format.name.lowercase()}.${format.fileExtension}")
            val uri = Uri.parse(
                io().encode(pixelRgba(), 1, 1, name, ExportPolicy(format = format, keepExif = false), null),
            )
            outputUris += uri

            val decoded = context.contentResolver.openInputStream(uri)!!.use { input ->
                BitmapFactory.decodeStream(input)
            }
            assertNotNull(decoded)
            decoded!!.recycle()
            assertOutput(uri, format, name)
        }
    }

    @Test
    fun exifOnlyCopiesNonLocationTags() {
        val source = sourceWithMetadata()
        val name = trackName("exif")
        val uri = Uri.parse(
            io().encode(
                pixelRgba(),
                1,
                1,
                name,
                ExportPolicy(keepExif = true, keepGps = false),
                Uri.fromFile(source).toString(),
            ),
        )
        outputUris += uri

        val exif = openExif(uri)
        assertEquals("2020:01:02 03:04:05", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("Rimuru", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("Model X", exif.getAttribute(ExifInterface.TAG_MODEL))
        GPS_TAGS.forEach { tag -> assertNull(exif.getAttribute(tag)) }
    }

    @Test
    fun gpsOnlyCopiesGpsTags() {
        val source = sourceWithMetadata()
        val name = trackName("gps")
        val uri = Uri.parse(
            io().encode(
                pixelRgba(),
                1,
                1,
                name,
                ExportPolicy(keepExif = false, keepGps = true),
                Uri.fromFile(source).toString(),
            ),
        )
        outputUris += uri

        val exif = openExif(uri)
        GPS_TAGS.forEach { tag -> assertNotNull(exif.getAttribute(tag)) }
        NON_LOCATION_TAGS.forEach { tag -> assertNull(exif.getAttribute(tag)) }
    }

    @Test
    fun noMetadataSkipsAllExifTags() {
        val name = trackName("none")
        val uri = Uri.parse(
            io().encode(
                pixelRgba(),
                1,
                1,
                name,
                ExportPolicy(keepExif = false, keepGps = false),
                null,
            ),
        )
        outputUris += uri

        val exif = openExif(uri)
        (NON_LOCATION_TAGS + GPS_TAGS).forEach { tag -> assertNull(exif.getAttribute(tag)) }
    }

    @Test
    fun requestedMetadataWritesForEveryFormat() {
        listOf(OutputFormat.PNG, OutputFormat.JPEG, OutputFormat.WEBP).forEach { format ->
            val source = sourceWithMetadata()
            val name = trackName("metadata-${format.name.lowercase()}")
            val uri = Uri.parse(
                io().encode(
                    pixelRgba(),
                    1,
                    1,
                    name,
                    ExportPolicy(format = format, keepExif = true, keepGps = true),
                    Uri.fromFile(source).toString(),
                ),
            )
            outputUris += uri

            val exif = openExif(uri)
            (NON_LOCATION_TAGS + GPS_TAGS).forEach { tag -> assertNotNull(exif.getAttribute(tag)) }
        }
    }

    @Test
    fun unreadableExifSourceFailsAndRemovesPendingRow() {
        val source = trackFile("unreadable", "not an image".toByteArray())
        val name = trackName("unreadable")
        val result = runCatching {
            io().encode(
                pixelRgba(),
                1,
                1,
                name,
                ExportPolicy(keepExif = true, keepGps = false),
                Uri.fromFile(source).toString(),
            )
        }

        assertTrue(result.isFailure)
        assertFalse(rowExists(name))
    }

    private fun io() = MediaStoreImageIo(context)

    private fun pixelRgba() = ByteArray(4) { index ->
        if (index == 3) 0xFF.toByte() else 0x7F.toByte()
    }

    private fun trackName(prefix: String): String {
        val name = "rimuru2x_test_${prefix}_${UUID.randomUUID()}"
        outputNames += name
        return name
    }

    private fun trackFile(prefix: String, bytes: ByteArray): File {
        val file = File(context.cacheDir, "rimuru2x_test_${prefix}_${UUID.randomUUID()}")
        file.writeBytes(bytes)
        sourceFiles += file
        return file
    }

    private fun sourceWithMetadata(): File {
        val file = trackFile("source", ByteArray(0))
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
        }
        bitmap.recycle()
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05")
        exif.setAttribute(ExifInterface.TAG_MAKE, "Rimuru")
        exif.setAttribute(ExifInterface.TAG_MODEL, "Model X")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "0/1,7/1,0/1")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "10/1")
        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, "0")
        exif.saveAttributes()
        return file
    }

    private fun openExif(uri: Uri): ExifInterface =
        context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }

    private fun assertOutput(uri: Uri, format: OutputFormat, name: String) {
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.IS_PENDING,
            ),
            null,
            null,
            null,
        ).use { cursor ->
            assertTrue(cursor!!.moveToFirst())
            assertEquals(format.mimeType, cursor.getString(0))
            assertTrue(cursor.getString(1).endsWith(".${format.fileExtension}"))
            assertTrue(cursor.getString(1).endsWith(name))
            if (Build.VERSION.SDK_INT >= 29) assertEquals(0, cursor.getInt(2))
        }
    }

    private fun rowExists(name: String): Boolean = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DISPLAY_NAME}=?",
        arrayOf(name),
        null,
    ).use { cursor -> cursor?.moveToFirst() == true }

    private companion object {
        val NON_LOCATION_TAGS = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
        )
        val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
        )
    }
}
