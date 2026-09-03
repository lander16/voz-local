package dev.sebastian.vozlocal.polish

import java.util.Locale

/**
 * Intelligent paragraph formatter for speech-to-text outputs.
 *
 * Whisper natively outputs long transcriptions as a single monolithic block of
 * space-separated text. This helper analyzes sentence boundaries, word counts,
 * questions, and conversational discourse markers (e.g. "Pero bueno", "En fin",
 * "Por otra parte", "Además", "Sin embargo") to naturally segment long transcripts
 * into clean, highly readable paragraphs separated by double newlines (`\n\n`).
 */
object SmartParagraphFormatter {

    private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.?!])\\s+(?=[A-ZÁÉÍÓÚÑa-záéíóúñ¿¡\"'])")

    // Conversational topic shift markers in Spanish and English that signal natural paragraph beginnings
    private val TRANSITION_MARKERS = listOf(
        "pero bueno",
        "en fin",
        "por otra parte",
        "por otro lado",
        "por cierto",
        "además",
        "ademas",
        "y bueno",
        "de hecho",
        "en resumen",
        "al final",
        "sin embargo",
        "no obstante",
        "a ver,",
        "o sea,",
        "bueno,",
        "anyway",
        "in conclusion",
        "however",
        "furthermore",
        "on the other hand",
        "by the way",
        "after all",
        "so basically"
    )

    fun format(text: String): String {
        if (text.isBlank()) return text

        // If the text already has multiple explicit line breaks (e.g., from spoken punctuation commands),
        // format each block independently.
        val existingBlocks = text.split("\n\n")
        val formattedBlocks = existingBlocks.map { block ->
            formatSingleBlock(block.trim())
        }

        return formattedBlocks.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun formatSingleBlock(block: String): String {
        val words = block.split(Regex("\\s+"))
        // If the block is short (< 35 words), keep it as a single paragraph.
        if (words.size < 35) return block

        // Split into sentences while keeping punctuation attached
        val rawSentences = block.split(SENTENCE_SPLIT_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
        if (rawSentences.size <= 1) return block

        val paragraphs = mutableListOf<String>()
        val currentSentences = mutableListOf<String>()
        var currentWordCount = 0

        for (sentence in rawSentences) {
            val sentenceWordCount = sentence.split(Regex("\\s+")).size
            val lowerSentence = sentence.lowercase(Locale.getDefault())

            val hasTransition = TRANSITION_MARKERS.any { lowerSentence.startsWith(it) }
            val isQuestion = sentence.startsWith("¿") || sentence.endsWith("?")

            val shouldBreak = currentSentences.isNotEmpty() && (
                // Natural transition or question when paragraph already has sufficient substance
                (currentWordCount >= 30 && (hasTransition || isQuestion)) ||
                // Hard paragraph limit to avoid visual exhaustion (~50 words)
                (currentWordCount >= 50)
            )

            if (shouldBreak) {
                paragraphs.add(currentSentences.joinToString(" "))
                currentSentences.clear()
                currentWordCount = 0
            }

            currentSentences.add(sentence)
            currentWordCount += sentenceWordCount
        }

        if (currentSentences.isNotEmpty()) {
            paragraphs.add(currentSentences.joinToString(" "))
        }

        return paragraphs.joinToString("\n\n")
    }
}
