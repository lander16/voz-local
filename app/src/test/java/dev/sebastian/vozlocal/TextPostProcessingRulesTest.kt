package dev.sebastian.vozlocal

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPostProcessingRulesTest {

    private fun applySmartPunctuation(text: String): String {
        var result = text
        result = result.replace(Regex("\\s+"), " ").trim()

        // Verbalized Punctuation - Spanish
        result = result.replace(Regex("(?i)\\bpunto\\b"), ".")
        result = result.replace(Regex("(?i)\\bcoma\\b"), ",")
        result = result.replace(Regex("(?i)\\bdos puntos\\b"), ":")
        result = result.replace(Regex("(?i)\\bsigno de (interrogacion|interrogación)\\b"), "?")
        result = result.replace(Regex("(?i)\\bsigno de (exclamacion|exclamación)\\b"), "!")

        // Verbalized Punctuation - English
        result = result.replace(Regex("(?i)\\bperiod\\b"), ".")
        result = result.replace(Regex("(?i)\\bcomma\\b"), ",")
        result = result.replace(Regex("(?i)\\bquestion mark\\b"), "?")

        // Clean spaces BEFORE punctuation
        result = result.replace(Regex("\\s+([,.?!:])"), "$1")

        // Ensure single space AFTER punctuation if followed by a letter
        result = result.replace(Regex("([,.?!:])([^\\s\\d,.?!:])"), "$1 $2")

        return result.trim()
    }

    @Test
    fun verbalizedPunctuationReplacement_spanish() {
        val rawText = "hola coma como estas punto"
        val processed = applySmartPunctuation(rawText)
        assertEquals("hola, como estas.", processed)
    }

    @Test
    fun verbalizedPunctuationReplacement_english() {
        val rawText = "hello comma how are you question mark"
        val processed = applySmartPunctuation(rawText)
        assertEquals("hello, how are you?", processed)
    }

    @Test
    fun punctuationSpacingCorrection() {
        val rawText = "hola ,mundo .que tal"
        val processed = applySmartPunctuation(rawText)
        assertEquals("hola, mundo. que tal", processed)
    }
}
