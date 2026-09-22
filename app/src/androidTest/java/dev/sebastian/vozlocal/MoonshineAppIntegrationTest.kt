package dev.sebastian.vozlocal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sebastian.vozlocal.data.repository.ModelUrls
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit opt-in integration check; never runs against the user's production data. */
@RunWith(AndroidJUnit4::class)
class MoonshineAppIntegrationTest {
    @Test fun tinyDownloadSelectCancelRecoverAndSwitch() = validateModel("moonshine_tiny_es")
    @Test fun smallDownloadSelectCancelRecoverAndSwitch() = validateModel("moonshine_small_es")

    private fun validateModel(id: String) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".validation"))
        val repository = (context.applicationContext as VozLocalApp).repository
        repository.ensureModelCatalogInitialized()
        repository.saveLanguage("es")
        val fixtures = File(context.filesDir, "validation/moonshine")
        val bytes = File(fixtures, "speech.f32").readBytes()
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(floats.remaining()).also { floats.get(it) }
        require(samples.size in 16000..160000)
        val previousSelected = repository.allModels.first().find { it.isSelected }?.id
        repository.startModelDownload(id, this)
        withTimeout(180000) { repository.allModels.first { models -> models.any { it.id == id && it.isDownloaded && !it.isDownloading } } }
        assertEquals("Download must not select an experimental model", previousSelected,
            repository.allModels.first().find { it.isSelected }?.id)
        try {
            repository.selectModel(id)
            assertEquals(id, repository.allModels.first().single { it.isSelected }.id)
            assertTrue(repository.transcribeAudio(samples, id).isNotBlank())
            val longClip = FloatArray(30 * 16000) { samples[it % samples.size] }
            val decoding = launch(Dispatchers.Default) { repository.transcribeAudio(longClip, id) }
            delay(200)
            decoding.cancelAndJoin()
            // Native abort is unavailable: wait for its safe drain before retrying.
            withTimeout(30000) { while (repository.liveModelError(id) != null) delay(25) }
            assertTrue(repository.transcribeAudio(samples, id).isNotBlank())
            repository.shutdown()
            assertTrue("Engine must be reusable after drain", repository.transcribeAudio(samples, id).isNotBlank())

            val whisperId = "whisper_small"
            File(fixtures, "whisper-small-q8.bin").copyTo(ModelUrls.getModelFile(context, whisperId), overwrite = true)
            val whisper = repository.allModels.first().single { it.id == whisperId }
            repository.updateModel(whisper.copy(isDownloaded = true))
            repository.selectModel(whisperId)
            assertTrue(repository.transcribeAudio(samples, whisperId).isNotBlank())
            repository.selectModel(id)
            assertTrue(repository.transcribeAudio(samples, id).isNotBlank())
            assertTrue(repository.deleteDownloadedModel(id))
            assertEquals(whisperId, repository.allModels.first().single { it.isSelected }.id)
        } finally {
            repository.shutdown()
        }
    }
}
