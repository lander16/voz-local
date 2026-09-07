package dev.sebastian.vozlocal.benchmark

import dev.sebastian.vozlocal.polish.TextPolishEngine
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Duration categorization buckets for evaluation corpus clips. */
enum class DurationBucket {
    SHORT,
    MEDIUM,
    LONG,
    EXTENDED;

    companion object {
        fun fromDurationMs(ms: Long): DurationBucket = when {
            ms < 5_000 -> SHORT
            ms < 25_000 -> MEDIUM
            ms < 60_000 -> LONG
            else -> EXTENDED
        }
    }
}

/** Represents a single curated evaluation clip in the accuracy corpus. */
data class CorpusSample(
    val sampleId: String,
    val language: String,
    val durationMs: Long,
    val category: String,
    val referenceText: String,
    val licenseNotes: String = "",
    val sourceNotes: String = "",
) {
    init {
        require(sampleId.isNotBlank()) { "sampleId must not be blank" }
        require(durationMs > 0) { "durationMs must be positive: $durationMs" }
        require(category.isNotBlank()) { "category must not be blank" }
        require(language.isNotBlank()) { "language must not be blank" }
        require(referenceText.isNotBlank()) { "referenceText must not be blank" }
    }

    val durationBucket: DurationBucket
        get() = DurationBucket.fromDurationMs(durationMs)
}

/** Curated collection of accuracy corpus samples with versioning and query helpers. */
data class CorpusManifest(
    val version: Int = 1,
    val description: String = "",
    val samples: List<CorpusSample>,
) {
    val size: Int get() = samples.size

    fun findById(sampleId: String): CorpusSample? =
        samples.firstOrNull { it.sampleId == sampleId }

    fun filterByLanguage(language: String): List<CorpusSample> =
        samples.filter { it.language.equals(language, ignoreCase = true) }

    fun filterByCategory(category: String): List<CorpusSample> =
        samples.filter { it.category.equals(category, ignoreCase = true) }

    fun filterByBucket(bucket: DurationBucket): List<CorpusSample> =
        samples.filter { it.durationBucket == bucket }
}

/** Detailed evaluation scores and hypothesis comparison for a single corpus sample. */
data class SampleEvaluationResult(
    val sampleId: String,
    val language: String,
    val category: String,
    val durationMs: Long,
    val reference: String,
    val rawHypothesis: String,
    val cleanedHypothesis: String,
    val rawScores: TranscriptionScorer.Scores,
    val cleanedScores: TranscriptionScorer.Scores,
) {
    val rawWer: Double get() = rawScores.wordErrorRate.rate
    val rawCer: Double get() = rawScores.characterErrorRate.rate
    val cleanedWer: Double get() = cleanedScores.wordErrorRate.rate
    val cleanedCer: Double get() = cleanedScores.characterErrorRate.rate

    /** Error rate change (negative indicates cleanup reduced errors). */
    val werDelta: Double get() = cleanedWer - rawWer
    val cerDelta: Double get() = cleanedCer - rawCer
}

