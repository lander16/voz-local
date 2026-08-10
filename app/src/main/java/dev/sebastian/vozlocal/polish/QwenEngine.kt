package dev.sebastian.vozlocal.polish

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Local rule-based text polisher. Strips filler words, collapses
 * repeated tokens, and applies light smart-punctuation / capitalization.
 * Pure Kotlin — no model file required. Despite the historical class name,
 * this is rule-based cleanup rather than an LLM/AI backend.
 *
 * The polisher is intentionally conservative: it only removes pure
 * vocalizations (um, uh, hm, …) that are never words in the target
 * language. Discourse markers and common short words are NOT removed
 * because they are ambiguous — removing "este", "bueno", "so" or
 * "well" destroys legitimate text ("este libro" must stay "este libro").
 */
class QwenEngine {
    enum class CleanupMode { MINIMAL, BALANCED, AGGRESSIVE }

    // Only never-legitimate vocalizations per language. Everything else from
    // the original list (este, bueno, vamos, claro, so, well, like, …) was
    // dropped because those are real words in normal prose.
    private val fillersByLang: Map<String, List<String>> = mapOf(
        "es" to listOf("eh", "ehm"),
        "en" to listOf("um", "uh", "er", "uhm", "hmm", "mm"),
        "fr" to listOf("euh", "euhm"),
        "de" to listOf("ähm", "äh", "hm", "hmm"),
        "pt" to listOf("ah", "hm", "hmm"),
        "it" to listOf("ehm", "hm", "hmm"),
        "auto" to listOf("um", "uh", "er", "hm", "hmm", "mm")
    )

    private val fillerRegexCache: MutableMap<String, List<Regex>> = ConcurrentHashMap()
    private val aggressiveFillerRegexCache: MutableMap<String, List<Regex>> = ConcurrentHashMap()
    private val repeatWordRegex = Regex("\\b(\\w+)(?:\\s+\\1\\b)+", RegexOption.IGNORE_CASE)
    private val aggressiveRepeatWordRegex = Regex("\\b([\\p{L}\\p{N}_+#.-]{2,})(?:\\s+\\1\\b)+", RegexOption.IGNORE_CASE)
    private val multiSpaceRegex = Regex("\\s+")
    private val capitalizeAfterSentenceRegex = Regex("([.!?¿¡\\n]\\s*)([a-zñáéíóúàâçèêëîïôùûüäöß])")

    private fun fillerRegexesFor(language: String): List<Regex> {
        val resolved = if (fillersByLang.containsKey(language)) language else "auto"
        return fillerRegexCache.getOrPut(resolved) {
            fillersByLang.getValue(resolved).map { filler ->
                Regex("\\b${Regex.escape(filler)}\\b", RegexOption.IGNORE_CASE)
            }
        }
    }

    private val aggressiveFillersByLang: Map<String, List<String>> = mapOf(
        "es" to listOf("eh", "ehm", "em", "mmm"),
        "en" to listOf("um", "uh", "er", "uhm", "hmm", "mm", "erm"),
        "fr" to listOf("euh", "euhm", "hum"),
        "de" to listOf("ähm", "äh", "hm", "hmm", "ähhh"),
        "pt" to listOf("ah", "hã", "hum", "hm", "hmm"),
        "it" to listOf("ehm", "em", "hm", "hmm"),
        "auto" to listOf("um", "uh", "er", "uhm", "hmm", "mm", "erm")
    )

    private fun aggressiveFillerRegexesFor(language: String): List<Regex> {
        val resolved = if (aggressiveFillersByLang.containsKey(language)) language else "auto"
        return aggressiveFillerRegexCache.getOrPut(resolved) {
            aggressiveFillersByLang.getValue(resolved).map { filler ->
                Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(filler)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
            }
        }
    }

    suspend fun polish(
        text: String,
        language: String = "auto",
        cleanupMode: CleanupMode = CleanupMode.BALANCED
    ): String = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext text
        var result = text

        if (cleanupMode != CleanupMode.MINIMAL) {
            val fillers = if (cleanupMode == CleanupMode.AGGRESSIVE) aggressiveFillerRegexesFor(language) else fillerRegexesFor(language)
            for (re in fillers) {
                result = re.replace(result, " ")
            }

            // Collapse accidental stutters. Balanced preserves the original conservative behavior;
            // aggressive uses Unicode/code-token aware repeats but still only removes exact repeats.
            result = if (cleanupMode == CleanupMode.AGGRESSIVE) {
                aggressiveRepeatWordRegex.replace(result) { it.groupValues[1] }
            } else {
                repeatWordRegex.replace(result) { it.groupValues[1] }
            }
        }

        // 3. Trim repeated whitespace.
        result = multiSpaceRegex.replace(result, " ").trim()

        // 4. Capitalize after sentence end or newline.
        result = capitalizeAfterSentenceRegex.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase(Locale.getDefault())
        }

        // 5. Capitalize first character.
        if (result.isNotEmpty()) {
            result = result.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }

        // 6. Ensure terminal punctuation.
        val last = result.lastOrNull()
        if (last != null && last !in ".!?") result += "."

        result
    }
}
