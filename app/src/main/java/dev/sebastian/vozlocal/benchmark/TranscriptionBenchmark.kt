package dev.sebastian.vozlocal.benchmark

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.whispercpp.whisper.WhisperNativeTimings
import dev.sebastian.vozlocal.data.repository.DictationRepository
import dev.sebastian.vozlocal.whisper.PromptMode
import dev.sebastian.vozlocal.whisper.WhisperEngine
import dev.sebastian.vozlocal.whisper.WhisperParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Fully identifies one inference configuration so benchmark rows are comparable. */
data class TranscriptionBenchmarkConfig(
    val modelId: String,
    val quantization: String,
    val threadCount: Int,
    val language: String,
    val beamSize: Int,
    val temperatureIncrement: Float,
    val vadEnabled: Boolean,
    val audioSource: String,
    val backendMode: String = "compatibility",
    val backendTier: String = "unknown",
    val nativeBuildId: String = "unknown",
    val streaming: Boolean = false,
    val coldStart: Boolean = false,
    val repetitions: Int = 1,
    val seed: Long? = null,
    val modelSha256: String? = null,
    val pcmSha256: String? = null,
    val promptMode: PromptMode = PromptMode.AUTOMATIC,
    val audioCtx: Int = 0,
) {
    init {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(quantization.isNotBlank()) { "quantization must not be blank" }
        require(threadCount > 0) { "threadCount must be positive" }
        require(language.isNotBlank()) { "language must not be blank" }
        require(beamSize >= 0) { "beamSize must be non-negative" }
        require(temperatureIncrement >= 0.0f) { "temperatureIncrement must be non-negative" }
        require(repetitions > 0) { "repetitions must be positive" }
        require(audioCtx >= 0) { "audioCtx must be non-negative" }
    }
}

/**
 * Detailed monotonic stage timings for one utterance pass (all stored in nanoseconds).
 * Stages: queue, verify, model load, warmup, audio decode/resample, trim/VAD,
 * native encode, native decode, cleanup, stop-to-result.
 * Monotonic clocks: System.nanoTime() / elapsedRealtimeNanos().
 */
data class BenchmarkStageTimings(
    val queueNanos: Long? = null,
    val verifyNanos: Long? = null,
    val modelLoadNanos: Long? = null,
    val warmupNanos: Long? = null,
    val audioDecodeResampleNanos: Long? = null,
    val trimVadNanos: Long? = null,
    val nativeEncodeNanos: Long? = null,
    val nativeDecodeNanos: Long? = null,
    val cleanupNanos: Long? = null,
    val stopToResultNanos: Long? = null,
) {
    init {
        queueNanos?.let { require(it >= 0) { "queueNanos must be non-negative" } }
        verifyNanos?.let { require(it >= 0) { "verifyNanos must be non-negative" } }
        modelLoadNanos?.let { require(it >= 0) { "modelLoadNanos must be non-negative" } }
        warmupNanos?.let { require(it >= 0) { "warmupNanos must be non-negative" } }
        audioDecodeResampleNanos?.let { require(it >= 0) { "audioDecodeResampleNanos must be non-negative" } }
        trimVadNanos?.let { require(it >= 0) { "trimVadNanos must be non-negative" } }
        nativeEncodeNanos?.let { require(it >= 0) { "nativeEncodeNanos must be non-negative" } }
        nativeDecodeNanos?.let { require(it >= 0) { "nativeDecodeNanos must be non-negative" } }
        cleanupNanos?.let { require(it >= 0) { "cleanupNanos must be non-negative" } }
        stopToResultNanos?.let { require(it >= 0) { "stopToResultNanos must be non-negative" } }
    }

    val queueMs: Double? get() = queueNanos?.let { it / 1_000_000.0 }
    val verifyMs: Double? get() = verifyNanos?.let { it / 1_000_000.0 }
    val modelLoadMs: Double? get() = modelLoadNanos?.let { it / 1_000_000.0 }
    val warmupMs: Double? get() = warmupNanos?.let { it / 1_000_000.0 }
    val audioDecodeResampleMs: Double? get() = audioDecodeResampleNanos?.let { it / 1_000_000.0 }
    val trimVadMs: Double? get() = trimVadNanos?.let { it / 1_000_000.0 }
    val nativeEncodeMs: Double? get() = nativeEncodeNanos?.let { it / 1_000_000.0 }
    val nativeDecodeMs: Double? get() = nativeDecodeNanos?.let { it / 1_000_000.0 }
    val cleanupMs: Double? get() = cleanupNanos?.let { it / 1_000_000.0 }
    val stopToResultMs: Double? get() = stopToResultNanos?.let { it / 1_000_000.0 }

    /** Sum of all non-null constituent stage durations in nanoseconds (excluding aggregate stopToResult). */
    val constituentSumNanos: Long
        get() = listOfNotNull(
            queueNanos,
            verifyNanos,
            modelLoadNanos,
            warmupNanos,
            audioDecodeResampleNanos,
            trimVadNanos,
            nativeEncodeNanos,
            nativeDecodeNanos,
            cleanupNanos,
        ).sum()

    val constituentSumMs: Double
        get() = constituentSumNanos / 1_000_000.0

    /**
     * Checks if the constituent stage times are consistent with stop-to-result duration.
     * When stopToResultNanos is specified, constituent stages that occur within stop-to-result
     * should not exceed stopToResultNanos + tolerance.
     */
    fun isConsistent(toleranceNanos: Long = 1_000_000L): Boolean {
        val total = stopToResultNanos ?: return true
        return constituentSumNanos <= total + toleranceNanos
    }

    companion object {
        fun fromMillis(
            queueMs: Double? = null,
            verifyMs: Double? = null,
            modelLoadMs: Double? = null,
            warmupMs: Double? = null,
            audioDecodeResampleMs: Double? = null,
            trimVadMs: Double? = null,
            nativeEncodeMs: Double? = null,
            nativeDecodeMs: Double? = null,
            cleanupMs: Double? = null,
            stopToResultMs: Double? = null,
        ): BenchmarkStageTimings {
            fun toNanos(ms: Double?): Long? = ms?.let { (it * 1_000_000.0).toLong() }
            return BenchmarkStageTimings(
                queueNanos = toNanos(queueMs),
                verifyNanos = toNanos(verifyMs),
                modelLoadNanos = toNanos(modelLoadMs),
                warmupNanos = toNanos(warmupMs),
                audioDecodeResampleNanos = toNanos(audioDecodeResampleMs),
                trimVadNanos = toNanos(trimVadMs),
                nativeEncodeNanos = toNanos(nativeEncodeMs),
                nativeDecodeNanos = toNanos(nativeDecodeMs),
                cleanupNanos = toNanos(cleanupMs),
                stopToResultNanos = toNanos(stopToResultMs),
            )
        }
    }
}

