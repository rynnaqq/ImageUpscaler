package com.rimuru.twobytwo

import com.rimuru.twobytwo.staleScratchFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A process kill mid-job orphans the tile spools and the streaming temp PNG, and
 * nothing ever reclaimed them. They then eat the very space
 * MediaStoreImageIo.checkScratchCapacity measures, turning a survivable job into
 * "insufficient temporary storage".
 */
class StaleScratchTest {

    private val now = 10_000_000_000L
    private val twoHours = 7_200_000L
    private val dirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun scratch(vararg names: String): File {
        val dir = Files.createTempDirectory("rimuru2x-scratch").toFile()
        dirs += dir
        names.forEach { File(dir, it).writeText("x") }
        return dir
    }

    private fun age(dir: File, name: String, millis: Long) {
        File(dir, name).setLastModified(now - millis)
    }

    @Test
    fun `finds orphaned tile spools and stream pngs`() {
        val dir = scratch("rimuru2x_tiles_12345.rgba", "rimuru2x_stream_999.png")
        age(dir, "rimuru2x_tiles_12345.rgba", twoHours + 1)
        age(dir, "rimuru2x_stream_999.png", twoHours + 1)

        val found = staleScratchFiles(dir, olderThanMs = twoHours, nowMs = now).map { it.name }.toSet()

        assertEquals(setOf("rimuru2x_tiles_12345.rgba", "rimuru2x_stream_999.png"), found)
    }

    @Test
    fun `leaves a live job's scratch alone`() {
        val dir = scratch("rimuru2x_tiles_live.rgba")
        age(dir, "rimuru2x_tiles_live.rgba", twoHours - 60_000)

        assertTrue(staleScratchFiles(dir, olderThanMs = twoHours, nowMs = now).isEmpty())
    }

    @Test
    fun `ignores unrelated files in the cache directory`() {
        // The prefix must match at the START of the name, not anywhere in it.
        val dir = scratch(
            "renders",
            "thumb.jpg",
            "some_other_temp.tmp",
            "tiles_rimuru2x_scratch.rgba",
            "rimuru2x_scratch_log.txt",
        )

        assertTrue(staleScratchFiles(dir, olderThanMs = twoHours, nowMs = now).isEmpty())
    }

    @Test
    fun `a missing directory yields nothing instead of throwing`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "definitely-not-here-${System.nanoTime()}")

        assertTrue(staleScratchFiles(missing, olderThanMs = twoHours, nowMs = now).isEmpty())
    }

    @Test
    fun `deletes only the stale files and keeps the fresh one`() {
        val dir = scratch("rimuru2x_tiles_old.rgba", "rimuru2x_tiles_new.rgba", "keepme.txt")
        age(dir, "rimuru2x_tiles_old.rgba", twoHours + 1)
        age(dir, "rimuru2x_tiles_new.rgba", 1_000)

        val deleted = staleScratchFiles(dir, olderThanMs = twoHours, nowMs = now, delete = true)

        assertEquals(listOf("rimuru2x_tiles_old.rgba"), deleted.map { it.name })
        assertFalse(File(dir, "rimuru2x_tiles_old.rgba").exists())
        assertTrue(File(dir, "rimuru2x_tiles_new.rgba").exists())
        assertTrue(File(dir, "keepme.txt").exists())
    }

    @Test
    fun `dry run finds the same files without deleting them`() {
        val dir = scratch("rimuru2x_tiles_old.rgba")
        age(dir, "rimuru2x_tiles_old.rgba", twoHours + 1)

        val found = staleScratchFiles(dir, olderThanMs = twoHours, nowMs = now, delete = false)

        assertEquals(1, found.size)
        assertTrue("dry run must not delete", File(dir, "rimuru2x_tiles_old.rgba").exists())
    }

    @Test
    fun `the default age leaves a two hour old file alone`() {
        val dir = scratch("rimuru2x_tiles_recent.rgba")
        age(dir, "rimuru2x_tiles_recent.rgba", 7_200_000L)

        assertTrue(staleScratchFiles(dir, nowMs = now).isEmpty())
    }
}
