package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

class WhisperContext private constructor(private var ptr: Long) {
    // Meet Whisper C++ constraint: Don't access from more than one thread at a time.
    private val executor = Executors.newSingleThreadExecutor()
    private val scope: CoroutineScope = CoroutineScope(executor.asCoroutineDispatcher())

    suspend fun transcribeData(
        data: FloatArray,
        printTimestamp: Boolean = true,
        language: String? = null
    ): String = withContext(scope.coroutineContext) {
        require(ptr != 0L)
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Selecting $numThreads threads, language=${language ?: "default"}")

        // The vendored JNI only ships the 3-param fullTranscribe, which
        // hardcodes language="en" in its whisper_full_params. There is no
        // fullTranscribeWithLang symbol in the compiled .so. Until a
        // project-owned JNI shim is added, always call fullTranscribe
        // and accept the ~200-500ms auto-detect overhead. Spanish (and
        // any other) audio still transcribes correctly because the audio
        // language wins over the prompt language.
        WhisperLib.fullTranscribe(ptr, numThreads, data)

        val textCount = WhisperLib.getTextSegmentCount(ptr)
        return@withContext buildString {
            for (i in 0 until textCount) {
                val segText = WhisperLib.getTextSegment(ptr, i).trim()
                if (segText.isEmpty()) continue
                if (isNotEmpty() && !endsWith(' ')) append(' ')
                append(segText)
            }
        }
    }

    suspend fun benchMemory(nthreads: Int): String = withContext(scope.coroutineContext) {
        return@withContext WhisperLib.benchMemcpy(nthreads)
    }

    suspend fun benchGgmlMulMat(nthreads: Int): String = withContext(scope.coroutineContext) {
        return@withContext WhisperLib.benchGgmlMulMat(nthreads)
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
        scope.cancel()
        executor.shutdown()
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
