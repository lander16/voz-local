package dev.sebastian.vozlocal.polish

import org.junit.Assert.assertEquals
import org.junit.Test

class SpanishOrthographyHelperTest {

    @Test
    fun fixAccents_restores_mandatory_accents() {
        val input = "Y bueno el cuento de la negrita sigue asi. Despues de haber viajado tanto por todo el pais, regresa al malecon gritando."
        val expected = "Y bueno el cuento de la negrita sigue así. Después de haber viajado tanto por todo el pais, regresa al malecón gritando."
        assertEquals(expected, SpanishOrthographyHelper.fixAccents(input))
    }

    @Test
    fun fixAccents_preserves_casing() {
        assertEquals("Así es como funciona en el Malecón.", SpanishOrthographyHelper.fixAccents("Asi es como funciona en el Malecon."))
        assertEquals("ASÍ Y MALECÓN", SpanishOrthographyHelper.fixAccents("ASI Y MALECON"))
    }

    @Test
    fun fixAccents_handles_cion_and_sion_suffixes() {
        val input = "La cancion de la estacion y la decision de la reunion."
        val expected = "La canción de la estación y la decisión de la reunión."
        assertEquals(expected, SpanishOrthographyHelper.fixAccents(input))
    }

    @Test
    fun fixAccents_restores_interrogative_accents_after_opening_question() {
        assertEquals("¿Qué no?", SpanishOrthographyHelper.fixAccents("¿Que no?"))
        assertEquals("¿Cómo estás?", SpanishOrthographyHelper.fixAccents("¿Como estás?"))
        assertEquals("¿Dónde vas?", SpanishOrthographyHelper.fixAccents("¿Donde vas?"))
    }

    @Test
    fun fixAccents_preserves_already_accented_text() {
        val input = "Y bueno el cuento sigue así en el malecón con limón."
        assertEquals(input, SpanishOrthographyHelper.fixAccents(input))
    }
}
