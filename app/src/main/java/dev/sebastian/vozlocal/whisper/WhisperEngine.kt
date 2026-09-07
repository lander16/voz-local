package dev.sebastian.vozlocal.whisper

import android.content.Context
import android.util.Log
import dev.sebastian.vozlocal.BuildConfig
import dev.sebastian.vozlocal.data.repository.ModelUrls
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CancellationException
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
    "Hola, ¿cómo estás? Voy a dictar en español con correcta ortografía, tildes y puntuación: así, también, aquí, allí, después, malecón, canción, estación, corazón, más, qué, cómo, cuándo, dónde."

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
            loadModelLocked(modelId)
        }
    }

    private suspend fun loadModelLocked(modelId: String): Boolean {
            if (currentModelId == modelId && whisperContext != null) {
                return true
            }

            releaseLocked()

            val modelFile = ModelUrls.getModelFile(context, modelId)
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file does not exist: ${modelFile.absolutePath}")
                return false
            }

            return try {
                val backend = CpuBackendManager.ensureInitialized(context)
                Log.i(TAG, "Using CPU tier=${backend.tier}, features=${backend.features.joinToString()}")
                Log.i(TAG, "Loading Whisper model from ${modelFile.absolutePath}")
                val loadedContext = WhisperContext.createContextFromFile(modelFile.absolutePath)
                whisperContext = loadedContext
                currentModelId = modelId
                Log.i(TAG, "Whisper model $modelId loaded successfully! Pre-warming GGML compute graphs...")
                if (warmupInternalLocked(loadedContext)) {
                    CpuBackendManager.markWarmupSuccessful(context)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Whisper model $modelId", e)
                whisperContext = null
                currentModelId = null
                false
            }
    }

    suspend fun warmup(): Boolean = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            val wContext = whisperContext ?: return@withLock false
            warmupInternalLocked(wContext)
        }
    }

    private suspend fun warmupInternalLocked(wContext: WhisperContext): Boolean {
        return try {
            val startMs = System.currentTimeMillis()
            val warmupParams = WhisperParams(
                language = "auto",
                singleSegment = true,
                noTimestamps = true,
                noContext = true,
                audioCtx = 256
            )
            check(wContext.warmup(com.whispercpp.whisper.WhisperCpuConfig.threadCountFor(warmupParams)))
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.i(TAG, "Whisper GGML compute graph pre-warmed in ${elapsedMs}ms")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal error during model pre-warm pass", e)
            false
        }
    }

    suspend fun transcribe(
        audioSamples: FloatArray,
        language: String = "es",
        params: WhisperParams = WhisperParams()
    ): String = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            transcribeLoadedModelLocked(audioSamples, language, params)
        }
    }

    /**
     * Loads the requested model, if needed, and performs inference under one
     * context lease. A later selection/preload cannot substitute another model
     * between verification by the repository and native execution.
     */
    suspend fun transcribeWithModel(
        modelId: String,
        audioSamples: FloatArray,
        language: String = "es",
        params: WhisperParams = WhisperParams()
    ): String = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            if (!loadModelLocked(modelId)) {
                throw IllegalStateException("Couldn't load requested Whisper model $modelId")
            }
            transcribeLoadedModelLocked(audioSamples, language, params)
        }
    }

    private suspend fun transcribeLoadedModelLocked(
        audioSamples: FloatArray,
        language: String,
        params: WhisperParams
    ): String {
            val wContext = whisperContext
            if (wContext == null) {
                Log.e(TAG, "Whisper context not initialized!")
                throw IllegalStateException("Whisper context not initialized")
            }

            if (audioSamples.size < 3200) { // < 200ms audio sample
                return ""
            }

            return try {
                // Backward-compat resolution: an explicitly-passed positional language
                // (legacy callers) wins; otherwise params.language is used.
                val effectiveLanguage = if (language == "es") params.language else language
                val effectiveParams = params.copy(
                    language = effectiveLanguage,
                    initialPrompt = effectivePrompt(effectiveLanguage, params.initialPrompt)
                ).forIndependentRequest()

                val startMs = System.currentTimeMillis()
                val durationSec = audioSamples.size / 16000f
                Log.d(TAG, "Running transcription: ${audioSamples.size} samples (${String.format("%.1f", durationSec)}s audio), lang=$effectiveLanguage")
                val result = wContext.transcribeData(audioSamples, effectiveParams)
                val elapsedMs = System.currentTimeMillis() - startMs
                Log.i(TAG, "Transcription completed in ${elapsedMs}ms (${String.format("%.1fx", durationSec * 1000 / elapsedMs)} realtime)")
                if (BuildConfig.DEBUG) Log.d(TAG, "Raw transcription output: $result")
                HallucinationFilter.filter(result).trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error transcribing audio samples", e)
                throw e
            }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            releaseLocked()
        }
    }

    /**
     * Coordinates filesystem replacement/deletion with native context ownership.
     * Returns only after active inference has completed and the matching model is
     * no longer held by the native context.
     */
    suspend fun releaseModelIfLoaded(modelId: String) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (currentModelId == modelId) releaseLocked()
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
