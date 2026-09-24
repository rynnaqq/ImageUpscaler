package com.rimuru.twobytwo.data.engine

import android.content.Context
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import com.rimuru.twobytwo.domain.engine.ModelHandle
import com.rimuru.twobytwo.domain.engine.ModelProvider
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class ModelRegistry(
    private val manifest: ModelManifest,
    private val cacheDirectory: File,
    private val assetSource: (String) -> InputStream?,
) : ModelProvider, AutoCloseable {

    constructor(context: Context, manifest: ModelManifest) : this(
        manifest = manifest,
        cacheDirectory = File(context.filesDir, "models"),
        assetSource = { path -> context.assets.open(path) },
    )

    private val handles = mutableMapOf<InferenceEngine.ModelKey, ModelHandle>()

    override fun load(key: InferenceEngine.ModelKey): ModelHandle? {
        handles[key]?.let { return it }
        val entry = manifest.entries[key] ?: return null
        val file = materialize(entry) ?: return null
        return FileModelHandle(
            modelPath = file.absolutePath,
            inputScale = InferenceEngine.scaleFor(key),
        ).also { handles[key] = it }
    }

    override fun close() {
        val openHandles = handles.values.toList()
        handles.clear()
        openHandles.forEach { handle ->
            try {
                handle.close()
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }

    private fun materialize(entry: ModelManifest.Entry): File? {
        val file = File(cacheDirectory, entry.fileName)
        return try {
            if (entry.sha256.isBlank()) return null
            cacheDirectory.mkdirs()
            if (file.exists() && sha256(file) == entry.sha256) {
                return file
            }
            if (file.exists()) file.delete()
            val input = assetSource("models/${entry.fileName}") ?: return null
            input.use { source ->
                file.outputStream().use { target -> source.copyTo(target) }
            }
            if (sha256(file) == entry.sha256) {
                file
            } else {
                file.delete()
                null
            }
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (_: Throwable) {
            file.delete()
            null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private class FileModelHandle(
        override val modelPath: String,
        override val inputScale: Int,
    ) : ModelHandle {
        override val backendName: String = "ORT CPU"

        override fun close() = Unit
    }
}
