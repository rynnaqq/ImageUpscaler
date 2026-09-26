package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.cache.RenderCacheStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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
    fun `touch - does not regress LRU order across store instances`() {
        val root = Files.createTempDirectory("render-cache-cross-instance-touch").toFile()
        val firstStore = RenderCacheStore(root, 6)
        val secondStore = RenderCacheStore(root, 6)
        val oldPath = File(firstStore.put("old", byteArrayOf(1, 2, 3)))
        firstStore.put("new", byteArrayOf(4, 5, 6))
        assertNotNull(secondStore.get("old"))
        assertTrue(oldPath.setLastModified(System.currentTimeMillis() + 86_400_000L))

        firstStore.touch("new")
        firstStore.put("latest", byteArrayOf(7, 8, 9))

        assertNull(firstStore.get("old"))
        assertNotNull(firstStore.get("new"))
        assertNotNull(firstStore.get("latest"))
    }

    @Test
    fun `clear - waits for another store instance to finish put`() {
        val delegate = Files.createTempDirectory("render-cache-shared-lock").toFile()
        val listingEntered = CountDownLatch(1)
        val releaseListing = CountDownLatch(1)
        val root = BlockingListFile(delegate.path, listingEntered, releaseListing)
        val firstStore = RenderCacheStore(root, 16)
        val secondStore = RenderCacheStore(root, 16)
        replaceCacheRoot(firstStore, root)
        replaceCacheRoot(secondStore, root)
        val putStarted = CountDownLatch(1)
        val clearStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val putFuture = executor.submit<String> {
            putStarted.countDown()
            firstStore.put("entry", byteArrayOf(1, 2, 3))
        }

        try {
            assertTrue(putStarted.await(5, TimeUnit.SECONDS))
            assertTrue(listingEntered.await(5, TimeUnit.SECONDS))
            val clearFuture = executor.submit<Unit> {
                clearStarted.countDown()
                secondStore.clear()
            }
            assertTrue(clearStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) {
                clearFuture.get(250, TimeUnit.MILLISECONDS)
            }
            releaseListing.countDown()
            putFuture.get(5, TimeUnit.SECONDS)
            clearFuture.get(5, TimeUnit.SECONDS)
        } finally {
            releaseListing.countDown()
            executor.shutdownNow()
        }

        assertNull(firstStore.get("entry"))
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
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".render") })
        assertTrue(outside.isFile)
        assertArrayEquals(byteArrayOf(9), outside.readBytes())
        outside.delete()
    }

    @Test
    fun `totalBytes - fails when the configured root is not a directory`() {
        val root = Files.createTempFile("render-cache-invalid-root", ".tmp").toFile()
        val store = RenderCacheStore(root, 16)

        assertThrows(IOException::class.java) {
            store.totalBytes()
        }
    }

    @Test
    fun `put and clear - leave no temporary files behind`() {
        val root = Files.createTempDirectory("render-cache-temp").toFile()
        val store = RenderCacheStore(root, 16)

        store.put("entry", byteArrayOf(1, 2, 3))
        store.clear()

        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    private fun replaceCacheRoot(store: RenderCacheStore, root: File) {
        RenderCacheStore::class.java.getDeclaredField("cacheRoot").apply {
            isAccessible = true
            set(store, root)
        }
    }

    private class BlockingListFile(
        path: String,
        private val listingEntered: CountDownLatch,
        private val releaseListing: CountDownLatch,
    ) : File(path) {
        private val blockListing = AtomicBoolean(true)

        override fun listFiles(): Array<File>? {
            if (blockListing.compareAndSet(true, false)) {
                listingEntered.countDown()
                check(releaseListing.await(5, TimeUnit.SECONDS))
            }
            return super.listFiles()
        }
    }
}
