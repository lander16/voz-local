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
    private val MULTI_SPACE = Regex("\\s+")
    private val REPEATED_SENTENCE = Regex("\\b([^.!?]+[.!?])\\s+\\1\\s*", RegexOption.IGNORE_CASE)
    private val REPEATED_WORD = Regex("(?i)\\b(\\p{L}+)(?:\\s+\\1\\b){2,}")

    fun filter(text: String): String {
        if (text.isBlank()) return text
        var result = text
        for (p in PATTERNS) {
            result = p.replace(result) { match ->
                if (isTailMatch(result, match.range)) "" else match.value
            }
        }
        // Collapse sentences, multi-word phrases, and words repeated verbatim in loops
        result = collapseRepetition(result)
        // Clean up the resulting double spaces
        result = result.replace(MULTI_SPACE, " ").trim()
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
        // 1. If a sentence (ending in . ! ?) appears 2+ times consecutively, keep only one.
        var result = text
        while (REPEATED_SENTENCE.containsMatchIn(result)) {
            result = result.replace(REPEATED_SENTENCE, "$1 ")
        }
        // 2. Collapse pathological single word repetition loops (e.g. "no no no no no..." -> "no")
        result = result.replace(REPEATED_WORD, "$1")
        // 3. Collapse multi-word phrase repetition loops (e.g. "ustedes como ven ustedes como ven..." -> "ustedes como ven")
        result = collapseRepeatedPhrases(result)
        return result
    }

    private fun collapseRepeatedPhrases(text: String): String {
        var current = text
        var previous = ""
        while (current != previous) {
            previous = current
            current = collapseRepeatedPhrasesPass(current)
        }
        return current
    }

    private fun collapseRepeatedPhrasesPass(text: String): String {
        val tokens = text.split(MULTI_SPACE).filter { it.isNotEmpty() }
        if (tokens.size < 4) return text

        val resultTokens = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            var matchedLen = 0
            var matchedReps = 0
            val maxPhraseLen = minOf(10, (tokens.size - i) / 2)
            for (len in 2..maxPhraseLen) {
                val phrase = tokens.subList(i, i + len)
                var reps = 1
                while (i + (reps + 1) * len <= tokens.size) {
                    val nextChunk = tokens.subList(i + reps * len, i + (reps + 1) * len)
                    val isMatch = (0 until len).all { idx ->
                        cleanTokenForComparison(phrase[idx]).equals(cleanTokenForComparison(nextChunk[idx]), ignoreCase = true)
                    }
                    if (isMatch) {
                        reps++
                    } else {
                        break
                    }
                }
                if (reps >= 2) {
                    matchedLen = len
                    matchedReps = reps
                    break
                }
            }

            if (matchedLen > 0 && matchedReps >= 2) {
                resultTokens.addAll(tokens.subList(i, i + matchedLen))
                i += matchedLen * matchedReps
            } else {
                resultTokens.add(tokens[i])
                i++
            }
        }
        return resultTokens.joinToString(" ")
    }

    private fun cleanTokenForComparison(token: String): String {
        return token.trim().trim('.', ',', '!', '?', ';', ':', '"', '¿', '¡')
    }
}
