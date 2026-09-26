package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.engine.ModelManifest
import com.rimuru.twobytwo.domain.engine.InferenceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Hashes the ONNX files that actually ship in `app/src/main/assets/models/`.
 * `ModelRegistryTest` only compares manifest strings, so it cannot catch an
 * asset whose bytes were rewritten after the checksum was recorded (a
 * `core.autocrlf` checkout on Windows, a partial LFS fetch, a re-export).
 */
class ModelAssetHashTest {

    @Test
    fun `bundled onnx assets hash to the manifest sha256`() {
        for (key in listOf(InferenceEngine.ModelKey.CREATIVE_X2, InferenceEngine.ModelKey.CREATIVE_X4)) {
            val entry = ModelManifest.BUNDLED.entries.getValue(key)
            val asset = bundledAsset(entry.fileName)

            assertTrue(
                "ModelManifest.BUNDLED has no real checksum for ${entry.fileName}: '${entry.sha256}'",
                entry.sha256.matches(SHA256_PATTERN),
            )
            assertEquals(
                "${entry.fileName} bytes do not match ModelManifest.BUNDLED " +
                    "(a line-ending filter rewrote the asset?)",
                entry.sha256,
                sha256(asset),
            )
        }
    }

    private fun bundledAsset(fileName: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(
                File(dir, "src/main/assets/models/$fileName"),
                File(dir, "app/src/main/assets/models/$fileName"),
            )) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "bundled asset not found: $fileName (searched upward from ${File("").absolutePath})",
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = stream.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