/** Device and environment metadata captured for benchmark reproducibility. */
data class BenchmarkEnvironmentMetadata(
    val deviceModel: String = "unknown",
    val buildFingerprint: String = "unknown",
    val soc: String = "unknown",
    val osVersion: String = "unknown",
    val nativeBuild: String = "unknown",
    val modelSha256: String? = null,
    val pcmSha256: String? = null,
    val thermalStatusBefore: Int? = null,
    val thermalStatusAfter: Int? = null,
    val powerMode: String? = null,
    val batteryChargePercent: Int? = null,
    val appGitRevision: String? = null,
) {
    companion object {
        fun capture(
            context: Context? = null,
            modelSha256: String? = null,
            pcmSha256: String? = null,
            thermalStatusBefore: Int? = null,
            thermalStatusAfter: Int? = null,
            powerMode: String? = null,
            batteryChargePercent: Int? = null,
            nativeBuild: String = "whisper.cpp",
            appGitRevision: String? = null,
        ): BenchmarkEnvironmentMetadata {
            val deviceModel = runCatching { Build.MODEL }.getOrNull() ?: "unknown"
            val buildFingerprint = runCatching { Build.FINGERPRINT }.getOrNull() ?: "unknown"
            val soc = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
            }.getOrNull() ?: "unknown"
            val osVersion = runCatching {
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            }.getOrNull() ?: System.getProperty("os.name") ?: "unknown"

            var currentThermal = thermalStatusBefore
            if (currentThermal == null && context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                currentThermal = runCatching {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    pm?.currentThermalStatus
                }.getOrNull()
            }

            return BenchmarkEnvironmentMetadata(
                deviceModel = deviceModel,
                buildFingerprint = buildFingerprint,
                soc = soc,
                osVersion = osVersion,
                nativeBuild = nativeBuild,
                modelSha256 = modelSha256,
                pcmSha256 = pcmSha256,
                thermalStatusBefore = currentThermal,
                thermalStatusAfter = thermalStatusAfter,
                powerMode = powerMode,
                batteryChargePercent = batteryChargePercent,
                appGitRevision = appGitRevision,
            )
        }
    }
}

/** Measurements for one utterance and one [TranscriptionBenchmarkConfig]. */
data class TranscriptionBenchmarkResult(
    val sampleId: String,
    val config: TranscriptionBenchmarkConfig,
    val audioDurationMs: Long,
    val modelLoadMs: Long,
    val warmupMs: Long? = null,
    val inferenceMs: Long,
    val peakResidentBytes: Long? = null,
    val thermalStatusBefore: Int? = null,
    val thermalStatusAfter: Int? = null,
    val reference: String,
    val hypothesis: String,
    val scores: TranscriptionScorer.Scores = TranscriptionScorer.score(reference, hypothesis),
    val recordingDurationMs: Long? = null,
    val stageTimings: BenchmarkStageTimings = BenchmarkStageTimings(),
    val environment: BenchmarkEnvironmentMetadata = BenchmarkEnvironmentMetadata(),
    val nativeTimings: WhisperNativeTimings? = null,
) {
    init {
        require(sampleId.isNotBlank()) { "sampleId must not be blank" }
        require(audioDurationMs >= 0) { "audioDurationMs must be non-negative" }
        require(modelLoadMs >= 0) { "modelLoadMs must be non-negative" }
        require(inferenceMs >= 0) { "inferenceMs must be non-negative" }
        require(warmupMs == null || warmupMs >= 0) { "warmupMs must be non-negative" }
        require(recordingDurationMs == null || recordingDurationMs >= 0) { "recordingDurationMs must be non-negative" }
        require(config.threadCount > 0) { "threadCount must be positive" }
    }

    /** Compute time / audio time. Lower is faster; below 1.0 is faster than real time. */
    val realTimeFactor: Double?
        get() = audioDurationMs.takeIf { it > 0 }?.let { inferenceMs.toDouble() / it }

    /** Audio time / compute time. Higher is faster. */
    val realTimeSpeed: Double?
        get() = inferenceMs.takeIf { it > 0 }?.let { audioDurationMs.toDouble() / it }

    /** Stop-to-result latency in ms: prefers stageTimings.stopToResultMs, falling back to inferenceMs. */
    val stopToResultMs: Double
        get() = stageTimings.stopToResultMs ?: inferenceMs.toDouble()

    /** Real-time factor calculated against total stop-to-result latency. */
    val stopToResultRealTimeFactor: Double?
        get() = audioDurationMs.takeIf { it > 0 }?.let { stopToResultMs / it }

    /** Verifies consistency of constituent stage timings against stopToResult (if available). */
    val isStageSumConsistent: Boolean
        get() = stageTimings.isConsistent()

    fun toJson(): String = BenchmarkRunner.exportResultToJson(this)

    companion object {
        fun fromJson(json: String): TranscriptionBenchmarkResult = BenchmarkRunner.importResultFromJson(json)
    }
}

