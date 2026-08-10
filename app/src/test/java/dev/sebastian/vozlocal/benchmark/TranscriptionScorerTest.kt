package dev.sebastian.vozlocal.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionScorerTest {
    @Test
    fun punctuation_case_and_whitespace_doNotAffectScores() {
        val scores = TranscriptionScorer.score(
            reference = "¡Hola, mundo!  ¿Cómo estás?",
            hypothesis = "hola mundo cómo estás",
        )

        assertEquals(0.0, scores.wordErrorRate.rate, 0.0)
        assertEquals(0.0, scores.characterErrorRate.rate, 0.0)
    }

    @Test
    fun substitutions_insertions_andDeletionsProduceExpectedWer() {
        val scores = TranscriptionScorer.score(
            reference = "uno dos tres cuatro",
            hypothesis = "uno diez cuatro extra",
        )

        assertEquals(3, scores.wordErrorRate.edits)
        assertEquals(4, scores.wordErrorRate.referenceUnits)
        assertEquals(0.75, scores.wordErrorRate.rate, 0.0)
    }

    @Test
    fun cerRetainsSpanishDiacritics() {
        val scores = TranscriptionScorer.score("sí", "si")

        assertEquals(1.0, scores.wordErrorRate.rate, 0.0)
        assertEquals(1, scores.characterErrorRate.edits)
        assertEquals(0.5, scores.characterErrorRate.rate, 0.0)
    }

    @Test
    fun emptyReferenceHasDefinedRate() {
        assertEquals(0.0, TranscriptionScorer.score("", "").wordErrorRate.rate, 0.0)
        assertEquals(1.0, TranscriptionScorer.score("", "hallucination").wordErrorRate.rate, 0.0)
    }

    @Test
    fun benchmarkResultCalculatesRealTimeMetrics() {
        val config = TranscriptionBenchmarkConfig(
            modelId = "whisper_small",
            quantization = "q8_0",
            threadCount = 4,
            language = "es",
            beamSize = 0,
            temperatureIncrement = 0.2f,
            vadEnabled = true,
            audioSource = "VOICE_RECOGNITION",
        )
        val result = TranscriptionBenchmarkResult(
            sampleId = "es-mx-001",
            config = config,
            audioDurationMs = 10_000,
            modelLoadMs = 300,
            inferenceMs = 2_500,
            reference = "hola",
            hypothesis = "hola",
        )

        assertEquals(0.25, result.realTimeFactor!!, 0.0)
        assertEquals(4.0, result.realTimeSpeed!!, 0.0)
        assertEquals(0.0, result.scores.wordErrorRate.rate, 0.0)

        val zeroInference = result.copy(inferenceMs = 0)
        assertEquals(0.0, zeroInference.realTimeFactor!!, 0.0)
        assertNull(zeroInference.realTimeSpeed)
    }
}
