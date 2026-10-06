package dev.sebastian.vozlocal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sebastian.vozlocal.data.repository.DictationRepository
import dev.sebastian.vozlocal.fastconformer.FastConformerModels
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Integration smoke for the app's verified repository path; test-package data only. */
@RunWith(AndroidJUnit4::class)
class FastConformerRepositoryValidationTest {
    @Test
    fun verifiedExperimentalModelUsesRepositoryRouteWithoutChangingSelection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "dev.sebastian.vozlocal.validation") {
            "Integration smoke must run in the isolated validation package"
        }
        val staged = requireNotNull(context.getExternalFilesDir("validation/fastconformer"))
        val modelDirectory = FastConformerModels.directory(context)
        installFixtureBundleIfAbsent(staged, modelDirectory)
        val pcm = File(staged, "speech.f32")
        require(FastConformerModels.sha256(pcm) == FIXTURE_PCM_SHA256) { "Validation PCM SHA-256 mismatch" }
        val pcmBytes = pcm.readBytes()
        require(pcmBytes.size % 4 == 0 && pcmBytes.size in 4..(10 * 16000 * 4))
        val samples = FloatArray(pcmBytes.size / 4)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
        require(samples.all { it.isFinite() && it in -1f..1f })

        val repository = DictationRepository(context)
        val previousLanguage = repository.getLanguage()
        try {
            repository.saveLanguage("es")
            repository.ensureModelCatalogInitialized()
            val selectedBefore = repository.allModels.first().firstOrNull { it.isSelected }?.id
            assertTrue(repository.allModels.first().any { it.id == FastConformerModels.ID })
            // Selection is not changed: exercise the explicit model ID without promoting it.
            assertTrue(FastConformerModels.verify(modelDirectory, FastConformerModels.spec))
            val result = repository.transcribeAudio(samples, FastConformerModels.ID, trace = null)
            assertTrue("Experimental FastConformer returned no text", result.isNotBlank())
            // Second decode verifies repository-level resident reuse without changing selection.
            val warmResult = repository.transcribeAudio(samples, FastConformerModels.ID, trace = null)
            assertTrue("Resident FastConformer returned no text", warmResult.isNotBlank())
            assertTrue(repository.allModels.first().firstOrNull { it.isSelected }?.id == selectedBefore)
        } finally {
            repository.shutdown()
            repository.saveLanguage(previousLanguage)
        }
    }

    private fun installFixtureBundleIfAbsent(source: File, target: File) {
        if (FastConformerModels.verify(target, FastConformerModels.spec)) return
        require(!target.exists()) { "Refusing to replace an existing unverified app model directory in validation" }
        val parent = requireNotNull(target.parentFile)
        require(parent.exists() || parent.mkdirs())
        val stage = File(parent, ".${target.name}.integration-test.part")
        require(!stage.exists()) { "Stale validation staging directory requires inspection" }
        try {
            require(stage.mkdirs())
            FastConformerModels.spec.assets.forEach { asset ->
                File(source, asset.name).copyTo(File(stage, asset.name), overwrite = false)
            }
            File(stage, ".verified").writeText(FastConformerModels.marker(FastConformerModels.spec))
            require(FastConformerModels.verify(stage, FastConformerModels.spec)) {
                "Staged FastConformer fixture failed verification"
            }
            require(stage.renameTo(target)) { "Could not promote verified validation bundle" }
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    private companion object {
        const val FIXTURE_PCM_SHA256 = "23b8db3cdc813839c01bc4d7f7920a780de5f078a91a1fd67184c8acea81f25a"
    }
}
