package dev.sebastian.vozlocal.whisper

/**
 * Post-filter that strips hallucinated loop phrases Whisper sometimes emits at
 * the tail of a transcription (YouTube outro-style "gracias por ver", "thanks
 * for watching", "subscribe", "[music]", etc.) and collapses verbatim repeated
 * sentences. Pure JVM — no model, no Android deps — so it is unit-testable.
 */
object HallucinationFilter {
    private val PATTERNS = listOf(
        Regex("(?i)\\bgracias por (ver|mirar|escuchar|vernos|ver el vídeo)\\b"),
        Regex("(?i)\\bsuscr[ií]bete\\b"),
        Regex("(?i)\\bthanks? for (watching|listening)\\b"),
        Regex("(?i)\\blike and subscribe\\b"),
        Regex("(?i)\\[m[uú]sica\\]"),
        Regex("(?i)\\[music\\]"),
        Regex("(?i)\\[aplausos?\\]"),
        Regex("(?i)\\[applause\\]"),
        Regex("(?i)\\bsubtitles? (by|translated)\\b"),
    )

    // Collapses an empty "sentence" left behind after a phrase is stripped,
    // e.g. "Hola. . Adios." -> "Hola. Adios.".
    private val PUNCT_GAP = Regex("([.!?])\\s+\\1")

    fun filter(text: String): String {
        if (text.isBlank()) return text
        var result = text
        for (p in PATTERNS) {
            result = p.replace(result) { match ->
                if (isTailMatch(result, match.range)) "" else match.value
            }
        }
        // Collapse a sentence that's been repeated N>=2 times verbatim
        result = collapseRepetition(result)
        // Clean up the resulting double spaces
        result = result.replace(Regex("\\s+"), " ").trim()
        // Clean up empty-sentence punctuation gaps left by the removals
        while (PUNCT_GAP.containsMatchIn(result)) {
            result = result.replace(PUNCT_GAP, "$1")
        }
        return result
    }

    private fun isTailMatch(text: String, range: IntRange): Boolean {
        val tail = text.substring(range.last + 1).trim()
        return tail.isEmpty() || tail.all { it.isWhitespace() || it in ".!?)]}" }
    }

    private fun collapseRepetition(text: String): String {
        // If a sentence (ending in . ! ?) appears 2+ times consecutively, keep only one.
        // The leading \b prevents a false positive like "Goodbye." vs "Bye." where a
        // substring ("bye.") would otherwise match the backreference case-insensitively.
        val pattern = Regex("\\b([^.!?]+[.!?])\\s+\\1\\s*", RegexOption.IGNORE_CASE)
        var result = text
        while (pattern.containsMatchIn(result)) {
            result = result.replace(pattern, "$1 ")
        }
        return result
    }
}
