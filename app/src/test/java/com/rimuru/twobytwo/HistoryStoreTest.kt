package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.history.FileHistoryStore
import com.rimuru.twobytwo.data.history.HistoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HistoryStoreTest {

    @Test
    fun `save and find round trips every field`() {
        val root = Files.createTempDirectory("history-round-trip").toFile()
        val store = FileHistoryStore(root)
        val record = HistoryRecord(
            id = "job-round-trip",
            sourceUri = "content://source/round-trip",
            settingsJson = "{\"scale\":2,\"mode\":\"creative\"}",
            outputUri = "content://output/round-trip",
            width = 1280,
            height = 720,
            createdAt = 1_700_000_000_000L,
            parentId = "parent-round-trip",
        )

        store.save(record)

        assertEquals(record, store.find(record.id))
    }

    @Test
    fun `list returns newest records first`() {
        val root = Files.createTempDirectory("history-order").toFile()
        val store = FileHistoryStore(root)
        val older = record("job-old", createdAt = 10L)
        val newer = record("job-new", createdAt = 30L)
        val middle = record("job-middle", createdAt = 20L)

        store.save(older)
        store.save(newer)
        store.save(middle)

        assertEquals(listOf(newer.id, middle.id, older.id), store.list().map { it.id })
    }

    @Test
    fun `find returns a parent record by id`() {
        val root = Files.createTempDirectory("history-parent").toFile()
        val store = FileHistoryStore(root)
        val parent = record("job-parent", createdAt = 10L)
        val child = parent.copy(id = "job-child", createdAt = 20L, parentId = parent.id)

        store.save(parent)
        store.save(child)

        assertEquals(parent, store.find(parent.id))
        assertEquals(parent.id, store.find(child.id)?.parentId)
    }

    @Test
    fun `malformed records are skipped without removing valid records`() {
        val root = Files.createTempDirectory("history-malformed").toFile()
        val store = FileHistoryStore(root)
        val valid = record("job-valid", createdAt = 10L)
        val malformed = File(root, "malformed.json")
        val missingField = File(root, "missing-field.json")

        store.save(valid)
        malformed.writeText("{not valid json")
        missingField.writeText("{\"id\":\"missing-field\"}")

        assertEquals(listOf(valid), store.list())
        assertEquals(valid, store.find(valid.id))
        assertTrue(malformed.exists())
        assertTrue(missingField.exists())
    }

    @Test
    fun `path traversal ids are rejected before any file is written`() {
        val root = Files.createTempDirectory("history-id").toFile()
        val store = FileHistoryStore(root)
        val outside = File(root.parentFile, "escaped.json")

        val error = runCatching {
            store.save(record("../escaped"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(outside.exists())
        assertNull(store.find("../missing"))
    }

    @Test
    fun `duplicate settings creates a child without copying the output uri`() {
        val root = Files.createTempDirectory("history-duplicate").toFile()
        val store = FileHistoryStore(root)
        val parent = record("job-source", createdAt = 10L)

        store.save(parent)
        val child = store.duplicateSettings(parent.id, "job-child")

        assertNotNull(child)
        assertEquals("job-child", child?.id)
        assertEquals(parent.sourceUri, child?.sourceUri)
        assertEquals(parent.settingsJson, child?.settingsJson)
        assertEquals(parent.width, child?.width)
        assertEquals(parent.height, child?.height)
        assertEquals(parent.id, child?.parentId)
        assertNull(child?.outputUri)
        assertEquals(parent, store.find(parent.id))
        assertEquals(listOf("job-child", "job-source"), store.list().map { it.id })
    }

    @Test
    fun `duplicate settings does not replace an existing version`() {
        val root = Files.createTempDirectory("history-collision").toFile()
        val store = FileHistoryStore(root)
        val parent = record("job-parent", createdAt = 10L)
        val existing = record("job-child", createdAt = 20L)

        store.save(parent)
        store.save(existing)

        val duplicate = store.duplicateSettings(parent.id, existing.id)

        assertNull(duplicate)
        assertEquals(parent, store.find(parent.id))
        assertEquals(existing, store.find(existing.id))
    }

    @Test
    fun `duplicate settings leaves an existing reservation untouched`() {
        val root = Files.createTempDirectory("history-reservation").toFile()
        val store = FileHistoryStore(root)
        val parent = record("job-parent", createdAt = 10L)
        val reservation = File(root, "job-child.json")

        store.save(parent)
        assertTrue(reservation.createNewFile())

        val duplicate = store.duplicateSettings(parent.id, reservation.nameWithoutExtension)

        assertNull(duplicate)
        assertTrue(reservation.exists())
        assertEquals(0L, reservation.length())
    }

    @Test
    fun `duplicate settings returns null when the parent is missing`() {
        val root = Files.createTempDirectory("history-missing-parent").toFile()
        val store = FileHistoryStore(root)

        assertNull(store.duplicateSettings("missing", "child"))
    }

    @Test
    fun `fractional numeric fields are malformed`() {
        val root = Files.createTempDirectory("history-fractional").toFile()
        val store = FileHistoryStore(root)
        writeRawRecord(root, "fractional-width", "1.5", "480", "10")
        writeRawRecord(root, "fractional-created", "640", "480", "10.5")

        assertNull(store.find("fractional-width"))
        assertNull(store.find("fractional-created"))
        assertTrue(store.list().isEmpty())
        assertTrue(File(root, "fractional-width.json").exists())
        assertTrue(File(root, "fractional-created.json").exists())
    }

    @Test
    fun `out of range numeric fields are malformed`() {
        val root = Files.createTempDirectory("history-overflow").toFile()
        val store = FileHistoryStore(root)
        writeRawRecord(root, "overflow-width", "2147483648", "480", "10")
        writeRawRecord(root, "overflow-height", "640", "2147483648", "10")
        writeRawRecord(root, "overflow-created", "640", "480", "9223372036854775808")

        assertNull(store.find("overflow-width"))
        assertNull(store.find("overflow-height"))
        assertNull(store.find("overflow-created"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `save leaves no temporary files behind`() {
        val root = Files.createTempDirectory("history-atomic").toFile()
        val store = FileHistoryStore(root)

        store.save(record("job-atomic", createdAt = 10L))

        assertTrue(root.listFiles()?.none { it.name.endsWith(".tmp") } == true)
    }

    private fun writeRawRecord(
        root: File,
        id: String,
        width: String,
        height: String,
        createdAt: String,
    ) {
        File(root, "$id.json").writeText(
            """{"id":"$id","sourceUri":"content://source/$id","settingsJson":"{}","outputUri":null,"width":$width,"height":$height,"createdAt":$createdAt,"parentId":null}""",
        )
    }

    private fun record(
        id: String,
        createdAt: Long,
    ) = HistoryRecord(
        id = id,
        sourceUri = "content://source/$id",
        settingsJson = "{\"scale\":2,\"mode\":\"creative\"}",
        outputUri = "content://output/$id",
        width = 640,
        height = 480,
        createdAt = createdAt,
        parentId = null,
    )
}
