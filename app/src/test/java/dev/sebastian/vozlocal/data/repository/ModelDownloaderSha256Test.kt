package dev.sebastian.vozlocal.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
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
}