/** Runner and export pipeline for reproducible transcription benchmarks. */
object BenchmarkRunner {

    fun nowNanos(): Long = System.nanoTime()

    fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun computePcmSha256(samples: FloatArray): String {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            buffer.putFloat(s)
        }
        return computeSha256(buffer.array())
    }

    fun computeFileSha256(file: File): String {
        if (!file.exists() || !file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Executes a benchmark run according to [config] for [audioSamples].
     * Batch PCM runner. Cold means a fresh native context (not a flushed OS file
     * cache); warm requires the requested model already resident. File verification
     * and native execution are leased. Unmeasured stages stay null. Unsupported
     * modes fail explicitly instead of exporting misleading measurements.
     */
    suspend fun run(
        config: TranscriptionBenchmarkConfig,
        audioSamples: FloatArray,
        audioDurationMs: Long,
        sampleId: String,
        reference: String,
        recordingDurationMs: Long? = null,
        engine: WhisperEngine? = null,
        repository: DictationRepository? = null,
        context: Context? = null,
        modelFile: File? = null,
    ): TranscriptionBenchmarkResult = withContext(Dispatchers.Default) {
        requireNotNull(repository) { "A real repository is required; simulated benchmarks are not supported" }
        requireNotNull(context) { "Device context is required" }
        require(!config.streaming) { "This runner measures batch inference, not streaming" }
        require(!config.vadEnabled) { "Native VAD benchmarking requires a verified VAD model integration" }
        require(config.repetitions == 1 && config.seed == null) { "Run repetitions explicitly; seed control is unsupported" }
        require(config.promptMode != PromptMode.CUSTOM) { "Custom prompt text is not represented by this config" }
        require(audioSamples.isNotEmpty())
        val expectedQuantization = dev.sebastian.vozlocal.data.repository.ModelUrls.URL_MAP[config.modelId]
            ?.let { Regex("q[0-9]+_[0-9]+").find(it)?.value }
        require(expectedQuantization != null && config.quantization.equals(expectedQuantization, true)) {
            "Quantization label does not match the catalog model"
        }
        val pcmSha256 = computePcmSha256(audioSamples)
        require(config.pcmSha256 == null || config.pcmSha256 == pcmSha256) { "PCM checksum mismatch" }
        val backend = dev.sebastian.vozlocal.whisper.CpuBackendManager.ensureInitialized(context)
        require(config.backendMode.equals(backend.effectiveMode.name, ignoreCase = true)) {
            "Backend differs from request; backend changes require a process restart"
        }
        require(config.backendTier == "unknown" || config.backendTier == backend.tier)
        require(config.nativeBuildId == "unknown" || config.nativeBuildId == backend.nativeBuildId)

        var thermalBefore: Int? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalBefore = runCatching {
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
            }.getOrNull()
        }

        val stopTimestampNanos = nowNanos()

        val params = WhisperParams(
            language = config.language,
            singleSegment = !config.streaming,
            printTimestamps = false,
            beamSize = config.beamSize,
            temperatureInc = config.temperatureIncrement,
            vadModelPath = null,
            promptMode = config.promptMode,
            audioCtx = config.audioCtx,
            threadCountOverride = config.threadCount,
            modelIdHint = config.modelId,
        )
        var modelSha256: String? = null
        val pass = repository.withBenchmarkModelLease(config.modelId) { leasedEngine ->
            require(engine == null || engine === leasedEngine) { "Engine must belong to the repository" }
            val actualFile = dev.sebastian.vozlocal.data.repository.ModelUrls.getModelFile(context, config.modelId)
            require(modelFile == null || modelFile.canonicalFile == actualFile.canonicalFile)
            modelSha256 = computeFileSha256(actualFile)
            require(config.modelSha256 == null || config.modelSha256 == modelSha256) { "Model checksum mismatch" }
            leasedEngine.benchmarkPass(config.modelId, audioSamples, params, config.coldStart)
        }
        val modelLoadNanos = pass.loadNanos
        val warmupNanos = pass.warmupNanos
        val inferenceDurationNanos = pass.inferenceNanos
        val inferenceMs = inferenceDurationNanos / 1_000_000L

        val cleanupStart = nowNanos()
        val hypothesis = pass.text.trim()
        val cleanupNanos = nowNanos() - cleanupStart

        val stopToResultNanos = nowNanos() - stopTimestampNanos

        var thermalAfter: Int? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalAfter = runCatching {
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
            }.getOrNull()
        }

        val stageTimings = BenchmarkStageTimings(
            queueNanos = pass.queueNanos,
            verifyNanos = null, // Repository lease includes waiting; don't mislabel it as verification.
            modelLoadNanos = modelLoadNanos,
            warmupNanos = warmupNanos,
            audioDecodeResampleNanos = null, // Input is already decoded PCM.
            trimVadNanos = null,
            nativeEncodeNanos = pass.timings?.encodeMs?.let { (it * 1_000_000).toLong() },
            nativeDecodeNanos = pass.timings?.let {
                ((it.decodeMs + it.batchdMs + it.promptMs + it.sampleMs) * 1_000_000).toLong()
            },
            cleanupNanos = cleanupNanos,
            stopToResultNanos = stopToResultNanos,
        )

        val environment = BenchmarkEnvironmentMetadata.capture(
            context = context,
            modelSha256 = modelSha256,
            pcmSha256 = pcmSha256,
            thermalStatusBefore = thermalBefore,
            thermalStatusAfter = thermalAfter,
            nativeBuild = backend.nativeBuildId,
        )

        val modelLoadMs = (modelLoadNanos ?: 0L) / 1_000_000L
        val warmupMs = warmupNanos?.let { it / 1_000_000L }

        TranscriptionBenchmarkResult(
            sampleId = sampleId,
            config = config.copy(modelSha256 = modelSha256, pcmSha256 = pcmSha256,
                backendTier = backend.tier, nativeBuildId = backend.nativeBuildId),
            audioDurationMs = audioDurationMs,
            modelLoadMs = modelLoadMs,
            warmupMs = warmupMs,
            inferenceMs = inferenceMs,
            reference = reference,
            hypothesis = hypothesis,
            recordingDurationMs = recordingDurationMs,
            stageTimings = stageTimings,
            environment = environment,
            nativeTimings = pass.timings,
        )
    }

    // --- JSON Serialization / Deserialization ---

    fun exportResultToJson(result: TranscriptionBenchmarkResult): String = buildString {
        append("{\n")
        append("  \"sampleId\": ${escape(result.sampleId)},\n")
        append("  \"audioDurationMs\": ${result.audioDurationMs},\n")
        append("  \"modelLoadMs\": ${result.modelLoadMs},\n")
        append("  \"warmupMs\": ${result.warmupMs ?: "null"},\n")
        append("  \"inferenceMs\": ${result.inferenceMs},\n")
        append("  \"recordingDurationMs\": ${result.recordingDurationMs ?: "null"},\n")
        append("  \"peakResidentBytes\": ${result.peakResidentBytes ?: "null"},\n")
        append("  \"thermalStatusBefore\": ${result.thermalStatusBefore ?: "null"},\n")
        append("  \"thermalStatusAfter\": ${result.thermalStatusAfter ?: "null"},\n")
        append("  \"reference\": ${escape(result.reference)},\n")
        append("  \"hypothesis\": ${escape(result.hypothesis)},\n")
        append("  \"realTimeFactor\": ${result.realTimeFactor ?: "null"},\n")
        append("  \"realTimeSpeed\": ${result.realTimeSpeed ?: "null"},\n")
        append("  \"stopToResultMs\": ${result.stopToResultMs},\n")
        append("  \"stopToResultRealTimeFactor\": ${result.stopToResultRealTimeFactor ?: "null"},\n")

        // config
        append("  \"config\": {\n")
        append("    \"modelId\": ${escape(result.config.modelId)},\n")
        append("    \"quantization\": ${escape(result.config.quantization)},\n")
        append("    \"threadCount\": ${result.config.threadCount},\n")
        append("    \"language\": ${escape(result.config.language)},\n")
        append("    \"beamSize\": ${result.config.beamSize},\n")
        append("    \"temperatureIncrement\": ${result.config.temperatureIncrement},\n")
        append("    \"vadEnabled\": ${result.config.vadEnabled},\n")
        append("    \"audioSource\": ${escape(result.config.audioSource)},\n")
        append("    \"backendMode\": ${escape(result.config.backendMode)},\n")
        append("    \"backendTier\": ${escape(result.config.backendTier)},\n")
        append("    \"nativeBuildId\": ${escape(result.config.nativeBuildId)},\n")
        append("    \"streaming\": ${result.config.streaming},\n")
        append("    \"coldStart\": ${result.config.coldStart},\n")
        append("    \"repetitions\": ${result.config.repetitions},\n")
        append("    \"seed\": ${result.config.seed ?: "null"},\n")
        append("    \"modelSha256\": ${result.config.modelSha256?.let { escape(it) } ?: "null"},\n")
        append("    \"pcmSha256\": ${result.config.pcmSha256?.let { escape(it) } ?: "null"},\n")
        append("    \"promptMode\": ${escape(result.config.promptMode.name)},\n")
        append("    \"audioCtx\": ${result.config.audioCtx}\n")
        append("  },\n")

        // stageTimings
        append("  \"stageTimings\": {\n")
        append("    \"queueNanos\": ${result.stageTimings.queueNanos ?: "null"},\n")
        append("    \"verifyNanos\": ${result.stageTimings.verifyNanos ?: "null"},\n")
        append("    \"modelLoadNanos\": ${result.stageTimings.modelLoadNanos ?: "null"},\n")
        append("    \"warmupNanos\": ${result.stageTimings.warmupNanos ?: "null"},\n")
        append("    \"audioDecodeResampleNanos\": ${result.stageTimings.audioDecodeResampleNanos ?: "null"},\n")
        append("    \"trimVadNanos\": ${result.stageTimings.trimVadNanos ?: "null"},\n")
        append("    \"nativeEncodeNanos\": ${result.stageTimings.nativeEncodeNanos ?: "null"},\n")
        append("    \"nativeDecodeNanos\": ${result.stageTimings.nativeDecodeNanos ?: "null"},\n")
        append("    \"cleanupNanos\": ${result.stageTimings.cleanupNanos ?: "null"},\n")
        append("    \"stopToResultNanos\": ${result.stageTimings.stopToResultNanos ?: "null"}\n")
        append("  },\n")

        // environment
        append("  \"environment\": {\n")
        append("    \"deviceModel\": ${escape(result.environment.deviceModel)},\n")
        append("    \"buildFingerprint\": ${escape(result.environment.buildFingerprint)},\n")
        append("    \"soc\": ${escape(result.environment.soc)},\n")
        append("    \"osVersion\": ${escape(result.environment.osVersion)},\n")
        append("    \"nativeBuild\": ${escape(result.environment.nativeBuild)},\n")
        append("    \"modelSha256\": ${result.environment.modelSha256?.let { escape(it) } ?: "null"},\n")
        append("    \"pcmSha256\": ${result.environment.pcmSha256?.let { escape(it) } ?: "null"},\n")
        append("    \"thermalStatusBefore\": ${result.environment.thermalStatusBefore ?: "null"},\n")
        append("    \"thermalStatusAfter\": ${result.environment.thermalStatusAfter ?: "null"},\n")
        append("    \"powerMode\": ${result.environment.powerMode?.let { escape(it) } ?: "null"},\n")
        append("    \"batteryChargePercent\": ${result.environment.batteryChargePercent ?: "null"},\n")
        append("    \"appGitRevision\": ${result.environment.appGitRevision?.let { escape(it) } ?: "null"}\n")
        append("  },\n")

        // nativeTimings
        if (result.nativeTimings != null) {
            append("  \"nativeTimings\": {\n")
            append("    \"sampleMs\": ${result.nativeTimings.sampleMs},\n")
            append("    \"encodeMs\": ${result.nativeTimings.encodeMs},\n")
            append("    \"decodeMs\": ${result.nativeTimings.decodeMs},\n")
            append("    \"batchdMs\": ${result.nativeTimings.batchdMs},\n")
            append("    \"promptMs\": ${result.nativeTimings.promptMs}\n")
            append("  },\n")
        } else {
            append("  \"nativeTimings\": null,\n")
        }

        // scores
        append("  \"scores\": {\n")
        append("    \"wordErrorRate\": {\n")
        append("      \"edits\": ${result.scores.wordErrorRate.edits},\n")
        append("      \"referenceUnits\": ${result.scores.wordErrorRate.referenceUnits},\n")
        append("      \"rate\": ${result.scores.wordErrorRate.rate}\n")
        append("    },\n")
        append("    \"characterErrorRate\": {\n")
        append("      \"edits\": ${result.scores.characterErrorRate.edits},\n")
        append("      \"referenceUnits\": ${result.scores.characterErrorRate.referenceUnits},\n")
        append("      \"rate\": ${result.scores.characterErrorRate.rate}\n")
        append("    }\n")
        append("  }\n")

        append("}")
    }

    fun exportToJson(results: List<TranscriptionBenchmarkResult>): String = buildString {
        append("[\n")
        results.forEachIndexed { index, r ->
            val json = exportResultToJson(r).prependIndent("  ")
            append(json)
            if (index < results.size - 1) append(",")
            append("\n")
        }
        append("]")
    }

    fun importResultFromJson(json: String): TranscriptionBenchmarkResult {
        val parsed = MiniBenchmarkJson.parse(json) as? Map<*, *>
            ?: error("Invalid JSON: expected object")
        return mapToResult(parsed)
    }

    fun importFromJson(json: String): List<TranscriptionBenchmarkResult> {
        val parsed = MiniBenchmarkJson.parse(json)
        return when (parsed) {
            is List<*> -> parsed.map { mapToResult(it as Map<*, *>) }
            is Map<*, *> -> listOf(mapToResult(parsed))
            else -> error("Invalid JSON: expected array or object")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapToResult(map: Map<*, *>): TranscriptionBenchmarkResult {
        val configMap = map["config"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val config = TranscriptionBenchmarkConfig(
            modelId = configMap["modelId"]?.toString() ?: "",
            quantization = configMap["quantization"]?.toString() ?: "",
            threadCount = (configMap["threadCount"] as? Number)?.toInt() ?: 1,
            language = configMap["language"]?.toString() ?: "es",
            beamSize = (configMap["beamSize"] as? Number)?.toInt() ?: 0,
            temperatureIncrement = (configMap["temperatureIncrement"] as? Number)?.toFloat() ?: 0.0f,
            vadEnabled = (configMap["vadEnabled"] as? Boolean) ?: false,
            audioSource = configMap["audioSource"]?.toString() ?: "VOICE_RECOGNITION",
            backendMode = configMap["backendMode"]?.toString() ?: "compatibility",
            backendTier = configMap["backendTier"]?.toString() ?: "unknown",
            nativeBuildId = configMap["nativeBuildId"]?.toString() ?: "unknown",
            streaming = (configMap["streaming"] as? Boolean) ?: false,
            coldStart = (configMap["coldStart"] as? Boolean) ?: false,
            repetitions = (configMap["repetitions"] as? Number)?.toInt() ?: 1,
            seed = (configMap["seed"] as? Number)?.toLong(),
            modelSha256 = configMap["modelSha256"]?.toString(),
            pcmSha256 = configMap["pcmSha256"]?.toString(),
            promptMode = configMap["promptMode"]?.toString()?.let {
                runCatching { PromptMode.valueOf(it) }.getOrNull()
            } ?: PromptMode.AUTOMATIC,
            audioCtx = (configMap["audioCtx"] as? Number)?.toInt() ?: 0,
        )

        val stageMap = map["stageTimings"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val stageTimings = BenchmarkStageTimings(
            queueNanos = (stageMap["queueNanos"] as? Number)?.toLong(),
            verifyNanos = (stageMap["verifyNanos"] as? Number)?.toLong(),
            modelLoadNanos = (stageMap["modelLoadNanos"] as? Number)?.toLong(),
            warmupNanos = (stageMap["warmupNanos"] as? Number)?.toLong(),
            audioDecodeResampleNanos = (stageMap["audioDecodeResampleNanos"] as? Number)?.toLong(),
            trimVadNanos = (stageMap["trimVadNanos"] as? Number)?.toLong(),
            nativeEncodeNanos = (stageMap["nativeEncodeNanos"] as? Number)?.toLong(),
            nativeDecodeNanos = (stageMap["nativeDecodeNanos"] as? Number)?.toLong(),
            cleanupNanos = (stageMap["cleanupNanos"] as? Number)?.toLong(),
            stopToResultNanos = (stageMap["stopToResultNanos"] as? Number)?.toLong(),
        )

        val envMap = map["environment"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val environment = BenchmarkEnvironmentMetadata(
            deviceModel = envMap["deviceModel"]?.toString() ?: "unknown",
            buildFingerprint = envMap["buildFingerprint"]?.toString() ?: "unknown",
            soc = envMap["soc"]?.toString() ?: "unknown",
            osVersion = envMap["osVersion"]?.toString() ?: "unknown",
            nativeBuild = envMap["nativeBuild"]?.toString() ?: "unknown",
            modelSha256 = envMap["modelSha256"]?.toString(),
            pcmSha256 = envMap["pcmSha256"]?.toString(),
            thermalStatusBefore = (envMap["thermalStatusBefore"] as? Number)?.toInt(),
            thermalStatusAfter = (envMap["thermalStatusAfter"] as? Number)?.toInt(),
            powerMode = envMap["powerMode"]?.toString(),
            batteryChargePercent = (envMap["batteryChargePercent"] as? Number)?.toInt(),
            appGitRevision = envMap["appGitRevision"]?.toString(),
        )

        val nativeMap = map["nativeTimings"] as? Map<*, *>
        val nativeTimings = nativeMap?.let {
            WhisperNativeTimings(
                sampleMs = (it["sampleMs"] as? Number)?.toFloat() ?: 0f,
                encodeMs = (it["encodeMs"] as? Number)?.toFloat() ?: 0f,
                decodeMs = (it["decodeMs"] as? Number)?.toFloat() ?: 0f,
                batchdMs = (it["batchdMs"] as? Number)?.toFloat() ?: 0f,
                promptMs = (it["promptMs"] as? Number)?.toFloat() ?: 0f,
            )
        }

        val reference = map["reference"]?.toString() ?: ""
        val hypothesis = map["hypothesis"]?.toString() ?: ""

        return TranscriptionBenchmarkResult(
            sampleId = map["sampleId"]?.toString() ?: "",
            config = config,
            audioDurationMs = (map["audioDurationMs"] as? Number)?.toLong() ?: 0L,
            modelLoadMs = (map["modelLoadMs"] as? Number)?.toLong() ?: 0L,
            warmupMs = (map["warmupMs"] as? Number)?.toLong(),
            inferenceMs = (map["inferenceMs"] as? Number)?.toLong() ?: 0L,
            peakResidentBytes = (map["peakResidentBytes"] as? Number)?.toLong(),
            thermalStatusBefore = (map["thermalStatusBefore"] as? Number)?.toInt(),
            thermalStatusAfter = (map["thermalStatusAfter"] as? Number)?.toInt(),
            reference = reference,
            hypothesis = hypothesis,
            scores = TranscriptionScorer.score(reference, hypothesis),
            recordingDurationMs = (map["recordingDurationMs"] as? Number)?.toLong(),
            stageTimings = stageTimings,
            environment = environment,
            nativeTimings = nativeTimings,
        )
    }

    // --- CSV Export / Import ---

    private val CSV_COLUMNS = listOf(
        "sampleId",
        "modelId",
        "quantization",
        "threadCount",
        "language",
        "beamSize",
        "temperatureIncrement",
        "vadEnabled",
        "audioSource",
        "backendMode",
        "backendTier",
        "nativeBuildId",
        "streaming",
        "coldStart",
        "repetitions",
        "seed",
        "modelSha256",
        "pcmSha256",
        "promptMode",
        "audioCtx",
        "audioDurationMs",
        "recordingDurationMs",
        "modelLoadMs",
        "warmupMs",
        "inferenceMs",
        "peakResidentBytes",
        "queueNanos",
        "verifyNanos",
        "modelLoadNanos",
        "warmupNanos",
        "audioDecodeResampleNanos",
        "trimVadNanos",
        "nativeEncodeNanos",
        "nativeDecodeNanos",
        "cleanupNanos",
        "stopToResultNanos",
        "nativeSampleMs",
        "nativeEncodeMs",
        "nativeDecodeMs",
        "nativeBatchdMs",
        "nativePromptMs",
        "realTimeFactor",
        "stopToResultMs",
        "stopToResultRealTimeFactor",
        "deviceModel",
        "buildFingerprint",
        "soc",
        "osVersion",
        "nativeBuild",
        "thermalStatusBefore",
        "thermalStatusAfter",
        "powerMode",
        "batteryChargePercent",
        "appGitRevision",
        "wordErrorRate",
        "charErrorRate",
        "reference",
        "hypothesis"
    )

    fun exportToCsv(results: List<TranscriptionBenchmarkResult>): String = buildString {
        append(CSV_COLUMNS.joinToString(","))
        append("\n")
        results.forEach { r ->
            val values = listOf(
                r.sampleId,
                r.config.modelId,
                r.config.quantization,
                r.config.threadCount.toString(),
                r.config.language,
                r.config.beamSize.toString(),
                r.config.temperatureIncrement.toString(),
                r.config.vadEnabled.toString(),
                r.config.audioSource,
                r.config.backendMode,
                r.config.backendTier,
                r.config.nativeBuildId,
                r.config.streaming.toString(),
                r.config.coldStart.toString(),
                r.config.repetitions.toString(),
                r.config.seed?.toString() ?: "",
                r.config.modelSha256 ?: "",
                r.config.pcmSha256 ?: "",
                r.config.promptMode.name,
                r.config.audioCtx.toString(),
                r.audioDurationMs.toString(),
                r.recordingDurationMs?.toString() ?: "",
                r.modelLoadMs.toString(),
                r.warmupMs?.toString() ?: "",
                r.inferenceMs.toString(),
                r.peakResidentBytes?.toString() ?: "",
                r.stageTimings.queueNanos?.toString() ?: "",
                r.stageTimings.verifyNanos?.toString() ?: "",
                r.stageTimings.modelLoadNanos?.toString() ?: "",
                r.stageTimings.warmupNanos?.toString() ?: "",
                r.stageTimings.audioDecodeResampleNanos?.toString() ?: "",
                r.stageTimings.trimVadNanos?.toString() ?: "",
                r.stageTimings.nativeEncodeNanos?.toString() ?: "",
                r.stageTimings.nativeDecodeNanos?.toString() ?: "",
                r.stageTimings.cleanupNanos?.toString() ?: "",
                r.stageTimings.stopToResultNanos?.toString() ?: "",
                r.nativeTimings?.sampleMs?.toString() ?: "",
                r.nativeTimings?.encodeMs?.toString() ?: "",
                r.nativeTimings?.decodeMs?.toString() ?: "",
                r.nativeTimings?.batchdMs?.toString() ?: "",
                r.nativeTimings?.promptMs?.toString() ?: "",
                r.realTimeFactor?.toString() ?: "",
                r.stopToResultMs.toString(),
                r.stopToResultRealTimeFactor?.toString() ?: "",
                r.environment.deviceModel,
                r.environment.buildFingerprint,
                r.environment.soc,
                r.environment.osVersion,
                r.environment.nativeBuild,
                r.environment.thermalStatusBefore?.toString() ?: "",
                r.environment.thermalStatusAfter?.toString() ?: "",
                r.environment.powerMode ?: "",
                r.environment.batteryChargePercent?.toString() ?: "",
                r.environment.appGitRevision ?: "",
                r.scores.wordErrorRate.rate.toString(),
                r.scores.characterErrorRate.rate.toString(),
                r.reference,
                r.hypothesis
            )
            append(values.joinToString(",") { escapeCsv(it) })
            append("\n")
        }
    }

    fun importFromCsv(csv: String): List<TranscriptionBenchmarkResult> {
        val rows = parseCsv(csv)
        if (rows.isEmpty()) return emptyList()
        val headers = rows.first()
        val headerIndices = headers.mapIndexed { idx, name -> name.trim() to idx }.toMap()

        return rows.drop(1).map { row ->
            fun get(col: String): String? = headerIndices[col]?.let { if (it < row.size) row[it].ifEmpty { null } else null }
            fun getLong(col: String): Long? = get(col)?.toLongOrNull()
            fun getInt(col: String): Int? = get(col)?.toIntOrNull()
            fun getFloat(col: String): Float? = get(col)?.toFloatOrNull()
            fun getBoolean(col: String): Boolean = get(col)?.toBooleanStrictOrNull() ?: false

            val config = TranscriptionBenchmarkConfig(
                modelId = get("modelId") ?: "",
                quantization = get("quantization") ?: "",
                threadCount = getInt("threadCount") ?: 1,
                language = get("language") ?: "es",
                beamSize = getInt("beamSize") ?: 0,
                temperatureIncrement = getFloat("temperatureIncrement") ?: 0.0f,
                vadEnabled = getBoolean("vadEnabled"),
                audioSource = get("audioSource") ?: "VOICE_RECOGNITION",
                backendMode = get("backendMode") ?: "compatibility",
                backendTier = get("backendTier") ?: "unknown",
                nativeBuildId = get("nativeBuildId") ?: "unknown",
                streaming = getBoolean("streaming"),
                coldStart = getBoolean("coldStart"),
                repetitions = getInt("repetitions") ?: 1,
                seed = getLong("seed"),
                modelSha256 = get("modelSha256"),
                pcmSha256 = get("pcmSha256"),
                promptMode = get("promptMode")?.let {
                    runCatching { PromptMode.valueOf(it) }.getOrNull()
                } ?: PromptMode.AUTOMATIC,
                audioCtx = getInt("audioCtx") ?: 0,
            )

            val stageTimings = BenchmarkStageTimings(
                queueNanos = getLong("queueNanos"),
                verifyNanos = getLong("verifyNanos"),
                modelLoadNanos = getLong("modelLoadNanos"),
                warmupNanos = getLong("warmupNanos"),
                audioDecodeResampleNanos = getLong("audioDecodeResampleNanos"),
                trimVadNanos = getLong("trimVadNanos"),
                nativeEncodeNanos = getLong("nativeEncodeNanos"),
                nativeDecodeNanos = getLong("nativeDecodeNanos"),
                cleanupNanos = getLong("cleanupNanos"),
                stopToResultNanos = getLong("stopToResultNanos"),
            )

            val environment = BenchmarkEnvironmentMetadata(
                deviceModel = get("deviceModel") ?: "unknown",
                buildFingerprint = get("buildFingerprint") ?: "unknown",
                soc = get("soc") ?: "unknown",
                osVersion = get("osVersion") ?: "unknown",
                nativeBuild = get("nativeBuild") ?: "unknown",
                modelSha256 = get("modelSha256"),
                pcmSha256 = get("pcmSha256"),
                thermalStatusBefore = getInt("thermalStatusBefore"),
                thermalStatusAfter = getInt("thermalStatusAfter"),
                powerMode = get("powerMode"),
                batteryChargePercent = getInt("batteryChargePercent"),
                appGitRevision = get("appGitRevision"),
            )

            val hasNative = get("nativeEncodeMs") != null || get("nativeDecodeMs") != null
            val nativeTimings = if (hasNative) {
                WhisperNativeTimings(
                    sampleMs = getFloat("nativeSampleMs") ?: 0f,
                    encodeMs = getFloat("nativeEncodeMs") ?: 0f,
                    decodeMs = getFloat("nativeDecodeMs") ?: 0f,
                    batchdMs = getFloat("nativeBatchdMs") ?: 0f,
                    promptMs = getFloat("nativePromptMs") ?: 0f,
                )
            } else null

            val reference = get("reference") ?: ""
            val hypothesis = get("hypothesis") ?: ""

            TranscriptionBenchmarkResult(
                sampleId = get("sampleId") ?: "",
                config = config,
                audioDurationMs = getLong("audioDurationMs") ?: 0L,
                modelLoadMs = getLong("modelLoadMs") ?: 0L,
                warmupMs = getLong("warmupMs"),
                inferenceMs = getLong("inferenceMs") ?: 0L,
                peakResidentBytes = getLong("peakResidentBytes"),
                thermalStatusBefore = getInt("thermalStatusBefore"),
                thermalStatusAfter = getInt("thermalStatusAfter"),
                reference = reference,
                hypothesis = hypothesis,
                scores = TranscriptionScorer.score(reference, hypothesis),
                recordingDurationMs = getLong("recordingDurationMs"),
                stageTimings = stageTimings,
                environment = environment,
                nativeTimings = nativeTimings,
            )
        }
    }

    private fun escape(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code in 0..0x1f) append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    private fun parseCsv(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var inQuotes = false
        val currentRow = mutableListOf<String>()
        val currentField = StringBuilder()

        var i = 0
        while (i < csv.length) {
            val c = csv[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < csv.length && csv[i + 1] == '"') {
                        currentField.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                    }
                    '\r' -> {
                        if (i + 1 < csv.length && csv[i + 1] == '\n') i++
                        currentRow.add(currentField.toString())
                        currentField.clear()
                        if (currentRow.any { it.isNotEmpty() }) {
                            rows.add(currentRow.toList())
                        }
                        currentRow.clear()
                    }
                    '\n' -> {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                        if (currentRow.any { it.isNotEmpty() }) {
                            rows.add(currentRow.toList())
                        }
                        currentRow.clear()
                    }
                    else -> currentField.append(c)
                }
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            if (currentRow.any { it.isNotEmpty() }) {
                rows.add(currentRow.toList())
            }
        }
        return rows
    }
}

/** Lightweight pure-Kotlin JSON parser without external dependencies. */
private object MiniBenchmarkJson {
    fun parse(json: String): Any? = MiniBenchmarkParser(json.trim()).parse()

    private class MiniBenchmarkParser(private val src: String) {
        private var pos = 0

        fun parse(): Any? {
            skipWhitespace()
            return parseValue()
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun parseString(): String {
            pos++ // skip '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\' && pos < src.length) {
                    when (val esc = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
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
            error("Unterminated string in JSON at $pos")
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            return when {
                pos >= src.length -> null
                src[pos] == '"' -> parseString()
                src[pos] == '{' -> parseObject()
                src[pos] == '[' -> parseArray()
                else -> parseLiteral()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // skip '{'
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
                check(pos < src.length && src[pos] == ':') { "Expected ':' at $pos" }
                pos++
                skipWhitespace()
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                } else if (pos < src.length && src[pos] == '}') {
                    pos++
                    break
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            pos++ // skip '['
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (pos < src.length) {
                skipWhitespace()
                list.add(parseValue())
                skipWhitespace()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                } else if (pos < src.length && src[pos] == ']') {
                    pos++
                    break
                }
            }
            return list
        }

        private fun parseLiteral(): Any? {
            val start = pos
            while (pos < src.length && src[pos] !in ",}] \t\r\n") pos++
            val str = src.substring(start, pos)
            return when {
                str == "true" -> true
                str == "false" -> false
                str == "null" -> null
                str.contains('.') || str.contains('e') || str.contains('E') -> str.toDoubleOrNull()
                else -> str.toLongOrNull()
            }
        }
    }
}
