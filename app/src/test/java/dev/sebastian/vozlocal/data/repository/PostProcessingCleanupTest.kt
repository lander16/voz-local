package dev.sebastian.vozlocal.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.sebastian.vozlocal.polish.QwenEngine.CleanupMode
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
    fun spokenPunctuationDoesNotReplaceInsideNormalProse() = runTest {
        val r = repo()
        r.saveSpokenPunctuationCommands(true)
        val result = r.postProcessText("la palabra coma aparece en medicina", true, true, false, false)
        assertEquals("La palabra coma aparece en medicina", result)
    }

    @Test
    fun spokenPunctuationReplacesIsolatedCommandPhrase() = runTest {
        val r = repo()
        r.saveSpokenPunctuationCommands(true)
        val result = r.postProcessText("hola coma mundo punto", true, true, false, false)
        assertEquals("Hola coma mundo punto", result)
    }

    @Test
    fun spokenPunctuationReplacesDelimitedCommandPhrase() = runTest {
        val r = repo()
        r.saveSpokenPunctuationCommands(true)
        val result = r.postProcessText("hola, coma, mundo", true, true, false, false)
        assertEquals("Hola, mundo", result)
    }
}