/** Statistical summary of error rates and unit totals over a set of samples. */
data class AggregateMetrics(
    val sampleCount: Int,
    val meanWer: Double,
    val medianWer: Double,
    val meanCer: Double,
    val medianCer: Double,
    val totalWordEdits: Int,
    val totalWordUnits: Int,
    val totalCharEdits: Int,
    val totalCharUnits: Int,
    val aggregateWer: Double,
    val aggregateCer: Double,
) {
    companion object {
        fun fromScores(scores: List<TranscriptionScorer.Scores>): AggregateMetrics {
            if (scores.isEmpty()) {
                return AggregateMetrics(
                    sampleCount = 0,
                    meanWer = 0.0,
                    medianWer = 0.0,
                    meanCer = 0.0,
                    medianCer = 0.0,
                    totalWordEdits = 0,
                    totalWordUnits = 0,
                    totalCharEdits = 0,
                    totalCharUnits = 0,
                    aggregateWer = 0.0,
                    aggregateCer = 0.0,
                )
            }

            val wers = scores.map { it.wordErrorRate.rate }
            val cers = scores.map { it.characterErrorRate.rate }
            val totalWordEdits = scores.sumOf { it.wordErrorRate.edits }
            val totalWordUnits = scores.sumOf { it.wordErrorRate.referenceUnits }
            val totalCharEdits = scores.sumOf { it.characterErrorRate.edits }
            val totalCharUnits = scores.sumOf { it.characterErrorRate.referenceUnits }

            return AggregateMetrics(
                sampleCount = scores.size,
                meanWer = calculateMean(wers),
                medianWer = calculateMedian(wers),
                meanCer = calculateMean(cers),
                medianCer = calculateMedian(cers),
                totalWordEdits = totalWordEdits,
                totalWordUnits = totalWordUnits,
                totalCharEdits = totalCharEdits,
                totalCharUnits = totalCharUnits,
                aggregateWer = if (totalWordUnits > 0) totalWordEdits.toDouble() / totalWordUnits else 0.0,
                aggregateCer = if (totalCharUnits > 0) totalCharEdits.toDouble() / totalCharUnits else 0.0,
            )
        }

        private fun calculateMean(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            return values.average()
        }

        private fun calculateMedian(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[middle]
            } else {
                (sorted[middle - 1] + sorted[middle]) / 2.0
            }
        }
    }
}

/** Error breakdown for a specific category. */
data class CategoryBreakdown(
    val category: String,
    val sampleCount: Int,
    val rawMetrics: AggregateMetrics,
    val cleanedMetrics: AggregateMetrics,
)

/** Error breakdown for a specific language. */
data class LanguageBreakdown(
    val language: String,
    val sampleCount: Int,
    val rawMetrics: AggregateMetrics,
    val cleanedMetrics: AggregateMetrics,
)

/** Full summary report comparing raw ASR hypothesis vs post-cleanup hypothesis. */
data class AccuracyEvaluationReport(
    val totalSamples: Int,
    val rawMetrics: AggregateMetrics,
    val cleanedMetrics: AggregateMetrics,
    val sampleResults: List<SampleEvaluationResult>,
    val categoryBreakdown: Map<String, CategoryBreakdown> = emptyMap(),
    val languageBreakdown: Map<String, LanguageBreakdown> = emptyMap(),
)

/**
 * Runner and evaluator for transcription accuracy corpora.
 *
 * Provides deterministic parsing of [CorpusManifest], side-by-side evaluation of raw
 * versus post-cleanup hypotheses, Spanish diacritics and numeric retention via
 * [TranscriptionScorer], and aggregate metric summaries (median WER, mean WER, CER,
 * and category/language breakdowns).
 */
object AccuracyEvaluationRunner {

    /** Parses a manifest from a JSON string without external JSON library dependencies. */
    fun parseManifest(jsonContent: String): CorpusManifest {
        val parsed = MiniJsonParser(jsonContent).parse()
        val samplesList = mutableListOf<CorpusSample>()
        var version = 1
        var description = ""

        if (parsed is Map<*, *>) {
            version = (parsed["version"] as? Number)?.toInt() ?: 1
            description = parsed["description"]?.toString() ?: ""
            val rawSamples = (parsed["samples"] as? List<*>)
                ?: (parsed["entries"] as? List<*>)
                ?: emptyList<Any?>()
            for (item in rawSamples) {
                if (item is Map<*, *>) {
                    samplesList.add(mapToSample(item))
                }
            }
        } else if (parsed is List<*>) {
            for (item in parsed) {
                if (item is Map<*, *>) {
                    samplesList.add(mapToSample(item))
                }
            }
        } else {
            throw IllegalArgumentException("Invalid manifest JSON structure: expected object or array")
        }

        return CorpusManifest(
            version = version,
            description = description,
            samples = samplesList,
        )
    }

    /** Loads and parses manifest from an [InputStream]. */
    fun loadManifest(inputStream: InputStream): CorpusManifest {
        val text = inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return parseManifest(text)
    }

    /** Loads manifest from a file. */
    fun loadManifestFromFile(file: File): CorpusManifest =
        file.inputStream().use { loadManifest(it) }

