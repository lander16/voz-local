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

import java.util.concurrent.atomic.AtomicInteger

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

interface WhisperContextAdapter {
    suspend fun warmup(threadCount: Int): Boolean
    suspend fun transcribeData(data: FloatArray, params: WhisperParams): String
    suspend fun release()
}

internal class RealWhisperContextAdapter(private val context: WhisperContext) : WhisperContextAdapter {
    override suspend fun warmup(threadCount: Int): Boolean = context.warmup(threadCount)
    override suspend fun transcribeData(data: FloatArray, params: WhisperParams): String =
        context.transcribeData(data, params)
    override suspend fun release() = context.release()
}

class WhisperEngine internal constructor(
    private val context: Context,
    private val contextLoader: suspend (modelPath: String) -> WhisperContextAdapter
) {
    constructor(context: Context) : this(
        context = context,
        contextLoader = { path ->
            RealWhisperContextAdapter(WhisperContext.createContextFromFile(path))
        }
    )

    private val lifecycleMutex = Mutex()
    private var whisperContext: WhisperContextAdapter? = null
    private var currentModelId: String? = null
    private val activeInferenceCount = AtomicInteger(0)

    fun isBusy(): Boolean = activeInferenceCount.get() > 0

    fun isModelLoaded(): Boolean = whisperContext != null

    fun isModelLoaded(modelId: String): Boolean =
        currentModelId == modelId && whisperContext != null

    fun getLoadedModelId(): String? = currentModelId

    suspend fun loadModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            loadModelLocked(modelId)
        }
    }

    private suspend fun loadModelLocked(modelId: String): Boolean {
        if (currentModelId == modelId && whisperContext != null) {
            return true
        }

        val modelFile = ModelUrls.getModelFile(context, modelId)
        if (!modelFile.exists() || !modelFile.isFile || modelFile.length() <= 0L) {
            Log.e(TAG, "Model file does not exist or is empty: ${modelFile.absolutePath}")
            return false
        }

        val newContext = try {
            try {
                val backend = CpuBackendManager.ensureInitialized(context)
                Log.i(TAG, "Using CPU tier=${backend.tier}, features=${backend.features.joinToString()}")
            } catch (e: Throwable) {
                Log.w(TAG, "CPU backend initialization notice: ${e.message}")
            }
            Log.i(TAG, "Loading Whisper model from ${modelFile.absolutePath}")
            contextLoader(modelFile.absolutePath)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load Whisper model $modelId", e)
            return false
        }

        // Only release previous context AFTER confirming replacement model file
        // exists and was successfully loaded into a valid native context.
        val oldContext = whisperContext
        whisperContext = newContext
        currentModelId = modelId
        try {
            oldContext?.release()
        } catch (e: Throwable) {
            Log.e(TAG, "Error releasing previous whisper context", e)
        }

        Log.i(TAG, "Whisper model $modelId loaded successfully! Pre-warming GGML compute graphs...")
        val warmupSucceeded = warmupInternalLocked(newContext, modelId)
        // Separate capability probe success, graph warmup, and model readiness:
        // Loading and warmup passed without native abort/termination, so we clear
        // the pending probe sentinel so next launch doesn't quarantine a usable backend.
        try {
            CpuBackendManager.markWarmupSuccessful(context)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not mark warmup successful: ${e.message}")
        }
        if (!warmupSucceeded) {
            Log.w(TAG, "GGML graph warmup failed or was partial for model $modelId, but model is ready")
        }
        return true
    }

    suspend fun warmup(): Boolean = withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            val wContext = whisperContext ?: return@withLock false
            warmupInternalLocked(wContext, currentModelId)
        }
    }

    private suspend fun warmupInternalLocked(wContext: WhisperContextAdapter, modelId: String? = null): Boolean {
        return try {
            val startMs = System.currentTimeMillis()
            val warmupParams = WhisperParams(
                language = "auto",
                singleSegment = true,
                noTimestamps = true,
                noContext = true,
                modelIdHint = modelId,
                audioCtx = 256
            )
            val threadCount = com.whispercpp.whisper.WhisperCpuConfig.threadCountFor(warmupParams)
            check(wContext.warmup(threadCount))
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.i(TAG, "Whisper GGML compute graph pre-warmed in ${elapsedMs}ms (threads=$threadCount)")
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
        activeInferenceCount.incrementAndGet()
        try {
            lifecycleMutex.withLock {
                transcribeLoadedModelLocked(audioSamples, language, params)
            }
        } finally {
            activeInferenceCount.decrementAndGet()
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
        activeInferenceCount.incrementAndGet()
        try {
            lifecycleMutex.withLock {
                if (!loadModelLocked(modelId)) {
                    throw IllegalStateException("Couldn't load requested Whisper model $modelId")
                }
                transcribeLoadedModelLocked(audioSamples, language, params)
            }
        } finally {
            activeInferenceCount.decrementAndGet()
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
                initialPrompt = effectivePrompt(effectiveLanguage, params.initialPrompt),
                modelIdHint = params.modelIdHint ?: currentModelId
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
     * Releases the native context only if no active inference is in progress.
     * Returns true if released, or false if the engine is currently busy.
     */
    suspend fun releaseIfIdle(): Boolean = withContext(Dispatchers.IO) {
        if (isBusy()) return@withContext false
        lifecycleMutex.withLock {
            if (isBusy()) return@withLock false
            releaseLocked()
            true
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
        } catch (e: Throwable) {
            Log.e(TAG, "Error releasing whisper context", e)
        }
        whisperContext = null
        currentModelId = null
    }
}
