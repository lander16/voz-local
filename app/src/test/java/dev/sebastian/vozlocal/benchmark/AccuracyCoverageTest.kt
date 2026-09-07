package dev.sebastian.vozlocal.benchmark

import org.junit.Assert.*
import org.junit.Test

class AccuracyCoverageTest {
    private val manifest = CorpusManifest(samples = listOf(
        CorpusSample("one", "es", 3000, "speech", "Hola"),
        CorpusSample("two", "es", 3000, "speech", "Adiós")
    ))
    @Test fun missingHypothesisFailsRun() {
        assertThrows(IllegalArgumentException::class.java) {
            AccuracyEvaluationRunner.evaluateCorpusPreCleaned(manifest, mapOf("one" to ("Hola" to "Hola")))
        }
    }
    @Test fun emptyCorpusCannotPass() {
        assertThrows(IllegalArgumentException::class.java) {
            AccuracyEvaluationRunner.evaluateCorpusPreCleaned(CorpusManifest(samples = emptyList()), emptyMap())
        }
    }
    @Test fun explicitEmptyTranscriptCountsAsErrors() {
        val report = AccuracyEvaluationRunner.evaluateCorpusPreCleaned(manifest,
            mapOf("one" to ("Hola" to "Hola"), "two" to ("" to "")))
        assertEquals(2, report.totalSamples)
        assertEquals(0.5, report.rawMetrics.aggregateWer, 0.001)
    }
}