    /** Loads manifest from a classpath resource. */
    fun loadManifestFromResources(
        resourcePath: String = "benchmarks/corpus_manifest.json",
        classLoader: ClassLoader = AccuracyEvaluationRunner::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader(),
    ): CorpusManifest {
        val stream = classLoader.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Manifest resource not found at '$resourcePath'")
        return stream.use { loadManifest(it) }
    }

    private fun mapToSample(map: Map<*, *>): CorpusSample {
        val sampleId = map["sampleId"]?.toString()
            ?: throw IllegalArgumentException("Missing 'sampleId' in sample")
        val language = map["language"]?.toString() ?: "es"
        val durationMs = (map["durationMs"] as? Number)?.toLong() ?: 0L
        val category = map["category"]?.toString() ?: "conversational"
        val referenceText = map["referenceText"]?.toString()
            ?: throw IllegalArgumentException("Missing 'referenceText' for sample $sampleId")
        val licenseNotes = map["licenseNotes"]?.toString()
            ?: map["license"]?.toString()
            ?: ""
        val sourceNotes = map["sourceNotes"]?.toString()
            ?: map["notes"]?.toString()
            ?: ""

        return CorpusSample(
            sampleId = sampleId,
            language = language,
            durationMs = durationMs,
            category = category,
            referenceText = referenceText,
            licenseNotes = licenseNotes,
            sourceNotes = sourceNotes,
        )
    }

    /** Evaluates a single sample when both raw and cleaned hypotheses are already available. */
    fun evaluateSample(
        sample: CorpusSample,
        rawHypothesis: String,
        cleanedHypothesis: String,
    ): SampleEvaluationResult {
        val rawScores = TranscriptionScorer.score(sample.referenceText, rawHypothesis)
        val cleanedScores = TranscriptionScorer.score(sample.referenceText, cleanedHypothesis)
        return SampleEvaluationResult(
            sampleId = sample.sampleId,
            language = sample.language,
            category = sample.category,
            durationMs = sample.durationMs,
            reference = sample.referenceText,
            rawHypothesis = rawHypothesis,
            cleanedHypothesis = cleanedHypothesis,
            rawScores = rawScores,
            cleanedScores = cleanedScores,
        )
    }

    /**
     * Evaluates a raw hypothesis by running it through [TextPolishEngine.polishText]
     * with the specified [cleanupMode] (defaulting to [TextPolishEngine.CleanupMode.BALANCED]),
     * then scoring both hypotheses side by side.
     */
    suspend fun evaluateSampleWithPolisher(
        sample: CorpusSample,
        rawHypothesis: String,
        polishEngine: TextPolishEngine = TextPolishEngine(),
        cleanupMode: TextPolishEngine.CleanupMode = TextPolishEngine.CleanupMode.BALANCED,
    ): SampleEvaluationResult {
        val cleanedHypothesis = polishEngine.polishText(
            text = rawHypothesis,
            language = sample.language,
            cleanupMode = cleanupMode,
        )
        return evaluateSample(sample, rawHypothesis, cleanedHypothesis)
    }

    /** Evaluates a corpus given a map of sampleId -> rawHypothesis. */
    suspend fun evaluateCorpus(
        manifest: CorpusManifest,
        rawHypotheses: Map<String, String>,
        polishEngine: TextPolishEngine = TextPolishEngine(),
        cleanupMode: TextPolishEngine.CleanupMode = TextPolishEngine.CleanupMode.BALANCED,
    ): AccuracyEvaluationReport {
        val results = manifest.samples.mapNotNull { sample ->
            val rawHypothesis = rawHypotheses[sample.sampleId] ?: return@mapNotNull null
            evaluateSampleWithPolisher(sample, rawHypothesis, polishEngine, cleanupMode)
        }
        return buildReport(results)
    }

    /** Evaluates a corpus given pre-cleaned hypotheses (sampleId -> Pair(raw, cleaned)). */
    fun evaluateCorpusPreCleaned(
        manifest: CorpusManifest,
        hypotheses: Map<String, Pair<String, String>>,
    ): AccuracyEvaluationReport {
        val results = manifest.samples.mapNotNull { sample ->
            val pair = hypotheses[sample.sampleId] ?: return@mapNotNull null
            evaluateSample(sample, pair.first, pair.second)
        }
        return buildReport(results)
    }

