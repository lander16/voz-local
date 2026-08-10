package dev.sebastian.vozlocal.benchmark

import java.text.Normalizer
import java.util.Locale

/** Pure, deterministic transcription scoring for on-device benchmark runs. */
object TranscriptionScorer {
    data class ErrorRate(
        val edits: Int,
        val referenceUnits: Int,
    ) {
        /** Error rates may exceed 1.0 when the hypothesis has many insertions. */
        val rate: Double
            get() = when {
                referenceUnits > 0 -> edits.toDouble() / referenceUnits
                edits == 0 -> 0.0
                else -> 1.0
            }
    }

    data class Scores(
        val wordErrorRate: ErrorRate,
        val characterErrorRate: ErrorRate,
    )

    /**
     * Lowercases, canonicalizes Unicode, removes punctuation/symbols, and
     * collapses whitespace. Diacritics are retained so missing Spanish accents
     * still count toward CER while punctuation style does not affect WER/CER.
     */
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)
        .map { character ->
            when (Character.getType(character)) {
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                Character.MATH_SYMBOL.toInt(),
                Character.CURRENCY_SYMBOL.toInt(),
                Character.MODIFIER_SYMBOL.toInt(),
                Character.OTHER_SYMBOL.toInt() -> ' '
                else -> character
            }
        }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun score(reference: String, hypothesis: String): Scores {
        val normalizedReference = normalize(reference)
        val normalizedHypothesis = normalize(hypothesis)
        val referenceWords = tokenizeWords(normalizedReference)
        val hypothesisWords = tokenizeWords(normalizedHypothesis)
        val referenceCharacters = normalizedReference.toList()
        val hypothesisCharacters = normalizedHypothesis.toList()

        return Scores(
            wordErrorRate = ErrorRate(
                edits = levenshteinDistance(referenceWords, hypothesisWords),
                referenceUnits = referenceWords.size,
            ),
            characterErrorRate = ErrorRate(
                edits = levenshteinDistance(referenceCharacters, hypothesisCharacters),
                referenceUnits = referenceCharacters.size,
            ),
        )
    }

    private fun tokenizeWords(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split(' ')

    /** O(min(m,n)) memory, allowing the scorer to handle long shared files. */
    private fun <T> levenshteinDistance(first: List<T>, second: List<T>): Int {
        if (first.isEmpty()) return second.size
        if (second.isEmpty()) return first.size

        val rows = if (first.size <= second.size) first else second
        val columns = if (first.size <= second.size) second else first
        var previous = IntArray(rows.size + 1) { it }
        var current = IntArray(rows.size + 1)

        for (columnIndex in columns.indices) {
            current[0] = columnIndex + 1
            for (rowIndex in rows.indices) {
                val substitutionCost = if (rows[rowIndex] == columns[columnIndex]) 0 else 1
                current[rowIndex + 1] = minOf(
                    current[rowIndex] + 1,
                    previous[rowIndex + 1] + 1,
                    previous[rowIndex] + substitutionCost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[rows.size]
    }
}
