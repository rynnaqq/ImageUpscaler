package com.rimuru.twobytwo.data.cache

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

data class CacheEntry(
    val path: String,
    val bytes: Long,
    val lastAccess: Long,
)

class RenderCacheStore(
    private val root: File,
    private val maxBytes: Long,
) {
    private val cacheRoot = root.canonicalFile
    private val lockKey = cacheRoot.canonicalPath
    private val lockFile = File(cacheRoot, LOCK_FILE_NAME)

    init {
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
    }

    fun put(key: String, source: ByteArray): String = withRootLock {
        val target = fileFor(key)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) && !isCacheFile(target)) {
            throw IOException("Unsafe cache path: $target")
        }
        writeAtomically(target, source)
        touchFile(target)
        evict()
        target.absolutePath
    }

    fun get(key: String): ByteArray? = withRootLock {
        val file = fileFor(key)
        if (!isCacheFile(file)) return@withRootLock null
        val source = file.readBytes()
        touchFile(file)
        source
    }

    fun touch(key: String): Unit = withRootLock {
        val file = fileFor(key)
        if (isCacheFile(file)) touchFile(file)
    }

    fun clear(): Unit = withRootLock {
        listRootFiles().forEach { file ->
            if (isCacheFile(file) || isTemporaryFile(file)) {
                Files.deleteIfExists(file.toPath())
            }
        }
    }

    fun totalBytes(): Long = withRootLock {
        cacheEntries().sumOf(CacheEntry::bytes)
    }

    private fun <T> withRootLock(block: () -> T): T {
        val monitor = monitorFor(lockKey)
        return synchronized(monitor) {
            ensureRoot()
            val channel = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            try {
                val fileLock = channel.lock()
                try {
                    block()
                } finally {
                    fileLock.release()
                }
            } finally {
                channel.close()
            }
        }
    }

    private fun writeAtomically(target: File, source: ByteArray) {
        val temp = Files.createTempFile(cacheRoot.toPath(), TEMP_PREFIX, TEMP_SUFFIX)
        var failure: Throwable? = null
        try {
            Files.write(temp, source)
            moveAtomically(temp, target.toPath())
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                Files.deleteIfExists(temp)
            } catch (cleanupError: Throwable) {
                if (failure == null) throw cleanupError
                failure.addSuppressed(cleanupError)
            }
        }
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("Atomic cache move is not supported for: $target", e)
        } catch (e: UnsupportedOperationException) {
            throw IOException("Atomic cache move is not supported for: $target", e)
        }
    }

    private fun touchFile(file: File) {
        val persistedLastAccess = cacheEntries().maxOfOrNull(CacheEntry::lastAccess) ?: 0L
        val nextAccess = maxOf(System.currentTimeMillis(), persistedLastAccess + 1)
        if (!file.setLastModified(nextAccess)) {
            throw IOException("Unable to update cache access time: $file")
        }
    }

    private fun evict() {
        val entries = cacheEntries().sortedWith(
            compareBy<CacheEntry> { it.lastAccess }.thenBy { it.path },
        )
        var total = entries.sumOf(CacheEntry::bytes)
        for (entry in entries) {
            if (total <= maxBytes) break
            Files.deleteIfExists(File(entry.path).toPath())
            total -= entry.bytes
        }
        check(total <= maxBytes) { "Unable to reduce render cache to $maxBytes bytes" }
    }

    private fun listRootFiles(): Array<File> = cacheRoot.listFiles()
        ?: throw IOException("Unable to list render cache directory: $cacheRoot")

    private fun cacheEntries(): List<CacheEntry> = listRootFiles()
        .asSequence()
        .filter(::isCacheFile)
        .map { file ->
            CacheEntry(
                path = file.absolutePath,
                bytes = file.length(),
                lastAccess = file.lastModified(),
            )
        }
        .toList()

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8))
        val fileName = buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
        return File(cacheRoot, "$fileName$CACHE_EXTENSION")
    }

    private fun isCacheFile(file: File): Boolean =
        CACHE_FILE_PATTERN.matches(file.name) &&
            !Files.isSymbolicLink(file.toPath()) &&
            file.isFile &&
            isDirectChild(file)

    private fun isTemporaryFile(file: File): Boolean =
        file.name.startsWith(TEMP_PREFIX) &&
            file.name.endsWith(TEMP_SUFFIX) &&
            !Files.isSymbolicLink(file.toPath()) &&
            file.isFile &&
            isDirectChild(file)

    private fun isDirectChild(file: File): Boolean = try {
        file.canonicalFile.parentFile == cacheRoot
    } catch (_: IOException) {
        false
    }

    private fun ensureRoot() {
        if (!cacheRoot.isDirectory && !cacheRoot.mkdirs() && !cacheRoot.isDirectory) {
            throw IOException("Unable to create render cache directory: $cacheRoot")
        }
    }

    private companion object {
        private val monitorMap = mutableMapOf<String, Any>()
        private val monitorMapLock = Any()

        private fun monitorFor(key: String): Any = synchronized(monitorMapLock) {
            monitorMap.getOrPut(key) { Any() }
        }

        const val CACHE_EXTENSION = ".render"
        const val LOCK_FILE_NAME = ".render.lock"
        const val TEMP_PREFIX = ".render-"
        const val TEMP_SUFFIX = ".tmp"
        val CACHE_FILE_PATTERN = Regex("[0-9a-f]{64}${Regex.escape(CACHE_EXTENSION)}")
        val HEX = "0123456789abcdef".toCharArray()
    }
}