    /** Aggregates sample results into a comprehensive report with breakdowns. */
    fun buildReport(sampleResults: List<SampleEvaluationResult>): AccuracyEvaluationReport {
        val rawMetrics = AggregateMetrics.fromScores(sampleResults.map { it.rawScores })
        val cleanedMetrics = AggregateMetrics.fromScores(sampleResults.map { it.cleanedScores })

        val categoryBreakdown = sampleResults
            .groupBy { it.category }
            .mapValues { (cat, results) ->
                CategoryBreakdown(
                    category = cat,
                    sampleCount = results.size,
                    rawMetrics = AggregateMetrics.fromScores(results.map { it.rawScores }),
                    cleanedMetrics = AggregateMetrics.fromScores(results.map { it.cleanedScores }),
                )
            }

        val languageBreakdown = sampleResults
            .groupBy { it.language }
            .mapValues { (lang, results) ->
                LanguageBreakdown(
                    language = lang,
                    sampleCount = results.size,
                    rawMetrics = AggregateMetrics.fromScores(results.map { it.rawScores }),
                    cleanedMetrics = AggregateMetrics.fromScores(results.map { it.cleanedScores }),
                )
            }

        return AccuracyEvaluationReport(
            totalSamples = sampleResults.size,
            rawMetrics = rawMetrics,
            cleanedMetrics = cleanedMetrics,
            sampleResults = sampleResults,
            categoryBreakdown = categoryBreakdown,
            languageBreakdown = languageBreakdown,
        )
    }

    /** Lightweight pure-Kotlin JSON parser for manifests without external dependencies. */
    private class MiniJsonParser(private val src: String) {
        private var pos = 0

        fun parse(): Any? {
            skipWhitespace()
            val result = parseValue()
            skipWhitespace()
            return result
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) {
                pos++
            }
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= src.length) return null
            return when (val c = src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> if (c == '-' || c.isDigit()) parseNumber() else error("Unexpected char '$c' at position $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            match('{')
            val map = mutableMapOf<String, Any?>()
            skipWhitespace()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (pos < src.length) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                match(':')
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                    skipWhitespace()
                    if (pos < src.length && src[pos] == '}') {
                        pos++
                        break
                    }
                } else if (pos < src.length && src[pos] == '}') {
                    pos++
                    break
                } else {
                    error("Expected ',' or '}' at position $pos")
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            match('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (pos < src.length) {
                val value = parseValue()
                list.add(value)
                skipWhitespace()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                    skipWhitespace()
                    if (pos < src.length && src[pos] == ']') {
                        pos++
                        break
                    }
                } else if (pos < src.length && src[pos] == ']') {
                    pos++
                    break
                } else {
                    error("Expected ',' or ']' at position $pos")
                }
            }
            return list
        }

        private fun parseString(): String {
            match('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') {
                    return sb.toString()
                } else if (c == '\\') {
                    if (pos >= src.length) error("Unterminated escape sequence in string")
                    when (val esc = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > src.length) error("Invalid unicode escape")
                            val hex = src.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            error("Unterminated string literal")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            var isFloating = false
            if (pos < src.length && src[pos] == '.') {
                isFloating = true
                pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloating = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            val numStr = src.substring(start, pos)
            return if (isFloating) numStr.toDouble() else numStr.toLong()
        }

        private fun parseBoolean(): Boolean {
            if (src.startsWith("true", pos)) {
                pos += 4
                return true
            } else if (src.startsWith("false", pos)) {
                pos += 5
                return false
            }
            error("Expected boolean at position $pos")
        }

        private fun parseNull(): Any? {
            if (src.startsWith("null", pos)) {
                pos += 4
                return null
            }
            error("Expected null at position $pos")
        }

        private fun match(expected: Char) {
            skipWhitespace()
            if (pos < src.length && src[pos] == expected) {
                pos++
            } else {
                error("Expected '$expected' at position $pos, found '${src.getOrNull(pos)}'")
            }
        }
    }
}
