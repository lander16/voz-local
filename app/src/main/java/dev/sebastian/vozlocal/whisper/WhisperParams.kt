package dev.sebastian.vozlocal.whisper

/**
 * Parameters for a single Whisper transcription pass, mirroring the
 * `whisper_full_params` surface exposed by the project-owned JNI shim
 * (`fullTranscribeWithParams`). Defaults are tuned for Spanish dictation
 * (see the research pass that produced the threshold values).
 *
 * @param language whisper.cpp language code, or "auto" for auto-detection.
 * @param initialPrompt optional text primed into the decoder before the audio.
 * @param singleSegment true forces one segment output (live dictation).
 * @param printTimestamps true keeps per-segment timestamps (shared-file timeline).
 * @param noSpeechThold segment is skipped if no-speech probability is higher.
 * @param logprobThold segment is skipped if mean log-probability is lower.
 * @param entropyThold segment is skipped if mean entropy is higher.
 * @param vadModelPath absolute path to a Silero VAD ggml model; null disables VAD.
 * @param beamSize 0 = greedy sampling (default); >1 switches to beam search.
 * @param noTimestamps true asks whisper.cpp to skip timestamp token work.
 * @param temperatureInc increment used when decoding needs a higher-temperature fallback.
 * @param noContext whether each Whisper window is decoded independently.
 */
data class WhisperParams(
    val language: String = "es",
    val initialPrompt: String? = null,
    val singleSegment: Boolean = true,
    val printTimestamps: Boolean = false,
    val noSpeechThold: Float = 0.6f,
    val logprobThold: Float = -1.0f,
    val entropyThold: Float = 2.4f,
    val vadModelPath: String? = null,
    val beamSize: Int = 0, // 0 = greedy (default); >1 = beam search
    val noTimestamps: Boolean = true,
    val temperatureInc: Float = 0.2f,
    val noContext: Boolean = true,
    val modelIdHint: String? = null,
)

/**
 * Keep the low-latency, timestamp-free path comfortably below Whisper's
 * 30-second audio window. Longer recordings need timestamp tokens so
 * whisper.cpp can advance through subsequent windows instead of ending after
 * the first one.
 */
internal const val LIVE_SINGLE_WINDOW_MAX_SAMPLES = 25 * 16_000

internal fun WhisperParams.forLiveAudio(sampleCount: Int): WhisperParams {
    val needsMultipleWindows = sampleCount > LIVE_SINGLE_WINDOW_MAX_SAMPLES
    return copy(
        singleSegment = !needsMultipleWindows,
        printTimestamps = false,
        noTimestamps = !needsMultipleWindows,
    )
}
