package dev.sebastian.vozlocal.fastconformervalidation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FastConformerModelBundleTest {
    @Test
    fun missingArtifactsFailClosed() {
        val directory = Files.createTempDirectory("fc-missing").toFile()
        try {
            assertFalse(FastConformerModelBundle.verify(directory, listOf(artifact("model", byteArrayOf(1)))) )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun verifiedBytesPassAndCorruptedBytesFail() {
        val directory = Files.createTempDirectory("fc-hash").toFile()
        try {
            val bytes = "pinned test bytes".toByteArray()
            val artifact = artifact("tokens.txt", bytes)
            File(directory, artifact.name).writeBytes(bytes)
            assertTrue(FastConformerModelBundle.verify(directory, listOf(artifact)))
            val sameLengthCorruption = bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            File(directory, artifact.name).writeBytes(sameLengthCorruption)
            assertFalse(FastConformerModelBundle.verify(directory, listOf(artifact)))
            File(directory, artifact.name).writeBytes(bytes)
            File(directory, artifact.name).appendText("tampered")
            assertFalse(FastConformerModelBundle.verify(directory, listOf(artifact)))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun selectedModelAndLicenseArePinned() {
        assertTrue(FastConformerModelBundle.MODEL_ID.endsWith("_pc"))
        assertTrue(FastConformerModelBundle.LICENSE == "CC-BY-4.0")
        assertTrue(FastConformerModelBundle.artifacts.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
    }

    private fun artifact(name: String, bytes: ByteArray) = ModelArtifact(
        name = name,
        bytes = bytes.size.toLong(),
        sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) },
        url = "https://example.invalid/$name",
    )
}
