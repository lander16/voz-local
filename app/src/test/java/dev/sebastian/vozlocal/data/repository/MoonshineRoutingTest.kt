package dev.sebastian.vozlocal.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.sebastian.vozlocal.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MoonshineRoutingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun languageAndDurationFailBeforeLoadingNativeCode() {
        val repository = DictationRepository(context)
        repository.saveLanguage("es")
        assertNull(repository.liveModelError("moonshine_tiny_es", 480000))
        assertNull(repository.liveModelError("moonshine_tiny_es", 480001))
        repository.saveLanguage("en")
        assertNotNull(repository.liveModelError("moonshine_small_es"))
        repository.saveLanguage("auto")
        assertNotNull(repository.liveModelError("moonshine_tiny_es"))
        assertNull(repository.liveModelError("whisper_small", 480001))
    }

    @Test fun fileWorkflowRejectsMoonshineBeforeOpeningUri() = runBlocking {
        val repository = DictationRepository(context)
        var progressCalled = false
        try {
            repository.transcribeSharedFile(Uri.parse("content://nonexistent"), "moonshine_tiny_es") { _, _ -> progressCalled = true }
            fail("Expected unsupported-workflow rejection")
        } catch (_: IllegalStateException) { }
        assertFalse(progressCalled)
    }

    @Test fun catalogAddsBothModelsWithoutSelectingThem() = runBlocking {
        val repository = DictationRepository(context)
        repository.ensureModelCatalogInitialized()
        val models = AppDatabase.getDatabase(context).modelDao().getModelsList()
        val candidates = models.filter { it.id.startsWith("moonshine_") }
        assertEquals(2, candidates.size)
        assertTrue(candidates.none { it.isSelected })
        assertEquals("whisper_base", models.single { it.isSelected }.id)
    }

    @Test fun missingExperimentalModelCannotBecomeSelected() = runBlocking {
        val repository = DictationRepository(context)
        repository.ensureModelCatalogInitialized()
        repository.selectModel("moonshine_small_es")
        val selected = AppDatabase.getDatabase(context).modelDao().getModelsList().single { it.isSelected }
        assertEquals("whisper_base", selected.id)
    }

    @Test fun calibrationRejectsExperimentalModelBeforeNativeLoad() = runBlocking {
        val repository = DictationRepository(context)
        try {
            repository.withBenchmarkModelLease("moonshine_tiny_es") { fail("Must not access Whisper engine") }
            fail("Expected rejection")
        } catch (_: IllegalStateException) { }
    }
}
