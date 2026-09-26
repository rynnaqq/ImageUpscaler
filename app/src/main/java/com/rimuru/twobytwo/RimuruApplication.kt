package com.rimuru.twobytwo

import android.app.Application
import com.facebook.spectrum.SpectrumSoLoader
import java.io.File

/**
 * Scratch files older than this are assumed to belong to a job that was killed
 * (process death mid-encode). A live job keeps writing to its own spool, so an
 * age margin is what stops the sweep from pulling the rug out mid-run.
 */
internal const val STALE_SCRATCH_AGE_MS = 2L * 60L * 60L * 1000L

private val SCRATCH_PREFIXES = listOf("rimuru2x_tiles_", "rimuru2x_stream_")

/**
 * Finds (and by default deletes) scratch files orphaned by a killed enhancement
 * job: the per-row-group tile spools and the streaming temp PNG. Nothing else in
 * the cache directory is touched.
 *
 * Orphaned scratch matters because [com.rimuru.twobytwo.data.media.MediaStoreImageIo]
 * preflights free space in the same directory — the garbage eats the budget and
 * turns a survivable job into "insufficient temporary storage".
 */
internal fun staleScratchFiles(
    directory: File,
    olderThanMs: Long = STALE_SCRATCH_AGE_MS,
    nowMs: Long = System.currentTimeMillis(),
    delete: Boolean = true,
): List<File> {
    val children = directory.listFiles() ?: return emptyList()
    val cutoff = nowMs - olderThanMs
    val stale = children.filter { file ->
        // lastModified() == 0 means the filesystem did not report a timestamp, so
        // the age is unknown; keep it rather than risk deleting a live job's spool.
        file.isFile &&
            SCRATCH_PREFIXES.any { file.name.startsWith(it) } &&
            file.lastModified() in 1 until cutoff
    }
    if (delete) stale.forEach { it.delete() }
    return stale
}

class RimuruApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SpectrumSoLoader.init(this)
        staleScratchFiles(cacheDir)
    }
}
