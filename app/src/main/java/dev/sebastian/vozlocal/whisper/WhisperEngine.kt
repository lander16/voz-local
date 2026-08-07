package dev.sebastian.vozlocal.whisper

import android.content.Context
import android.util.Log
import dev.sebastian.vozlocal.BuildConfig
import dev.sebastian.vozlocal.data.repository.ModelUrls
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "WhisperEngine"

class WhisperEngine(private val context: Context) {
    private var whisperContext: WhisperContext? = null
    private var currentModelId: String? = null

    suspend fun loadModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        if (currentModelId == modelId && whisperContext != null) {
            return@withContext true
        }

        release()

        val modelFile = ModelUrls.getModelFile(context, modelId)
        if (!modelFile.exists()) {
            Log.e(TAG, "Model file does not exist: ${modelFile.absolutePath}")
            return@withContext false
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

    suspend fun transcribe(
        audioSamples: FloatArray,
        language: String = "es"
    ): String = withContext(Dispatchers.Default) {
        val wContext = whisperContext
        if (wContext == null) {
            Log.e(TAG, "Whisper context not initialized!")
            return@withContext ""
        }

        if (audioSamples.size < 3200) { // < 200ms audio sample
            return@withContext ""
        }

        try {
            val startMs = System.currentTimeMillis()
            val durationSec = audioSamples.size / 16000f
            Log.d(TAG, "Running transcription: ${audioSamples.size} samples (${String.format("%.1f", durationSec)}s audio), lang=$language")
            val result = wContext.transcribeData(audioSamples, printTimestamp = false, language = language)
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.i(TAG, "Transcription completed in ${elapsedMs}ms (${String.format("%.1fx", durationSec * 1000 / elapsedMs)} realtime)")
            if (BuildConfig.DEBUG) Log.d(TAG, "Raw transcription output: $result")
            result.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error transcribing audio samples", e)
            ""
        }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        try {
            whisperContext?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing whisper context", e)
        }
        whisperContext = null
        currentModelId = null
    }
}
