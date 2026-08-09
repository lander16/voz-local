package dev.sebastian.vozlocal.whisper

import android.content.Context
import android.util.Log
import dev.sebastian.vozlocal.BuildConfig
import dev.sebastian.vozlocal.data.repository.ModelUrls
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "WhisperEngine"

/**
 * Default priming prompt for Spanish dictation: forces Whisper to stay in
 * Spanish and prefer correct accents/punctuation. Substituted automatically
 * when no explicit initial prompt is configured.
 */
internal const val SPANISH_PROMPT =
    "Hola, ¿cómo estás? Voy a dictar en español. Uso acentos y puntuación correcta."

/**
 * Resolves the effective initial prompt: an explicitly-configured prompt wins;
 * otherwise Spanish gets the default priming prompt and other languages get
 * none (let Whisper auto-prompt).
 */
internal fun effectivePrompt(language: String, initialPrompt: String?): String? {
    if (initialPrompt != null) return initialPrompt
    return if (language == "es") SPANISH_PROMPT else null
}

class WhisperEngine(private val context: Context) {
    private val lifecycleMutex = Mutex()
    private var whisperContext: WhisperContext? = null
    private var currentModelId: String? = null

    suspend fun loadModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (currentModelId == modelId && whisperContext != null) {
                return@withLock true
            }

            releaseLocked()

            val modelFile = ModelUrls.getModelFile(context, modelId)
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file does not exist: ${modelFile.absolutePath}")
                return@withLock false
            }

            try {
                Log.i(TAG, "Loading Whisper model from ${modelFile.absolutePath}")
                whisperContext = WhisperContext.createContextFromFile(modelFile.absolutePath)
                currentModelId = modelId
                Log.i(TAG, "Whisper model $modelId loaded successfully!")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Whisper model $modelId", e)
                whisperContext = null
                currentModelId = null
                false
            }
        }
    }

    suspend fun transcribe(
        audioSamples: FloatArray,
        language: String = "es",
        params: WhisperParams = WhisperParams()
    ): String = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            val wContext = whisperContext
            if (wContext == null) {
                Log.e(TAG, "Whisper context not initialized!")
                return@withLock ""
            }

            if (audioSamples.size < 3200) { // < 200ms audio sample
                return@withLock ""
            }

            try {
                // Backward-compat resolution: an explicitly-passed positional language
                // (legacy callers) wins; otherwise params.language is used.
                val effectiveLanguage = if (language == "es") params.language else language
                val effectiveParams = params.copy(
                    language = effectiveLanguage,
                    initialPrompt = effectivePrompt(effectiveLanguage, params.initialPrompt)
                )

                val startMs = System.currentTimeMillis()
                val durationSec = audioSamples.size / 16000f
                Log.d(TAG, "Running transcription: ${audioSamples.size} samples (${String.format("%.1f", durationSec)}s audio), lang=$effectiveLanguage")
                val result = wContext.transcribeData(audioSamples, effectiveParams)
                val elapsedMs = System.currentTimeMillis() - startMs
                Log.i(TAG, "Transcription completed in ${elapsedMs}ms (${String.format("%.1fx", durationSec * 1000 / elapsedMs)} realtime)")
                if (BuildConfig.DEBUG) Log.d(TAG, "Raw transcription output: $result")
                HallucinationFilter.filter(result).trim()
            } catch (e: Exception) {
                Log.e(TAG, "Error transcribing audio samples", e)
                ""
            }
        }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            releaseLocked()
        }
    }

    private suspend fun releaseLocked() {
        try {
            whisperContext?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing whisper context", e)
        }
        whisperContext = null
        currentModelId = null
    }
}
