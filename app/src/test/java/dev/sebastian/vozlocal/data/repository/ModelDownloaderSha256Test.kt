package dev.sebastian.vozlocal.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/**
 * Exercises the SHA-256 integrity-check logic of [ModelDownloader] in isolation.
 *
 * `downloadModel` itself performs a real HTTP fetch against Hugging Face (no
 * MockWebServer dependency is declared), so the verification step was extracted
 * into [ModelDownloader.verifySha256] and is tested directly against pre-seeded
 * model files at the exact path `ModelUrls.getModelFile` would write to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelDownloaderSha256Test {

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun unknownHash_isExplicitlyUnverifiedButAccepted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = ModelUrls.getModelFile(context, "whisper_tiny")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            assertTrue(ModelDownloader(context).verifySha256(file, null))
            // Unknown SHA must NOT delete a file that passed transport/size checks.
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun matchingHash_succeeds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = ModelUrls.getModelFile(context, "whisper_tiny")
        val content = "known model bytes for sha256 test".toByteArray()
        file.writeBytes(content)
        val expected = sha256Hex(content)
        try {
            assertTrue(ModelDownloader(context).verifySha256(file, expected))
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun mismatchedHash_deletesFileAndReturnsFalse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = ModelUrls.getModelFile(context, "whisper_tiny")
        file.writeBytes("known model bytes for sha256 test".toByteArray())
        val wrongHash = sha256Hex("completely different content".toByteArray())
        try {
            assertFalse(ModelDownloader(context).verifySha256(file, wrongHash))
            // Mismatched content must be deleted so a corrupt model is never left on disk.
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun vadModelFile_usesSileroVadValidationIdAndStableName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = ModelDownloader(context).vadModelFile()

        assertEquals("ggml-silero-vad.bin", file.name)
        assertFalse(ModelUrls.isValidDownloadedFile(file, "silero_vad"))
        assertTrue((ModelUrls.minimumValidBytes("silero_vad") ?: 0L) > 0L)
    }

    @Test
    fun allModelsInUrlMapHaveValidSha256InModelDownloader() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val downloader = ModelDownloader(context)
        val shaRegex = Regex("^[a-f0-9]{64}$")

        assertTrue("URL_MAP should not be empty", ModelUrls.URL_MAP.isNotEmpty())
        for ((modelId, _) in ModelUrls.URL_MAP) {
            val hash = downloader.sha256Map[modelId]
            assertTrue("Expected sha256Map to contain modelId '$modelId'", hash != null)
            assertTrue(
                "Model '$modelId' hash '$hash' must be a valid 64-character lowercase hex string",
                shaRegex.matches(hash!!)
            )
            assertEquals("Verified (SHA-256)", downloader.verificationLabel(modelId))
        }

        assertEquals(ModelUrls.URL_MAP.keys, ModelDownloader.sha256Map.keys)
    }

    @Test
    fun sha256Calculation_matchesKnownVector() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val downloader = ModelDownloader(context)
        val testInput = "hello world"
        val expectedSha = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"

        // Test bytes directly
        val actualShaBytes = downloader.sha256(testInput.toByteArray(Charsets.UTF_8))
        assertEquals(expectedSha, actualShaBytes)

        // Test file calculation directly
        val tempFile = java.io.File(context.cacheDir, "test-sha256-vector.txt")
        try {
            tempFile.writeText(testInput, Charsets.UTF_8)
            val actualShaFile = downloader.sha256(tempFile)
            assertEquals(expectedSha, actualShaFile)
        } finally {
            tempFile.delete()
        }
    }
}
