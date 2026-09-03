package dev.sebastian.vozlocal.whisper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the hallucination post-filter: Whisper sometimes emits
 * looped outro phrases ("gracias por ver", "subscribe", "[music]", …) that must
 * be stripped, and verbatim repeated sentences that must be collapsed.
 */
class HallucinationFilterTest {

    @Test
    fun filter_strips_gracias_por_ver() {
        assertEquals("Hola mundo.", HallucinationFilter.filter("Hola mundo. Gracias por ver."))
    }

    @Test
    fun filter_strips_suscribete() {
        assertEquals("Hola.", HallucinationFilter.filter("Hola. Suscríbete."))
    }

    @Test
    fun filter_strips_thanks_for_watching() {
        assertEquals("Welcome back.", HallucinationFilter.filter("Welcome back. Thanks for watching."))
    }

    @Test
    fun filter_strips_music_brackets() {
        assertEquals("Hello world.", HallucinationFilter.filter("Hello world. [Music]"))
    }

    @Test
    fun filter_preserves_non_tail_phrases() {
        assertEquals("Welcome back. Thanks for watching. Bye.", HallucinationFilter.filter("Welcome back. Thanks for watching. Bye."))
    }

    @Test
    fun filter_collapses_repeated_sentence() {
        assertEquals("Goodbye. Bye.", HallucinationFilter.filter("Goodbye. Goodbye. Goodbye. Bye."))
    }

    @Test
    fun filter_handles_empty_and_blank() {
        assertEquals("", HallucinationFilter.filter(""))
        assertEquals("   ", HallucinationFilter.filter("   "))
    }

    @Test
    fun filter_preserves_normal_text() {
        // "gracias" alone is fine; only the "gracias por ver" pattern is stripped.
        assertEquals(
            "Hola, ¿cómo estás? Estoy bien, gracias.",
            HallucinationFilter.filter("Hola, ¿cómo estás? Estoy bien, gracias.")
        )
    }

    @Test
    fun filter_is_case_insensitive() {
        assertEquals("", HallucinationFilter.filter("GRACIAS POR VER"))
    }

    @Test
    fun filter_collapses_repeated_word_loop() {
        val input = "El cuento de la negro regresa para seguir en el no no no no no no no no no no"
        val expected = "El cuento de la negro regresa para seguir en el no"
        assertEquals(expected, HallucinationFilter.filter(input))
    }

    @Test
    fun filter_collapses_repeated_phrase_loop_without_punctuation() {
        val input = "Ustedes como ven ustedes como ven ustedes como ven ustedes como ven ustedes como ven"
        val expected = "Ustedes como ven"
        assertEquals(expected, HallucinationFilter.filter(input))
    }

    @Test
    fun filter_preserves_legitimate_double_words() {
        assertEquals("muy muy bien", HallucinationFilter.filter("muy muy bien"))
    }
}
