package dev.sebastian.vozlocal.moonshine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MoonshineModelsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun catalogContainsOnlyPinnedSpanishBundles() {
        assertTrue(MoonshineModels.isMoonshine("moonshine_tiny_es"))
        assertTrue(MoonshineModels.isMoonshine("moonshine_small_es"))
        assertFalse(MoonshineModels.isMoonshine("whisper_small"))
        assertEquals(8, MoonshineModels.model("moonshine_tiny_es")!!.assets.size)
        assertEquals(8, MoonshineModels.model("moonshine_small_es")!!.assets.size)
        MoonshineModels.model("moonshine_tiny_es")!!.assets.forEach {
            assertEquals(64, it.sha256.length)
            assertTrue(it.url.startsWith("https://download.moonshine.ai/"))
        }
    }

    @Test fun incompleteOrCorruptDirectoryIsNeverDownloaded() {
        val id = "moonshine_tiny_es"
        val dir = MoonshineModels.directory(context, id)
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "adapter.ort").writeBytes(byteArrayOf(1, 2, 3))
        try {
            assertFalse(MoonshineModels.isDownloaded(context, id))
            assertNull(MoonshineModelDownloader(context).verifiedDirectory(id))
        } finally { dir.deleteRecursively() }
    }

    @Test fun unknownOrMissingModelIsRejectedWithoutCreatingDirectory() = runBlocking {
        val before = MoonshineModels.directory(context, "not_a_model")
        before.deleteRecursively()
        assertFalse(MoonshineModelDownloader(context).delete("not_a_model"))
        assertFalse(before.exists())
        assertNull(MoonshineModelDownloader(context).verifiedDirectory("not_a_model"))
    }
}
