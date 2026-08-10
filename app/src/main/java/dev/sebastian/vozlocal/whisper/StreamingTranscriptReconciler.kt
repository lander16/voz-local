package dev.sebastian.vozlocal.whisper

import java.text.Normalizer
import java.util.Locale

/**
 * Reconciles transcripts from successive overlapping audio windows.
 *
 * Whisper may revise the words at the start/end of an overlapping window. Only
 * text that falls before a confirmed token overlap is made stable; the most
 * recent window remains provisional until the next pass or final transcription.
 */
class StreamingTranscriptReconciler {
    data class State(
        val stableText: String,
        val provisionalText: String,
        val isFinal: Boolean = false,
    ) {
        val text: String = listOf(stableText, provisionalText).filter { it.isNotBlank() }.joinToString(" ")
    }

    private var stableWords = emptyList<String>()
    private var previousWindowWords = emptyList<String>()

    fun accept(windowTranscript: String): State {
        val nextWords = words(windowTranscript)
        if (nextWords.isEmpty()) return state()

        if (previousWindowWords.isNotEmpty()) {
            val overlap = longestSuffixPrefixOverlap(previousWindowWords, nextWords)
            // One coincidental word is not enough evidence to commit a boundary.
            if (overlap >= 2) {
                stableWords += previousWindowWords.dropLast(overlap)
            }
        }
        previousWindowWords = nextWords
        return state()
    }

    /** Final whole-recording pass is authoritative and replaces provisional text. */
    fun finalizeTranscript(finalTranscript: String): State {
        stableWords = words(finalTranscript)
        previousWindowWords = emptyList()
        return State(stableWords.joinToString(" "), "", isFinal = true)
    }

    fun reset() {
        stableWords = emptyList()
        previousWindowWords = emptyList()
    }

    private fun state() = State(
        stableText = stableWords.joinToString(" "),
        provisionalText = previousWindowWords.joinToString(" "),
    )

    private fun longestSuffixPrefixOverlap(previous: List<String>, next: List<String>): Int {
        val max = minOf(previous.size, next.size)
        for (length in max downTo 1) {
            val previousSuffix = previous.takeLast(length).map(::comparisonKey)
            val nextPrefix = next.take(length).map(::comparisonKey)
            if (previousSuffix == nextPrefix) return length
        }
        return 0
    }

    private fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun comparisonKey(word: String): String = Normalizer.normalize(word, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
