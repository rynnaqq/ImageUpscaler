package com.rimuru.twobytwo.data.history

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

interface HistoryStore {
    fun save(record: HistoryRecord)
    fun list(): List<HistoryRecord>
    fun find(id: String): HistoryRecord?
    fun duplicateSettings(id: String, newId: String): HistoryRecord?
}

class FileHistoryStore(private val root: File) : HistoryStore {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY_NAME))

    override fun save(record: HistoryRecord) {
        write(record, replaceExisting = true)
    }

    override fun list(): List<HistoryRecord> {
        val files = root.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && it.name.endsWith(FILE_EXTENSION) }
            .mapNotNull { file ->
                val id = file.name.removeSuffix(FILE_EXTENSION)
                if (!isValidId(id)) null else read(file, id)
            }
            .sortedWith(compareByDescending<HistoryRecord> { it.createdAt }.thenBy { it.id })
            .toList()
    }

    override fun find(id: String): HistoryRecord? {
        val validId = id.takeIf(::isValidId) ?: return null
        val file = fileFor(validId)
        return if (file.isFile) read(file, validId) else null
    }

    override fun duplicateSettings(id: String, newId: String): HistoryRecord? {
        val parentId = id.takeIf(::isValidId) ?: return null
        val childId = newId.takeIf(::isValidId) ?: return null
        if (parentId == childId) return null
        val parent = find(parentId) ?: return null
        val child = parent.copy(
            id = childId,
            outputUri = null,
            createdAt = System.currentTimeMillis(),
            parentId = parent.id,
        )
        return try {
            write(child, replaceExisting = false)
            child
        } catch (_: FileAlreadyExistsException) {
            null
        }
    }

    private fun write(record: HistoryRecord, replaceExisting: Boolean) {
        val id = validateId(record.id)
        record.parentId?.let(::validateId)
        ensureRoot()
        val target = fileFor(id)
        if (!replaceExisting && target.exists()) {
            throw FileAlreadyExistsException(target.toString())
        }
        val temp = Files.createTempFile(root.toPath(), ".$id-", ".tmp")
        try {
            Files.write(temp, encode(record).toByteArray(StandardCharsets.UTF_8))
            moveAtomically(temp, target.toPath(), replaceExisting)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun ensureRoot() {
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Unable to create history directory: $root")
        }
        if (!root.isDirectory) {
            throw IOException("History path is not a directory: $root")
        }
    }

    private fun fileFor(id: String): File = File(root, "$id$FILE_EXTENSION")

    private fun read(file: File, expectedId: String): HistoryRecord? = try {
        decode(file.readText(StandardCharsets.UTF_8), expectedId)
    } catch (_: Exception) {
        null
    }

    private fun encode(record: HistoryRecord): String = JSONObject().apply {
        put(KEY_ID, record.id)
        put(KEY_SOURCE_URI, record.sourceUri)
        put(KEY_SETTINGS_JSON, record.settingsJson)
        put(KEY_OUTPUT_URI, record.outputUri ?: JSONObject.NULL)
        put(KEY_WIDTH, record.width)
        put(KEY_HEIGHT, record.height)
        put(KEY_CREATED_AT, record.createdAt)
        put(KEY_PARENT_ID, record.parentId ?: JSONObject.NULL)
    }.toString()

    private fun decode(json: String, expectedId: String): HistoryRecord {
        val obj = JSONObject(json)
        val id = requiredString(obj, KEY_ID)
        if (!isValidId(id) || id != expectedId) {
            throw IOException("Invalid history id")
        }
        val parentId = optionalString(obj, KEY_PARENT_ID)
        if (parentId != null && !isValidId(parentId)) {
            throw IOException("Invalid parent id")
        }
        return HistoryRecord(
            id = id,
            sourceUri = requiredString(obj, KEY_SOURCE_URI),
            settingsJson = requiredString(obj, KEY_SETTINGS_JSON),
            outputUri = optionalString(obj, KEY_OUTPUT_URI),
            width = requiredInt(obj, KEY_WIDTH),
            height = requiredInt(obj, KEY_HEIGHT),
            createdAt = requiredLong(obj, KEY_CREATED_AT),
            parentId = parentId,
        )
    }

    private fun requiredString(obj: JSONObject, key: String): String =
        obj.opt(key) as? String ?: throw IOException("Missing or invalid field: $key")

    private fun requiredInt(obj: JSONObject, key: String): Int {
        val value = requiredIntegralNumber(obj, key)
        if (value < Int.MIN_VALUE.toLong() || value > Int.MAX_VALUE.toLong()) {
            throw IOException("Out-of-range field: $key")
        }
        return value.toInt()
    }

    private fun requiredLong(obj: JSONObject, key: String): Long =
        requiredIntegralNumber(obj, key)

    private fun requiredIntegralNumber(obj: JSONObject, key: String): Long {
        val value = obj.opt(key) as? Number
            ?: throw IOException("Missing or invalid field: $key")
        return when (value) {
            is Byte, is Short, is Int, is Long -> value.toLong()
            is BigInteger -> try {
                value.longValueExact()
            } catch (_: ArithmeticException) {
                throw IOException("Out-of-range field: $key")
            }
            is BigDecimal -> if (value.scale() > 0) {
                throw IOException("Fractional field: $key")
            } else {
                try {
                    value.toBigIntegerExact().longValueExact()
                } catch (_: ArithmeticException) {
                    throw IOException("Out-of-range field: $key")
                }
            }
            else -> throw IOException("Invalid numeric field: $key")
        }
    }

    private fun optionalString(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return requiredString(obj, key)
    }

    private fun validateId(id: String): String {
        require(isValidId(id)) { "Invalid history id" }
        return id
    }

    private fun isValidId(id: String): Boolean =
        id.length in 1..MAX_ID_LENGTH && ID_PATTERN.matches(id) && id != "." && id != ".."

    private fun moveAtomically(source: Path, target: Path, replaceExisting: Boolean) {
        try {
            if (replaceExisting) {
                Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("Atomic history move is not supported for: $target", e)
        } catch (e: UnsupportedOperationException) {
            throw IOException("Atomic history move is not supported for: $target", e)
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "history"
        const val FILE_EXTENSION = ".json"
        const val MAX_ID_LENGTH = 128
        val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        const val KEY_ID = "id"
        const val KEY_SOURCE_URI = "sourceUri"
        const val KEY_SETTINGS_JSON = "settingsJson"
        const val KEY_OUTPUT_URI = "outputUri"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_CREATED_AT = "createdAt"
        const val KEY_PARENT_ID = "parentId"
    }
}
