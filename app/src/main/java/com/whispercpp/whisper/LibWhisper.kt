package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.os.Process
import android.util.Log
import dev.sebastian.vozlocal.whisper.WhisperParams
import kotlinx.coroutines.*
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

class WhisperContext private constructor(private var ptr: Long) {
    // Meet Whisper C++ constraint: Don't access from more than one thread at a time.
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /**
     * Full-parameter transcription pass. Backed by the project-owned JNI shim's
     * `fullTranscribeWithParams`, which exposes the entire high-value
     * `whisper_full_params` surface (language, initial_prompt, single_segment,
     * thresholds, beam_size, native Silero VAD).
     */
    suspend fun transcribeData(
        data: FloatArray,
        params: WhisperParams
    ): String = withContext(dispatcher) {
        val contextPtr = ptr
        require(contextPtr != 0L)
        currentCoroutineContext().ensureActive()
        val cancellationHandler = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                WhisperLib.requestAbort(contextPtr)
            }
        }
        try {
        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        }
        val numThreads = WhisperCpuConfig.threadCountFor(params)
        Log.d(LOG_TAG, "Selecting $numThreads threads, language=${params.language}")

        WhisperLib.fullTranscribeWithParams(
            contextPtr,
            numThreads,
            data,
            params.language,
            params.initialPrompt,
            params.singleSegment,
            params.printTimestamps,
            params.noSpeechThold,
            params.logprobThold,
            params.entropyThold,
            params.vadModelPath,
            params.beamSize,
            params.noTimestamps,
            params.temperatureInc,
            params.noContext,
            params.audioCtx
        )

        currentCoroutineContext().ensureActive()
        val textCount = WhisperLib.getTextSegmentCount(contextPtr)
        buildString {
            for (i in 0 until textCount) {
                val segText = WhisperLib.getTextSegment(contextPtr, i).trim()
                if (segText.isEmpty()) continue
                if (isNotEmpty() && !endsWith(' ')) append(' ')
                append(segText)
            }
        }
        } finally {
            cancellationHandler?.dispose()
        }
    }

    /**
     * Backward-compatible 3-arg overload. `null` language maps to "auto"
     * (matching the old fullTranscribe auto-detect path).
     */
    suspend fun transcribeData(
        data: FloatArray,
        printTimestamp: Boolean = true,
        language: String? = null
    ): String {
        val params = WhisperParams(
            language = language ?: "auto",
            singleSegment = !printTimestamp,
            printTimestamps = printTimestamp
        )
        return transcribeData(data, params)
    }

    suspend fun benchMemory(nthreads: Int): String = withContext(dispatcher) {
        return@withContext WhisperLib.benchMemcpy(nthreads)
    }

    suspend fun benchGgmlMulMat(nthreads: Int): String = withContext(dispatcher) {
        return@withContext WhisperLib.benchGgmlMulMat(nthreads)
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        if (ptr != 0L) {
            WhisperLib.requestAbort(ptr)
            WhisperLib.forgetAbortState(ptr)
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
        dispatcher.close()
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context with path $filePath")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from input stream")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from asset $assetPath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String {
            return WhisperLib.getSystemInfo()
        }
    }
}

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loaded = false
            val librariesToTry = when {
                isArmEabiV8a() -> listOf("whisper_v8fp16_va", "whisper")
                isArmEabiV7a() -> listOf("whisper_vfpv4", "whisper")
                else -> listOf("whisper")
            }

            for (libName in librariesToTry) {
                try {
                    Log.d(LOG_TAG, "Attempting to load lib$libName.so")
                    System.loadLibrary(libName)
                    Log.i(LOG_TAG, "Successfully loaded lib$libName.so")
                    loaded = true
                    break
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(LOG_TAG, "Could not load lib$libName.so: ${e.message}")
                }
            }

            if (!loaded) {
                Log.e(LOG_TAG, "Failed to load any whisper native library!")
            }
        }

        // JNI methods
        external fun initContextFromInputStream(inputStream: InputStream): Long
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)
        external fun fullTranscribeWithLang(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String)
        external fun fullTranscribeWithParams(
            contextPtr: Long,
            numThreads: Int,
            audioData: FloatArray,
            language: String?,
            initialPrompt: String?,
            singleSegment: Boolean,
            printTimestamps: Boolean,
            noSpeechThold: Float,
            logprobThold: Float,
            entropyThold: Float,
            vadModelPath: String?,
            beamSize: Int,
            noTimestamps: Boolean,
            temperatureInc: Float,
            noContext: Boolean,
            audioCtx: Int
        )
        external fun requestAbort(contextPtr: Long)
        external fun forgetAbortState(contextPtr: Long)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
        external fun benchMemcpy(nthread: Int): String
        external fun benchGgmlMulMat(nthread: Int): String
    }
}

//  500 -> 00:05.000
// 6000 -> 01:00.000
private fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000

    val delimiter = if (comma) "," else "."
    return String.format(Locale.US, "%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
}

private fun isArmEabiV7a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("armeabi-v7a")
}

private fun isArmEabiV8a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("arm64-v8a")
}
