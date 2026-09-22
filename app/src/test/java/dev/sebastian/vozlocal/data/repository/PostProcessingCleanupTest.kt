package dev.sebastian.vozlocal.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.sebastian.vozlocal.polish.TextPolishEngine.CleanupMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PostProcessingCleanupTest {
    private fun repo(): DictationRepository = DictationRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun minimalDoesWhitespaceCapitalizationPunctuationOnly() = runTest {
        val result = repo().postProcessText(
            text = "  um um hello   world  ",
            smartPunctuation = true,
            autoCapitalize = true,
            applyDict = false,
            useAiPolisher = true,
            cleanupMode = CleanupMode.MINIMAL
        )
        assertEquals("Um um hello world.", result)
    }

    @Test
    fun spanishQuestionCapitalizesAfterInvertedQuestion() = runTest {
        val result = repo().postProcessText("como estas", true, true, false, false)
        assertEquals("¿Como estas?", result)
    }

    @Test
    fun moonshineSmallAddsFinalPeriodToPlainUtterance() = runTest {
        val result = repo().postProcessText(
            "hola mundo", true, true, false, false, modelId = "moonshine_small_es"
        )
        assertEquals("Hola mundo.", result)
    }

    @Test
    fun moonshineTinyPreservesExistingPunctuation() = runTest {
        val r = repo()
        assertEquals("Hola mundo.", r.postProcessText("hola mundo.", true, true, false, false, modelId = "moonshine_tiny_es"))
        assertEquals("¿Como estas?", r.postProcessText("como estas", true, true, false, false, modelId = "moonshine_tiny_es"))
        assertEquals("Hola!", r.postProcessText("hola!", true, true, false, false, modelId = "moonshine_tiny_es"))
    }

    @Test
    fun moonshineSmallRepairsMissingOpeningQuestionMark() = runTest {
        val r = repo()
        assertEquals("¿Como estas?", r.postProcessText("como estas?", true, true, false, false, modelId = "moonshine_small_es"))
        assertEquals("Hola. ¿Como estas?", r.postProcessText("hola. como estas?", true, true, false, false, modelId = "moonshine_small_es"))
        assertEquals("¿Como estas?", r.postProcessText("¿como estas?", true, true, false, false, modelId = "moonshine_small_es"))
    }

    @Test
    fun questionMarkRepairDoesNotChangeWhisperOrDisabledPunctuation() = runTest {
        val r = repo()
        assertEquals("Como estas?", r.postProcessText("como estas?", true, true, false, false, modelId = "whisper_small"))
        assertEquals("Como estas?", r.postProcessText("como estas?", false, true, false, false, modelId = "moonshine_small_es"))
    }

    @Test
    fun moonshineRespectsDisabledSmartPunctuation() = runTest {
        val result = repo().postProcessText(
            "hola mundo", false, true, false, false, modelId = "moonshine_small_es"
        )
        assertEquals("Hola mundo", result)
    }

    @Test
    fun whisperOutputDoesNotGainFinalPeriod() = runTest {
        val result = repo().postProcessText(
            "hola mundo", true, true, false, false, modelId = "whisper_small"
        )
        assertEquals("Hola mundo", result)
    }

    @Test
    fun spokenPunctuationDoesNotReplaceWhenDisabled() = runTest {
        val r = repo()
        r.saveSpokenPunctuationCommands(false)
        val result = r.postProcessText("la palabra coma aparece en medicina", true, true, false, false)
        assertEquals("La palabra coma aparece en medicina", result)
    }

    @Test
    fun spokenPunctuationReplacesIsolatedCommandPhrase() = runTest {
        val r = repo()
        r.saveSpokenPunctuationCommands(true)
        val result = r.postProcessText("hola coma mundo punto", true, true, false, false)
        assertEquals("Hola, mundo.", result)
    }

    @Test
    fun spokenPunctuationReplacesDelimitedCommandPhrase() = runTest {
        val r = repo()
        r.saveSpokenPunctuationCommands(true)
        val result = r.postProcessText("hola, coma, mundo", true, true, false, false)
        assertEquals("Hola, mundo", result)
    }
}
