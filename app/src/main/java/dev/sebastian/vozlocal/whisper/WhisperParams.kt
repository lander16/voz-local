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
 */
data class WhisperParams(
    val language: String = "es",
    val initialPrompt: String? = null,
    val singleSegment: Boolean = true,
    val printTimestamps: Boolean = false,
    val noSpeechThold: Float = 0.4f,
    val logprobThold: Float = -0.5f,
    val entropyThold: Float = 2.4f,
    val vadModelPath: String? = null,
    val beamSize: Int = 0, // 0 = greedy (default); >1 = beam search
)
