package dev.sebastian.vozlocal.polish

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Local rule-based text polisher. Strips filler words, collapses
 * repeated tokens, and applies light smart-punctuation / capitalization.
 * Pure Kotlin — no model file required. Designed as the v1 of the
 * "AI Polisher" feature while a future llama.cpp + Qwen 2.5 inference
 * path is still in the roadmap.
 */
class QwenEngine {
    private val fillersByLang: Map<String, List<String>> = mapOf(
        "es" to listOf("\\beh\\b", "\\beste\\b", "\\bpues\\b", "\\bo sea\\b", "\\bvale\\b", "\\bbueno\\b", "\\bvamos\\b", "\\bclaro\\b", "\\bmira\\b"),
        "en" to listOf("\\bum\\b", "\\buh\\b", "\\ber\\b", "\\buhm\\b", "\\blike\\b", "\\byou know\\b", "\\bi mean\\b", "\\bbasically\\b", "\\bliterally\\b", "\\bkinda\\b", "\\bsort of\\b", "\\bso\\b", "\\bwell\\b"),
        "fr" to listOf("\\beuh\\b", "\\bhein\\b", "\\btu vois\\b", "\\ben fait\\b", "\\bdu coup\\b"),
        "de" to listOf("\\bähm\\b", "\\bäh\\b", "\\balso\\b", "\\bquasi\\b", "\\bhalt\\b"),
        "pt" to listOf("\\bné\\b", "\\btipo\\b", "\\bah\\b", "\\bentão\\b"),
        "it" to listOf("\\behm\\b", "\\bcioè\\b", "\\ballora\\b", "\\bdiciamo\\b"),
        "auto" to listOf("\\bum\\b", "\\buh\\b", "\\beh\\b")
    )

    private val fillerRegexes: List<Regex> = fillersByLang.values.flatten().distinct().map { Regex(it, RegexOption.IGNORE_CASE) }
    private val repeatWordRegex = Regex("\\b(\\w+)(?:\\s+\\1\\b)+", RegexOption.IGNORE_CASE)
    private val multiSpaceRegex = Regex("\\s+")

    suspend fun polish(text: String, language: String = "auto"): String = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext text
        var result = text

        // 1. Remove filler words.
        for (re in fillerRegexes) {
            result = re.replace(result, " ")
        }

        // 2. Collapse "the the" -> "the".
        result = repeatWordRegex.replace(result) { it.groupValues[1] }

        // 3. Trim repeated whitespace.
        result = multiSpaceRegex.replace(result, " ").trim()

        // 4. Capitalize after sentence end or newline.
        result = result.replace(Regex("([.!?¿¡\\n]\\s+)([a-zñáéíóúàâçèêëîïôùûüäöß])")) { match ->
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
