package dev.sebastian.vozlocal.benchmark

import dev.sebastian.vozlocal.polish.TextPolishEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

class AccuracyEvaluationTest {

    @Test
    fun planningManifest_isExplicitlyNotExecutableEvidence() {
        val manifest = AccuracyEvaluationRunner.loadManifestFromResources(
            resourcePath = "benchmarks/corpus_manifest.json",
            classLoader = javaClass.classLoader!!
        )

        assertEquals(CorpusStatus.PLANNING_ONLY, manifest.status)
        val root = createTempDirectory("accuracy-corpus-").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                AccuracyEvaluationRunner.requireExecutableCorpus(manifest, root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun executableCorpus_requiresAndVerifiesPcmProvenance() {
        val root = createTempDirectory("accuracy-corpus-").toFile()
        try {
            val audio = File(root, "es/sample.pcm").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(32_000) { (it % 127).toByte() })
            }
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(audio.readBytes())
                .joinToString("") { "%02x".format(it) }
            val sample = CorpusSample(
                sampleId = "verified",
                language = "es",
                durationMs = 1_000,
                category = "conversational",
                referenceText = "Texto verificado",
                licenseNotes = "Redistribution terms recorded",
                audioPath = "es/sample.pcm",
                sourceUri = "https://example.invalid/source-record",
                permissionEvidence = "consent-record-001",
                normalizationProfile = "pcm_s16le_16000_mono_v1",
                pcmSha256 = hash,
            )
            val manifest = CorpusManifest(status = CorpusStatus.EXECUTABLE, samples = listOf(sample))

            AccuracyEvaluationRunner.requireExecutableCorpus(manifest, root)

            val wrongHash = manifest.copy(samples = listOf(sample.copy(pcmSha256 = "0".repeat(64))))
            val error = assertThrows(IllegalArgumentException::class.java) {
                AccuracyEvaluationRunner.requireExecutableCorpus(wrongHash, root)
            }
            assertTrue(error.message.orEmpty().contains("PCM SHA-256 mismatch"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun executableCorpus_rejectsMissingProvenanceAndEscapingPaths() {
        val root = createTempDirectory("accuracy-corpus-").toFile()
        try {
            val sample = CorpusSample(
                sampleId = "unsafe",
                language = "es",
                durationMs = 1_000,
                category = "conversational",
                referenceText = "Texto",
                audioPath = "../private.pcm",
            )
            val error = assertThrows(IllegalArgumentException::class.java) {
                AccuracyEvaluationRunner.requireExecutableCorpus(
                    CorpusManifest(status = CorpusStatus.EXECUTABLE, samples = listOf(sample)),
                    root,
                )
            }
            assertTrue(error.message.orEmpty().contains("sourceUri or permissionEvidence is required"))
            assertTrue(error.message.orEmpty().contains("audioPath escapes corpus root"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corpusManifest_loadsAndValidatesSuccessfully() {
        val manifest = AccuracyEvaluationRunner.loadManifestFromResources(
            resourcePath = "benchmarks/corpus_manifest.json",
            classLoader = javaClass.classLoader!!
        )

        assertNotNull("Manifest should load", manifest)
        assertTrue("Manifest should have at least 15-20 entries, found: ${manifest.size}", manifest.size >= 20)

        val sampleIds = mutableSetOf<String>()
        val categories = mutableSetOf<String>()
        val languages = mutableSetOf<String>()
        val buckets = mutableSetOf<DurationBucket>()

        val expectedCategories = setOf(
            "immediate_onset",
            "quiet_ending",
            "numbers_and_dates",
            "proper_names",
            "accents_and_diacritics",
            "noise_background",
            "conversational"
        )

        for (sample in manifest.samples) {
            assertTrue("sampleId must be non-blank: ${sample.sampleId}", sample.sampleId.isNotBlank())
            assertTrue("Duplicate sampleId found: ${sample.sampleId}", sampleIds.add(sample.sampleId))
            assertTrue("durationMs must be positive: ${sample.durationMs}", sample.durationMs > 0)
            assertTrue("referenceText must not be blank", sample.referenceText.isNotBlank())
            assertTrue("category must not be blank", sample.category.isNotBlank())
            assertTrue("category '${sample.category}' must be in expected set", expectedCategories.contains(sample.category))
            assertTrue("language must not be blank", sample.language.isNotBlank())

            categories.add(sample.category)
            languages.add(sample.language)
            buckets.add(sample.durationBucket)
        }

        // Verify span of duration buckets
        assertTrue("Manifest should include SHORT clips", buckets.contains(DurationBucket.SHORT))
        assertTrue("Manifest should include MEDIUM clips", buckets.contains(DurationBucket.MEDIUM))
        assertTrue("Manifest should include LONG clips", buckets.contains(DurationBucket.LONG))
        assertTrue("Manifest should include EXTENDED clips", buckets.contains(DurationBucket.EXTENDED))

        // Verify categories
        assertEquals("All expected categories should be present", expectedCategories, categories)

        // Verify language coverage
        assertTrue("Manifest must include Spanish ('es')", languages.contains("es"))
        assertTrue("Manifest must include English ('en') regression set", languages.contains("en"))

        // Verify filtering helpers
        val spanishClips = manifest.filterByLanguage("es")
        val englishClips = manifest.filterByLanguage("en")
        assertTrue("Spanish clips should form majority", spanishClips.size >= 15)
        assertTrue("English clips should have regression set", englishClips.size >= 3)
        assertEquals(manifest.size, spanishClips.size + englishClips.size)
    }

    @Test
    fun accuracyEvaluationPipeline_scoresSpanishAccentsAndDiacriticsCorrectly() {
        val sample = CorpusSample(
            sampleId = "test_accents_01",
            language = "es",
            durationMs = 7500,
            category = "accents_and_diacritics",
            referenceText = "La canción sonaba cerca del malecón.",
            sourceNotes = "Test acute accents"
        )

        // Exact match
        val perfectResult = AccuracyEvaluationRunner.evaluateSample(
            sample = sample,
            rawHypothesis = "la canción sonaba cerca del malecón",
            cleanedHypothesis = "La canción sonaba cerca del malecón."
        )
        assertEquals(0.0, perfectResult.rawWer, 0.0)
        assertEquals(0.0, perfectResult.rawCer, 0.0)
        assertEquals(0.0, perfectResult.cleanedWer, 0.0)
        assertEquals(0.0, perfectResult.cleanedCer, 0.0)

        // Missing accents in hypothesis: "cancion" and "malecon"
        val missingAccentsResult = AccuracyEvaluationRunner.evaluateSample(
            sample = sample,
            rawHypothesis = "la cancion sonaba cerca del malecon",
            cleanedHypothesis = "la cancion sonaba cerca del malecon"
        )
        // 2 word errors ("cancion" != "canción", "malecon" != "malecón") out of 6 words
        assertEquals(2, missingAccentsResult.rawScores.wordErrorRate.edits)
        assertEquals(6, missingAccentsResult.rawScores.wordErrorRate.referenceUnits)
        assertEquals(2.0 / 6.0, missingAccentsResult.rawWer, 0.001)

        // 2 character errors (o vs ó)
        assertEquals(2, missingAccentsResult.rawScores.characterErrorRate.edits)
        assertTrue(missingAccentsResult.rawCer > 0.0)
    }

    @Test
    fun accuracyEvaluationPipeline_scoresNumbersAndDatesCorrectly() {
        val sample = CorpusSample(
            sampleId = "test_num_01",
            language = "es",
            durationMs = 9000,
            category = "numbers_and_dates",
            referenceText = "El vuelo 402 saldrá el 15 de marzo a las 18:45.",
        )

        // Punctuation like ':' is stripped by normalizer, but digits 402, 15, 18, 45 are preserved
        val matchedHypothesis = "el vuelo 402 saldrá el 15 de marzo a las 18 45"
        val result = AccuracyEvaluationRunner.evaluateSample(
            sample = sample,
            rawHypothesis = matchedHypothesis,
            cleanedHypothesis = matchedHypothesis
        )
        assertEquals(0.0, result.rawWer, 0.0)
        assertEquals(0.0, result.rawCer, 0.0)

        // Number mistake (e.g. 502 instead of 402)
        val mismatchHypothesis = "el vuelo 502 saldrá el 15 de marzo a las 18 45"
        val mismatchResult = AccuracyEvaluationRunner.evaluateSample(
            sample = sample,
            rawHypothesis = mismatchHypothesis,
            cleanedHypothesis = mismatchHypothesis
        )
        assertEquals(1, mismatchResult.rawScores.wordErrorRate.edits)
        assertTrue(mismatchResult.rawWer > 0.0)
    }

    @Test
    fun rawWerAndCleanedWer_areComputedIndependently() = runBlocking {
        val sample = CorpusSample(
            sampleId = "test_independence_01",
            language = "es",
            durationMs = 5000,
            category = "conversational",
            referenceText = "Buenas tardes a todos.",
        )

        // Raw hypothesis has filler "eh" and duplicate stutter "buenas buenas"
        // Reference words: "buenas", "tardes", "a", "todos" (4 words)
        // Raw: "eh buenas buenas tardes a todos" -> 6 words (edits = 2: insertion "eh" + repeated "buenas")
        val rawHypothesis = "eh buenas buenas tardes a todos"
        val polishEngine = TextPolishEngine()

        val result = AccuracyEvaluationRunner.evaluateSampleWithPolisher(
            sample = sample,
            rawHypothesis = rawHypothesis,
            polishEngine = polishEngine,
            cleanupMode = TextPolishEngine.CleanupMode.BALANCED
        )

        // Raw hypothesis should show errors from "eh" and "buenas buenas"
        assertTrue("Raw hypothesis should contain vocalization 'eh'", result.rawHypothesis.contains("eh"))
        assertEquals(2, result.rawScores.wordErrorRate.edits)
        assertEquals(4, result.rawScores.wordErrorRate.referenceUnits)
        assertEquals(0.5, result.rawWer, 0.001)

        // Cleaned hypothesis should strip "eh" and collapse stuttered "buenas buenas" -> "Buenas tardes a todos."
        assertTrue("Cleaned hypothesis must not contain 'eh'", !result.cleanedHypothesis.startsWith("eh"))
        assertEquals(0, result.cleanedScores.wordErrorRate.edits)
        assertEquals(4, result.cleanedScores.wordErrorRate.referenceUnits)
        assertEquals(0.0, result.cleanedWer, 0.0)

        // Verify delta
        assertEquals(-0.5, result.werDelta, 0.001)

        // Verify independent evaluation with explicit strings
        val explicitResult = AccuracyEvaluationRunner.evaluateSample(
            sample = sample,
            rawHypothesis = "palabra errónea completamente",
            cleanedHypothesis = "Buenas tardes a todos."
        )
        assertEquals(4, explicitResult.rawScores.wordErrorRate.edits)
        assertEquals(4, explicitResult.rawScores.wordErrorRate.referenceUnits)
        assertEquals(1.0, explicitResult.rawWer, 0.0)
        assertEquals(0, explicitResult.cleanedScores.wordErrorRate.edits)
        assertEquals(0.0, explicitResult.cleanedWer, 0.0)
    }

    @Test
    fun aggregateMetrics_computesCorrectMeanMedianAndBreakdowns() {
        val sample1 = CorpusSample("s1", "es", 2000, "conversational", "uno dos tres cuatro")
        val sample2 = CorpusSample("s2", "es", 8000, "conversational", "cinco seis siete ocho")
        val sample3 = CorpusSample("s3", "en", 3000, "numbers_and_dates", "nine ten eleven twelve")

        // sample1: 0 edits (WER = 0.0)
        // sample2: 2 edits out of 4 (WER = 0.5)
        // sample3: 4 edits out of 4 (WER = 1.0)
        val res1 = AccuracyEvaluationRunner.evaluateSample(sample1, "uno dos tres cuatro", "uno dos tres cuatro")
        val res2 = AccuracyEvaluationRunner.evaluateSample(sample2, "cinco diez veinte ocho", "cinco seis siete ocho")
        val res3 = AccuracyEvaluationRunner.evaluateSample(sample3, "wrong wrong wrong wrong", "wrong wrong wrong wrong")

        val report = AccuracyEvaluationRunner.buildReport(listOf(res1, res2, res3))

        assertEquals(3, report.totalSamples)

        // Raw WERs: [0.0, 0.5, 1.0] -> mean = 0.5, median = 0.5
        assertEquals(0.5, report.rawMetrics.meanWer, 0.001)
        assertEquals(0.5, report.rawMetrics.medianWer, 0.001)
        assertEquals(6, report.rawMetrics.totalWordEdits) // 0 + 2 + 4
        assertEquals(12, report.rawMetrics.totalWordUnits) // 4 + 4 + 4
        assertEquals(0.5, report.rawMetrics.aggregateWer, 0.001)

        // Cleaned WERs: res1=0.0, res2=0.0, res3=1.0 -> [0.0, 0.0, 1.0] -> mean = 1/3, median = 0.0
        assertEquals(1.0 / 3.0, report.cleanedMetrics.meanWer, 0.001)
        assertEquals(0.0, report.cleanedMetrics.medianWer, 0.001)

        // Category breakdown
        val convBreakdown = report.categoryBreakdown["conversational"]
        assertNotNull(convBreakdown)
        assertEquals(2, convBreakdown!!.sampleCount)
        assertEquals(0.25, convBreakdown.rawMetrics.meanWer, 0.001) // (0.0 + 0.5)/2
        assertEquals(0.0, convBreakdown.cleanedMetrics.meanWer, 0.001) // (0.0 + 0.0)/2

        val numBreakdown = report.categoryBreakdown["numbers_and_dates"]
        assertNotNull(numBreakdown)
        assertEquals(1, numBreakdown!!.sampleCount)
        assertEquals(1.0, numBreakdown.rawMetrics.meanWer, 0.001)

        // Language breakdown
        val esBreakdown = report.languageBreakdown["es"]
        val enBreakdown = report.languageBreakdown["en"]
        assertNotNull(esBreakdown)
        assertNotNull(enBreakdown)
        assertEquals(2, esBreakdown!!.sampleCount)
        assertEquals(1, enBreakdown!!.sampleCount)
    }

    @Test
    fun aggregateMetrics_evenLengthMedianCalculation() {
        // Test median with even count: [0.1, 0.3, 0.5, 0.9] -> median = (0.3 + 0.5) / 2 = 0.4
        val scores = listOf(
            TranscriptionScorer.score("a b c d e f g h i j", "a b c d e f g h i x"), // 1/10 = 0.1
            TranscriptionScorer.score("a b c d e f g h i j", "a b c d e f g x y z"), // 3/10 = 0.3
            TranscriptionScorer.score("a b c d e f g h i j", "a b c d e x y z w v"), // 5/10 = 0.5
            TranscriptionScorer.score("a b c d e f g h i j", "a x y z w v u t s r")  // 9/10 = 0.9
        )
        val metrics = AggregateMetrics.fromScores(scores)
        assertEquals(4, metrics.sampleCount)
        assertEquals(0.45, metrics.meanWer, 0.001) // (0.1+0.3+0.5+0.9)/4 = 1.8/4 = 0.45
        assertEquals(0.4, metrics.medianWer, 0.001) // (0.3+0.5)/2 = 0.4
    }
}
