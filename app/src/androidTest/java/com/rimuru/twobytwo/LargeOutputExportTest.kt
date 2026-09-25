package com.rimuru.twobytwo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.spectrum.SpectrumException
import com.rimuru.twobytwo.data.media.MediaStoreImageIo
import com.rimuru.twobytwo.domain.model.ExportPolicy
import com.rimuru.twobytwo.domain.model.OutputFormat
import com.rimuru.twobytwo.domain.usecase.StreamingImageIo
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeOutputExportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outputNames = mutableListOf<String>()
    private val outputUris = mutableListOf<Uri>()
    private val sourceFiles = mutableListOf<File>()
    private val temporaryFiles = mutableListOf<File>()

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
        temporaryFiles.forEach { it.delete() }
    }

    @Test
    fun streamingExportPublishesRequestedFormatsAndDimensions() = runBlocking {
        val width = 257
        val height = 129

        listOf(OutputFormat.PNG, OutputFormat.JPEG, OutputFormat.WEBP).forEach { format ->
            val name = trackName("streaming-${format.name.lowercase()}.${format.fileExtension}")
            var rowIndex = 0
            val uri = Uri.parse(
                streamingIo().encodeStreaming(
                    width = width,
                    height = height,
                    destinationUri = name,
                    policy = ExportPolicy(format = format, keepExif = false),
                    exifSourceUri = null,
                ) { row ->
                    writePattern(row, rowIndex++)
                },
            )
            outputUris += uri

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)!!.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            assertEquals(width, options.outWidth)
            assertEquals(height, options.outHeight)
            assertFormatSignature(uri, format)
            assertOutput(uri, format, name)
        }
    }

    @Test
    fun streamingJpegUsesClampedQuality() = runBlocking {
        val requested79 = encodeJpeg(79)
        val requested80 = encodeJpeg(80)
        val requested100 = encodeJpeg(100)

        assertArrayEquals(requested80, requested79)
        assertFalse(requested100.contentEquals(requested80))
    }

    @Test
    fun streamingExportCopiesAndVerifiesExifAllowlist() = runBlocking {
        val source = sourceWithMetadata()
        val name = trackName("streaming-exif.jpg")
        val uri = Uri.parse(
            streamingIo().encodeStreaming(
                width = 2,
                height = 2,
                destinationUri = name,
                policy = ExportPolicy(
                    format = OutputFormat.JPEG,
                    keepExif = true,
                    keepGps = true,
                ),
                exifSourceUri = Uri.fromFile(source).toString(),
            ) { row ->
                row.fill(0x7F)
                row[3] = 0xFF.toByte()
            },
        )
        outputUris += uri

        val exif = openExif(uri)
        assertEquals("2020:01:02 03:04:05", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("Rimuru", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("Model X", exif.getAttribute(ExifInterface.TAG_MODEL))
        GPS_TAGS.forEach { tag -> assertNotNull(exif.getAttribute(tag)) }
        assertNull(exif.getAttribute(ExifInterface.TAG_ORIENTATION))
    }

    @Test
    fun transcodeFailureRemovesPendingRowAndTemporaryFile() = runBlocking {
        val name = trackName("streaming-failure.jpg")
        val baseline = artifactNames(STREAM_PREFIX)
        val plantedSpool = plantTileSpool()
        var temporaryFile: File? = null
        val result = runCatching {
            streamingIo().encodeStreaming(
                width = 2,
                height = 2,
                destinationUri = name,
                policy = ExportPolicy(format = OutputFormat.JPEG, keepExif = false),
                exifSourceUri = null,
            ) { row ->
                if (temporaryFile == null) {
                    val created = newArtifacts(STREAM_PREFIX, baseline)
                    assertTrue("streaming temporary file was not created", created.isNotEmpty())
                    temporaryFile = created.first()
                    temporaryFiles += checkNotNull(temporaryFile)
                    if (Build.VERSION.SDK_INT >= 29) assertTrue(rowIsPending(name))
                    RandomAccessFile(checkNotNull(temporaryFile), "rw").use { output ->
                        output.seek(1)
                        output.write(ByteArray(7))
                    }
                }
                row.fill(0x7F)
                row[3] = 0xFF.toByte()
            }
        }

        assertTrue(result.exceptionOrNull() is SpectrumException)
        assertFalse(rowExists(name))
        assertFalse(checkNotNull(temporaryFile).exists())
        assertTrue(newArtifacts(STREAM_PREFIX, baseline).isEmpty())
        assertTrue("export cleanup removed unrelated cache entries", plantedSpool.exists())
    }

    @Test
    fun cancellationDuringRowProductionRemovesPendingRowAndTemporaryFile() = runBlocking {
        val name = trackName("streaming-cancellation.jpg")
        val baseline = artifactNames(STREAM_PREFIX)
        val plantedSpool = plantTileSpool()
        var temporaryFile: File? = null
        val export = async {
            streamingIo().encodeStreaming(
                width = 2,
                height = 1,
                destinationUri = name,
                policy = ExportPolicy(format = OutputFormat.JPEG, keepExif = false),
                exifSourceUri = null,
            ) { row ->
                val created = newArtifacts(STREAM_PREFIX, baseline)
                assertTrue("streaming temporary file was not created", created.isNotEmpty())
                temporaryFile = created.first()
                temporaryFiles += checkNotNull(temporaryFile)
                row.fill(0x7F)
                row[3] = 0xFF.toByte()
                currentCoroutineContext()[Job]!!.cancel(CancellationException("cancelled during row production"))
            }
        }

        val error = runCatching { export.await() }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertFalse(rowExists(name))
        assertFalse(checkNotNull(temporaryFile).exists())
        assertTrue(newArtifacts(STREAM_PREFIX, baseline).isEmpty())
        assertTrue("export cleanup removed unrelated cache entries", plantedSpool.exists())
    }

    @Test
    fun scratchPreflightRejectsRequirementAboveAvailableCacheSpace() {
        streamingIo().checkScratchCapacity(0L)

        val failure = runCatching { streamingIo().checkScratchCapacity(Long.MAX_VALUE) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        val message = checkNotNull(failure).message.orEmpty()
        assertTrue(message.contains("insufficient temporary storage"))
        assertTrue(message.contains(Long.MAX_VALUE.toString()))
    }

    @Test
    fun mergedManifestDoesNotRequestInternetPermission() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)

        assertFalse(info.requestedPermissions.orEmpty().contains(Manifest.permission.INTERNET))
    }

    private suspend fun encodeJpeg(jpegQuality: Int): ByteArray {
        val name = trackName("streaming-quality-$jpegQuality.jpg")
        var rowIndex = 0
        val uri = Uri.parse(
            streamingIo().encodeStreaming(
                width = 64,
                height = 64,
                destinationUri = name,
                policy = ExportPolicy(
                    format = OutputFormat.JPEG,
                    jpegQuality = jpegQuality,
                    keepExif = false,
                ),
                exifSourceUri = null,
            ) { row ->
                writePattern(row, rowIndex++)
            },
        )
        outputUris += uri
        return context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    }

    private fun streamingIo(): StreamingImageIo = MediaStoreImageIo(context)

    private fun artifactNames(prefix: String): Set<String> =
        context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) }
            .mapTo(mutableSetOf()) { it.name }

    private fun newArtifacts(prefix: String, baseline: Set<String>): List<File> =
        context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) && it.name !in baseline }

    private fun plantTileSpool(): File {
        val spool = File(context.cacheDir, "${TILE_PREFIX}${UUID.randomUUID()}.rgba")
        spool.writeBytes(ByteArray(0))
        temporaryFiles += spool
        return spool
    }

    private fun assertFormatSignature(uri: Uri, format: OutputFormat) {
        val content = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue("encoded output is shorter than its signature", content.size >= 12)

        when (format) {
            OutputFormat.PNG -> assertArrayEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
                content.copyOf(8),
            )
            OutputFormat.JPEG -> assertArrayEquals(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
                content.copyOf(3),
            )
            OutputFormat.WEBP -> {
                assertEquals("RIFF", String(content, 0, 4, Charsets.US_ASCII))
                assertEquals("WEBP", String(content, 8, 4, Charsets.US_ASCII))
            }
        }
    }

    private fun writePattern(row: ByteArray, rowIndex: Int) {
        var x = 0
        while (x < row.size / 4) {
            val value = ((x * 17 + rowIndex * 31) and 0xFF).toByte()
            val offset = x * 4
            row[offset] = value
            row[offset + 1] = value
            row[offset + 2] = value
            row[offset + 3] = 0xFF.toByte()
            x += 1
        }
    }

    private fun trackName(prefix: String): String {
        val name = "rimuru2x_test_${prefix}_${UUID.randomUUID()}"
        outputNames += name
        return name
    }

    private fun sourceWithMetadata(): File {
        val file = File(context.cacheDir, "rimuru2x_test_source_${UUID.randomUUID()}")
        sourceFiles += file
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
        }
        bitmap.recycle()
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05")
        exif.setAttribute(ExifInterface.TAG_MAKE, "Rimuru")
        exif.setAttribute(ExifInterface.TAG_MODEL, "Model X")
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
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
        val columns = buildList {
            add(MediaStore.Images.Media.MIME_TYPE)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Images.Media.IS_PENDING)
        }.toTypedArray()
        context.contentResolver.query(uri, columns, null, null, null).use { cursor ->
            val actualCursor = checkNotNull(cursor)
            assertTrue(actualCursor.moveToFirst())
            assertEquals(format.mimeType, actualCursor.getString(0))
            assertTrue(actualCursor.getString(1).endsWith(".${format.fileExtension}"))
            assertTrue(actualCursor.getString(1).endsWith(name))
            if (Build.VERSION.SDK_INT >= 29) assertEquals(0, actualCursor.getInt(2))
        }
    }

    private fun rowExists(name: String): Boolean = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DISPLAY_NAME}=?",
        arrayOf(name),
        null,
    ).use { cursor -> cursor?.moveToFirst() == true }

    private fun rowIsPending(name: String): Boolean = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media.IS_PENDING),
        "${MediaStore.Images.Media.DISPLAY_NAME}=?",
        arrayOf(name),
        null,
    ).use { cursor ->
        cursor?.let { it.moveToFirst() && it.getInt(0) == 1 } == true
    }

    private companion object {
        const val STREAM_PREFIX = "rimuru2x_stream_"
        const val TILE_PREFIX = "rimuru2x_tiles_"
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
