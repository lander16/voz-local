package dev.sebastian.vozlocal.benchmark

import com.whispercpp.whisper.WhisperNativeTimings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranscriptionBenchmarkTest {

    private fun createValidConfig(
        modelId: String = "whisper_small",
        quantization: String = "q8_0",
        threadCount: Int = 4,
        language: String = "es",
        beamSize: Int = 0,
        temperatureIncrement: Float = 0.2f,
        vadEnabled: Boolean = true,
        audioSource: String = "VOICE_RECOGNITION",
        repetitions: Int = 1,
    ) = TranscriptionBenchmarkConfig(
        modelId = modelId,
        quantization = quantization,
        threadCount = threadCount,
        language = language,
        beamSize = beamSize,
        temperatureIncrement = temperatureIncrement,
        vadEnabled = vadEnabled,
        audioSource = audioSource,
        repetitions = repetitions,
    )

    @Test
    fun configValidation_rejectsInvalidParameters() {
        // Blank modelId
        try {
            createValidConfig(modelId = "   ")
            fail("Expected IllegalArgumentException for blank modelId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("modelId"))
        }

        // Blank quantization
        try {
            createValidConfig(quantization = "")
            fail("Expected IllegalArgumentException for blank quantization")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("quantization"))
        }

        // Non-positive threadCount
        try {
            createValidConfig(threadCount = 0)
            fail("Expected IllegalArgumentException for threadCount = 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("threadCount"))
        }
        try {
            createValidConfig(threadCount = -2)
            fail("Expected IllegalArgumentException for threadCount < 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("threadCount"))
        }

        // Blank language
        try {
            createValidConfig(language = " ")
            fail("Expected IllegalArgumentException for blank language")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("language"))
        }

        // Negative beamSize
        try {
            createValidConfig(beamSize = -1)
            fail("Expected IllegalArgumentException for beamSize < 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("beamSize"))
        }

        // Negative temperatureIncrement
        try {
            createValidConfig(temperatureIncrement = -0.1f)
            fail("Expected IllegalArgumentException for temperatureIncrement < 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("temperatureIncrement"))
        }

        // Non-positive repetitions
        try {
            createValidConfig(repetitions = 0)
            fail("Expected IllegalArgumentException for repetitions = 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("repetitions"))
        }

        // Negative audioCtx
        try {
            createValidConfig().copy(audioCtx = -1)
            fail("Expected IllegalArgumentException for audioCtx < 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("audioCtx"))
        }
    }

    @Test
    fun configImmutability_copyProducesNewInstanceWithoutMutatingOriginal() {
        val original = createValidConfig(threadCount = 4, language = "es")
        val modified = original.copy(threadCount = 6, language = "en")

        assertEquals(4, original.threadCount)
        assertEquals("es", original.language)
        assertEquals(6, modified.threadCount)
        assertEquals("en", modified.language)

        val identical = original.copy()
        assertEquals(original, identical)
        assertEquals(original.hashCode(), identical.hashCode())
    }

    @Test
    fun monotonicStageSumConsistency_andConstituentCalculation() {
        // 5ms queue + 2ms verify + 20ms load + 10ms warmup + 15ms decode + 5ms trim + 40ms encode + 30ms decode + 3ms cleanup = 130ms = 130_000_000 ns
        val timings = BenchmarkStageTimings(
            queueNanos = 5_000_000L,
            verifyNanos = 2_000_000L,
            modelLoadNanos = 20_000_000L,
            warmupNanos = 10_000_000L,
            audioDecodeResampleNanos = 15_000_000L,
            trimVadNanos = 5_000_000L,
            nativeEncodeNanos = 40_000_000L,
            nativeDecodeNanos = 30_000_000L,
            cleanupNanos = 3_000_000L,
            stopToResultNanos = 140_000_000L, // 140ms >= 130ms
        )

        assertEquals(130_000_000L, timings.constituentSumNanos)
        assertEquals(130.0, timings.constituentSumMs, 0.001)
        assertEquals(5.0, timings.queueMs!!, 0.001)
        assertEquals(2.0, timings.verifyMs!!, 0.001)
        assertEquals(20.0, timings.modelLoadMs!!, 0.001)
        assertEquals(10.0, timings.warmupMs!!, 0.001)
        assertEquals(15.0, timings.audioDecodeResampleMs!!, 0.001)
        assertEquals(5.0, timings.trimVadMs!!, 0.001)
        assertEquals(40.0, timings.nativeEncodeMs!!, 0.001)
        assertEquals(30.0, timings.nativeDecodeMs!!, 0.001)
        assertEquals(3.0, timings.cleanupMs!!, 0.001)
        assertEquals(140.0, timings.stopToResultMs!!, 0.001)

        assertTrue("Stage sum should be consistent when sum <= stopToResult", timings.isConsistent())

        // Inconsistent timings: constituent stages exceed stopToResult
        val inconsistentTimings = timings.copy(stopToResultNanos = 100_000_000L) // 100ms < 130ms
        assertFalse("Stage sum should be inconsistent when sum > stopToResult", inconsistentTimings.isConsistent(toleranceNanos = 0L))

        // Factory fromMillis
        val fromMs = BenchmarkStageTimings.fromMillis(
            queueMs = 5.0,
            verifyMs = 2.0,
            nativeEncodeMs = 40.0,
            nativeDecodeMs = 30.0,
            stopToResultMs = 80.0
        )
        assertEquals(5_000_000L, fromMs.queueNanos)
        assertEquals(2_000_000L, fromMs.verifyNanos)
        assertEquals(40_000_000L, fromMs.nativeEncodeNanos)
        assertEquals(30_000_000L, fromMs.nativeDecodeNanos)
        assertEquals(80_000_000L, fromMs.stopToResultNanos)
    }

    @Test
    fun realTimeFactorCalculation_handlesVariousDurations() {
        val config = createValidConfig()
        val result = TranscriptionBenchmarkResult(
            sampleId = "sample-001",
            config = config,
            audioDurationMs = 10_000,
            modelLoadMs = 200,
            inferenceMs = 2_500,
            reference = "hola mundo",
            hypothesis = "hola mundo",
            stageTimings = BenchmarkStageTimings.fromMillis(stopToResultMs = 2_800.0)
        )

        assertEquals(0.25, result.realTimeFactor!!, 0.0001)
        assertEquals(4.0, result.realTimeSpeed!!, 0.0001)
        assertEquals(2800.0, result.stopToResultMs, 0.001)
        assertEquals(0.28, result.stopToResultRealTimeFactor!!, 0.0001)

        // Zero audio duration: real time factor is null, speed is 0.0
        val zeroAudio = result.copy(audioDurationMs = 0)
        assertNull(zeroAudio.realTimeFactor)
        assertEquals(0.0, zeroAudio.realTimeSpeed!!, 0.0)
        assertNull(zeroAudio.stopToResultRealTimeFactor)

        // Zero inference: RTF = 0.0, RTS is null
        val zeroInference = result.copy(inferenceMs = 0)
        assertEquals(0.0, zeroInference.realTimeFactor!!, 0.0)
        assertNull(zeroInference.realTimeSpeed)
    }

    @Test
    fun jsonSerializationAndDeserialization_roundtrip() {
        val config = createValidConfig(
            modelId = "whisper-small-es",
            quantization = "q8_0",
            threadCount = 5,
            language = "es",
            repetitions = 3,
        ).copy(
            modelSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            pcmSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )

        val stageTimings = BenchmarkStageTimings(
            queueNanos = 1_000_000L,
            verifyNanos = 2_000_000L,
            modelLoadNanos = 250_000_000L,
            warmupNanos = 80_000_000L,
            audioDecodeResampleNanos = 15_000_000L,
            trimVadNanos = 8_000_000L,
            nativeEncodeNanos = 120_000_000L,
            nativeDecodeNanos = 90_000_000L,
            cleanupNanos = 4_000_000L,
            stopToResultNanos = 250_000_000L,
        )

        val environment = BenchmarkEnvironmentMetadata(
            deviceModel = "Pixel 8 Pro",
            buildFingerprint = "google/husky/husky:14/UD1A.230803.041:user/release-keys",
            soc = "Tensor G3",
            osVersion = "Android 14 (API 34)",
            nativeBuild = "whisper.cpp v1.7.1",
            modelSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            pcmSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            thermalStatusBefore = 0,
            thermalStatusAfter = 1,
            powerMode = "NORMAL",
            batteryChargePercent = 85,
            appGitRevision = "abc1234",
        )

        val nativeTimings = WhisperNativeTimings(
            sampleMs = 12.5f,
            encodeMs = 120.0f,
            decodeMs = 90.0f,
            batchdMs = 15.0f,
            promptMs = 8.0f,
        )

        val originalResult = TranscriptionBenchmarkResult(
            sampleId = "roundtrip-sample-01",
            config = config,
            audioDurationMs = 5000,
            recordingDurationMs = 5200,
            modelLoadMs = 250,
            warmupMs = 80,
            inferenceMs = 210,
            peakResidentBytes = 104_857_600L,
            thermalStatusBefore = 0,
            thermalStatusAfter = 1,
            reference = "El veloz murciélago hindú comía feliz cardillo y kiwi.",
            hypothesis = "El veloz murciélago hindú comía feliz cardillo y kiwi.",
            stageTimings = stageTimings,
            environment = environment,
            nativeTimings = nativeTimings,
        )

        val json = originalResult.toJson()
        val deserialized = TranscriptionBenchmarkResult.fromJson(json)

        assertEquals(originalResult.sampleId, deserialized.sampleId)
        assertEquals(originalResult.audioDurationMs, deserialized.audioDurationMs)
        assertEquals(originalResult.recordingDurationMs, deserialized.recordingDurationMs)
        assertEquals(originalResult.modelLoadMs, deserialized.modelLoadMs)
        assertEquals(originalResult.warmupMs, deserialized.warmupMs)
        assertEquals(originalResult.inferenceMs, deserialized.inferenceMs)
        assertEquals(originalResult.peakResidentBytes, deserialized.peakResidentBytes)
        assertEquals(originalResult.thermalStatusBefore, deserialized.thermalStatusBefore)
        assertEquals(originalResult.thermalStatusAfter, deserialized.thermalStatusAfter)
        assertEquals(originalResult.reference, deserialized.reference)
        assertEquals(originalResult.hypothesis, deserialized.hypothesis)

        // Config check
        assertEquals(originalResult.config.modelId, deserialized.config.modelId)
        assertEquals(originalResult.config.quantization, deserialized.config.quantization)
        assertEquals(originalResult.config.threadCount, deserialized.config.threadCount)
        assertEquals(originalResult.config.language, deserialized.config.language)
        assertEquals(originalResult.config.repetitions, deserialized.config.repetitions)
        assertEquals(originalResult.config.modelSha256, deserialized.config.modelSha256)
        assertEquals(originalResult.config.pcmSha256, deserialized.config.pcmSha256)

        // Stage timings check
        assertEquals(originalResult.stageTimings.queueNanos, deserialized.stageTimings.queueNanos)
        assertEquals(originalResult.stageTimings.verifyNanos, deserialized.stageTimings.verifyNanos)
        assertEquals(originalResult.stageTimings.modelLoadNanos, deserialized.stageTimings.modelLoadNanos)
        assertEquals(originalResult.stageTimings.warmupNanos, deserialized.stageTimings.warmupNanos)
        assertEquals(originalResult.stageTimings.audioDecodeResampleNanos, deserialized.stageTimings.audioDecodeResampleNanos)
        assertEquals(originalResult.stageTimings.trimVadNanos, deserialized.stageTimings.trimVadNanos)
        assertEquals(originalResult.stageTimings.nativeEncodeNanos, deserialized.stageTimings.nativeEncodeNanos)
        assertEquals(originalResult.stageTimings.nativeDecodeNanos, deserialized.stageTimings.nativeDecodeNanos)
        assertEquals(originalResult.stageTimings.cleanupNanos, deserialized.stageTimings.cleanupNanos)
        assertEquals(originalResult.stageTimings.stopToResultNanos, deserialized.stageTimings.stopToResultNanos)

        // Environment check
        assertEquals(originalResult.environment.deviceModel, deserialized.environment.deviceModel)
        assertEquals(originalResult.environment.soc, deserialized.environment.soc)
        assertEquals(originalResult.environment.osVersion, deserialized.environment.osVersion)
        assertEquals(originalResult.environment.buildFingerprint, deserialized.environment.buildFingerprint)
        assertEquals(originalResult.environment.modelSha256, deserialized.environment.modelSha256)
        assertEquals(originalResult.environment.pcmSha256, deserialized.environment.pcmSha256)

        // Native timings check
        assertNotNull(deserialized.nativeTimings)
        assertEquals(originalResult.nativeTimings!!.encodeMs, deserialized.nativeTimings!!.encodeMs, 0.001f)
        assertEquals(originalResult.nativeTimings!!.decodeMs, deserialized.nativeTimings!!.decodeMs, 0.001f)

        // List roundtrip
        val listJson = BenchmarkRunner.exportToJson(listOf(originalResult))
        val listDeserialized = BenchmarkRunner.importFromJson(listJson)
        assertEquals(1, listDeserialized.size)
        assertEquals(originalResult.sampleId, listDeserialized.first().sampleId)
    }

    @Test
    fun csvExportAndImport_roundtripWithEscaping() {
        val config = createValidConfig(
            modelId = "small-q8",
            quantization = "q8_0",
            threadCount = 4,
            language = "es",
        )
        val stageTimings = BenchmarkStageTimings.fromMillis(
            queueMs = 3.0,
            verifyMs = 1.5,
            nativeEncodeMs = 50.0,
            nativeDecodeMs = 35.0,
            stopToResultMs = 95.0
        )
        val environment = BenchmarkEnvironmentMetadata(
            deviceModel = "Pixel 8 Pro",
            soc = "Tensor G3",
            osVersion = "Android 14",
            nativeBuild = "v1.7.1"
        )
        val nativeTimings = WhisperNativeTimings(
            sampleMs = 2.0f,
            encodeMs = 50.0f,
            decodeMs = 35.0f,
            batchdMs = 5.0f,
            promptMs = 3.0f,
        )

        // Text containing commas, quotes, and newlines to test RFC 4180 escaping
        val referenceWithSpecialChars = "Hola, \"mundo\".\nLínea dos con coma, y comillas."
        val hypothesisWithSpecialChars = "Hola, \"mundo\".\nLínea dos con coma, y comillas."

        val original = TranscriptionBenchmarkResult(
            sampleId = "csv-test-01",
            config = config,
            audioDurationMs = 6000,
            recordingDurationMs = 6100,
            modelLoadMs = 150,
            warmupMs = 40,
            inferenceMs = 85,
            reference = referenceWithSpecialChars,
            hypothesis = hypothesisWithSpecialChars,
            stageTimings = stageTimings,
            environment = environment,
            nativeTimings = nativeTimings,
        )

        val csv = BenchmarkRunner.exportToCsv(listOf(original))
        val parsedList = BenchmarkRunner.importFromCsv(csv)

        assertEquals(1, parsedList.size)
        val parsed = parsedList.first()

        assertEquals(original.sampleId, parsed.sampleId)
        assertEquals(original.config.modelId, parsed.config.modelId)
        assertEquals(original.config.quantization, parsed.config.quantization)
        assertEquals(original.config.threadCount, parsed.config.threadCount)
        assertEquals(original.config.promptMode, parsed.config.promptMode)
        assertEquals(original.config.audioCtx, parsed.config.audioCtx)
        assertEquals(original.audioDurationMs, parsed.audioDurationMs)
        assertEquals(original.recordingDurationMs, parsed.recordingDurationMs)
        assertEquals(original.modelLoadMs, parsed.modelLoadMs)
        assertEquals(original.warmupMs, parsed.warmupMs)
        assertEquals(original.inferenceMs, parsed.inferenceMs)
        assertEquals(original.reference, parsed.reference)
        assertEquals(original.hypothesis, parsed.hypothesis)
        assertEquals(original.stageTimings.queueNanos, parsed.stageTimings.queueNanos)
        assertEquals(original.stageTimings.stopToResultNanos, parsed.stageTimings.stopToResultNanos)
        assertNotNull(parsed.nativeTimings)
        assertEquals(original.nativeTimings!!.encodeMs, parsed.nativeTimings!!.encodeMs, 0.001f)
        assertEquals(original.nativeTimings!!.decodeMs, parsed.nativeTimings!!.decodeMs, 0.001f)
        assertEquals(original.environment.deviceModel, parsed.environment.deviceModel)
    }

    @Test
    fun handlingOfMissingOrNullStages() {
        // All stages null
        val allNullStages = BenchmarkStageTimings()
        assertNull(allNullStages.queueNanos)
        assertNull(allNullStages.verifyNanos)
        assertNull(allNullStages.modelLoadNanos)
        assertNull(allNullStages.warmupNanos)
        assertNull(allNullStages.audioDecodeResampleNanos)
        assertNull(allNullStages.trimVadNanos)
        assertNull(allNullStages.nativeEncodeNanos)
        assertNull(allNullStages.nativeDecodeNanos)
        assertNull(allNullStages.cleanupNanos)
        assertNull(allNullStages.stopToResultNanos)
        assertEquals(0L, allNullStages.constituentSumNanos)
        assertEquals(0.0, allNullStages.constituentSumMs, 0.0)
        assertTrue(allNullStages.isConsistent())

        // Partially populated stages: only nativeEncode and nativeDecode
        val partialStages = BenchmarkStageTimings(
            nativeEncodeNanos = 60_000_000L,
            nativeDecodeNanos = 40_000_000L
        )
        assertNull(partialStages.queueNanos)
        assertNull(partialStages.stopToResultNanos)
        assertEquals(100_000_000L, partialStages.constituentSumNanos)
        assertEquals(100.0, partialStages.constituentSumMs, 0.001)
        assertTrue(partialStages.isConsistent())

        // BenchmarkResult with null optional stages and metadata
        val resultWithNulls = TranscriptionBenchmarkResult(
            sampleId = "null-stages-test",
            config = createValidConfig(),
            audioDurationMs = 3000,
            modelLoadMs = 100,
            warmupMs = null,
            inferenceMs = 50,
            peakResidentBytes = null,
            thermalStatusBefore = null,
            thermalStatusAfter = null,
            reference = "prueba de nulos",
            hypothesis = "prueba de nulos",
            recordingDurationMs = null,
            stageTimings = partialStages,
            environment = BenchmarkEnvironmentMetadata(),
            nativeTimings = null
        )

        // JSON roundtrip with nulls
        val json = resultWithNulls.toJson()
        val jsonParsed = TranscriptionBenchmarkResult.fromJson(json)
        assertNull(jsonParsed.warmupMs)
        assertNull(jsonParsed.recordingDurationMs)
        assertNull(jsonParsed.nativeTimings)
        assertNull(jsonParsed.stageTimings.queueNanos)
        assertEquals(60_000_000L, jsonParsed.stageTimings.nativeEncodeNanos)
        assertEquals(40_000_000L, jsonParsed.stageTimings.nativeDecodeNanos)

        // CSV roundtrip with nulls
        val csv = BenchmarkRunner.exportToCsv(listOf(resultWithNulls))
        val csvParsed = BenchmarkRunner.importFromCsv(csv).first()
        assertNull(csvParsed.warmupMs)
        assertNull(csvParsed.recordingDurationMs)
        assertNull(csvParsed.nativeTimings)
        assertNull(csvParsed.stageTimings.queueNanos)
        assertEquals(60_000_000L, csvParsed.stageTimings.nativeEncodeNanos)
        assertEquals(40_000_000L, csvParsed.stageTimings.nativeDecodeNanos)
    }
}
