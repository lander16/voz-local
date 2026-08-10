package dev.sebastian.vozlocal.benchmark

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
)

/** Measurements for one utterance and one [TranscriptionBenchmarkConfig]. */
data class TranscriptionBenchmarkResult(
    val sampleId: String,
    val config: TranscriptionBenchmarkConfig,
    val audioDurationMs: Long,
    val modelLoadMs: Long,
    val inferenceMs: Long,
    val peakResidentBytes: Long? = null,
    val thermalStatusBefore: Int? = null,
    val thermalStatusAfter: Int? = null,
    val reference: String,
    val hypothesis: String,
    val scores: TranscriptionScorer.Scores = TranscriptionScorer.score(reference, hypothesis),
) {
    init {
        require(sampleId.isNotBlank()) { "sampleId must not be blank" }
        require(audioDurationMs >= 0) { "audioDurationMs must be non-negative" }
        require(modelLoadMs >= 0) { "modelLoadMs must be non-negative" }
        require(inferenceMs >= 0) { "inferenceMs must be non-negative" }
        require(config.threadCount > 0) { "threadCount must be positive" }
    }

    /** Compute time / audio time. Lower is faster; below 1.0 is faster than real time. */
    val realTimeFactor: Double?
        get() = audioDurationMs.takeIf { it > 0 }?.let { inferenceMs.toDouble() / it }

    /** Audio time / compute time. Higher is faster. */
    val realTimeSpeed: Double?
        get() = inferenceMs.takeIf { it > 0 }?.let { audioDurationMs.toDouble() / it }
}
