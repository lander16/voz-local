package dev.sebastian.vozlocal.polish

import kotlinx.coroutines.test.runTest
import dev.sebastian.vozlocal.polish.QwenEngine.CleanupMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the audit bug where the language parameter was ignored and
 * legitimate words (este, bueno, vamos, claro, so, well, like) were being deleted.
 *
 * NOTE on expected values: `polish()` always capitalizes the first character and
 * appends a terminal period, so assertions reflect the real production output
 * (e.g. "um uh hello" -> "Hello." rather than the raw "hello.").
 */
class QwenEngineTest {

    private val engine = QwenEngine()

    @Test
    fun polish_stripsUniversalVocalizationsInAutoMode() = runTest {
        assertEquals("Hello.", engine.polish("um uh hello", "auto"))
    }

    @Test
    fun polish_preservesSpanishDemonstrative_este() = runTest {
        assertEquals("Este libro.", engine.polish("este libro", "es"))
    }

    @Test
    fun polish_preservesSpanish_vamos_bueno_claro() = runTest {
        assertEquals("Vamos al cine que bueno claro.", engine.polish("vamos al cine que bueno claro", "es"))
    }

    @Test
    fun polish_preservesEnglish_well_so_like() = runTest {
        assertEquals("Well done so I went like that.", engine.polish("well done so I went like that", "en"))
    }

    @Test
    fun polish_languageParameter_filtersOnlyThatLanguage() = runTest {
        // "eh" is a Spanish-only vocalization (es list). In English mode it must survive,
        // proving the language parameter actually selects the per-language filler list.
        assertEquals("Eh hola amigo.", engine.polish("eh hola amigo", "en"))
    }

    @Test
    fun polish_perLanguage_spanishStripsOnlySpanishVocalizations() = runTest {
        // Spanish mode strips the Spanish vocalization "eh" but leaves "um" alone:
        // "um" is not in the conservative es list, so it survives.
        assertEquals("Um hola.", engine.polish("um eh hola", "es"))
    }

    @Test
    fun polish_caseInsensitive() = runTest {
        assertEquals("Hello.", engine.polish("UM hello", "auto"))
    }

    @Test
    fun polish_collapsesRepeatedWord() = runTest {
        assertEquals("The cat sat.", engine.polish("the the cat sat"))
    }

    @Test
    fun polish_minimalPreservesFillersAndRepeats() = runTest {
        assertEquals("Um um the the code works.", engine.polish("um um the the code works", "en", CleanupMode.MINIMAL))
    }

    @Test
    fun polish_aggressiveRemovesOnlyExactRepeatedTechnicalWords() = runTest {
        assertEquals("Kotlin flow API works.", engine.polish("kotlin kotlin flow flow API API works", "en", CleanupMode.AGGRESSIVE))
    }

    @Test
    fun polish_aggressivePreservesDiscourseWords() = runTest {
        assertEquals("Well so like este bueno claro.", engine.polish("well so like este bueno claro", "en", CleanupMode.AGGRESSIVE))
    }

    @Test
    fun polish_capitalizesAfterSpanishInvertedMarks() = runTest {
        assertEquals("¿Cómo estás? ¡Bien!", engine.polish("¿cómo estás? ¡bien!", "es", CleanupMode.MINIMAL))
    }

    @Test
    fun polish_capitalizesAfterPeriod() = runTest {
        assertEquals("Hello. How are you.", engine.polish("hello. how are you"))
    }

    @Test
    fun polish_handlesEmptyAndBlank() = runTest {
        assertEquals("", engine.polish("", "auto"))
        assertEquals("   ", engine.polish("   ", "auto"))
    }
}
