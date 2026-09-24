package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.cache.RenderCacheStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RenderCacheStoreTest {

    @Test
    fun `put and get - round trips bytes under a sanitized root-local path`() {
        val root = Files.createTempDirectory("render-cache-put-get").toFile()
        val store = RenderCacheStore(root, 32)
        val key = "../preview/../job one"
        val source = byteArrayOf(1, 2, 3, 4)

        val returnedPath = File(store.put(key, source))

        assertArrayEquals(source, store.get(key))
        assertEquals(root.canonicalFile, returnedPath.canonicalFile.parentFile)
        assertFalse(returnedPath.name.contains(".."))
        assertFalse(returnedPath.name.contains('/'))
        assertFalse(returnedPath.name.contains('\\'))
    }

    @Test
    fun `sanitized keys - distinct traversal strings do not collide`() {
        val root = Files.createTempDirectory("render-cache-collision").toFile()
        val store = RenderCacheStore(root, 16)
        val slashKey = "../same"
        val backslashKey = "..\\same"

        store.put(slashKey, byteArrayOf(1))
        store.put(backslashKey, byteArrayOf(2))

        assertArrayEquals(byteArrayOf(1), store.get(slashKey))
        assertArrayEquals(byteArrayOf(2), store.get(backslashKey))
    }

    @Test
    fun `get - updates least-recently-used ordering`() {
        val root = Files.createTempDirectory("render-cache-get-touch").toFile()
        val store = RenderCacheStore(root, 6)

        store.put("old", byteArrayOf(1, 2, 3))
        store.put("new", byteArrayOf(4, 5, 6))
        assertNotNull(store.get("old"))
        store.put("latest", byteArrayOf(7, 8, 9))

        assertNotNull(store.get("old"))
        assertNull(store.get("new"))
        assertNotNull(store.get("latest"))
    }

    @Test
    fun `touch - preserves the touched entry during eviction`() {
        val root = Files.createTempDirectory("render-cache-touch").toFile()
        val store = RenderCacheStore(root, 6)

        store.put("old", byteArrayOf(1, 2, 3))
        store.put("new", byteArrayOf(4, 5, 6))
        store.touch("old")
        store.put("latest", byteArrayOf(7, 8, 9))

        assertNotNull(store.get("old"))
        assertNull(store.get("new"))
        assertNotNull(store.get("latest"))
    }

    @Test
    fun `put - replaces an existing entry without double counting its bytes`() {
        val root = Files.createTempDirectory("render-cache-replace").toFile()
        val store = RenderCacheStore(root, 8)

        store.put("render", byteArrayOf(1, 2, 3, 4, 5, 6))
        store.put("render", byteArrayOf(7, 8))

        assertArrayEquals(byteArrayOf(7, 8), store.get("render"))
        assertEquals(2, store.totalBytes())
    }

    @Test
    fun `put - evicts an entry larger than the byte limit`() {
        val root = Files.createTempDirectory("render-cache-oversized").toFile()
        val store = RenderCacheStore(root, 4)

        store.put("oversized", byteArrayOf(1, 2, 3, 4, 5))

        assertNull(store.get("oversized"))
        assertEquals(0, store.totalBytes())
    }

    @Test
    fun `clear - removes cache entries but preserves files outside the root`() {
        val root = Files.createTempDirectory("render-cache-clear").toFile()
        val outside = File(root.parentFile, "render-cache-outside-${System.nanoTime()}").apply {
            writeBytes(byteArrayOf(9))
        }
        val store = RenderCacheStore(root, 16)
        store.put("first", byteArrayOf(1))
        store.put("second", byteArrayOf(2))

        store.clear()

        assertEquals(0, store.totalBytes())
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertTrue(outside.isFile)
        assertArrayEquals(byteArrayOf(9), outside.readBytes())
        outside.delete()
    }

    @Test
    fun `put and clear - leave no temporary files behind`() {
        val root = Files.createTempDirectory("render-cache-temp").toFile()
        val store = RenderCacheStore(root, 16)

        store.put("entry", byteArrayOf(1, 2, 3))
        store.clear()

        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }
}
