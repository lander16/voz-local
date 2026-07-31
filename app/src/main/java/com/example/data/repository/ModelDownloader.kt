package com.example.data.repository

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

private const val TAG = "ModelDownloader"

object ModelUrls {
    // Use quantized q8_0 models for ~2x faster inference on mobile (reduced memory bandwidth)
    val URL_MAP = mapOf(
        "whisper_tiny" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q8_0.bin",
        "whisper_base" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q8_0.bin",
        "whisper_small" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin",
        "whisper_medium" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q8_0.bin",
        "whisper_es_optimized" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin",
        "qwen2.5_0.5b" to "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    )

    fun getModelFile(context: Context, modelId: String): File {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return File(modelsDir, "ggml-$modelId.bin")
    }

    fun isModelDownloaded(context: Context, modelId: String): Boolean {
        val file = getModelFile(context, modelId)
        return file.exists() && file.length() > 1000000 // > 1MB
    }
}

class ModelDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder().build()

    suspend fun downloadModel(
        modelId: String,
        onProgress: suspend (Float) -> Unit
    ): Boolean {
        val url = ModelUrls.URL_MAP[modelId] ?: return false
        val outputFile = ModelUrls.getModelFile(context, modelId)

        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to download model $modelId: ${response.code}")
                return false
            }

            val body = response.body ?: return false
            val contentLength = body.contentLength()
            val inputStream: InputStream = body.byteStream()
            val outputStream = FileOutputStream(outputFile)

            val buffer = ByteArray(65536) // 64KB buffer for faster throughput
            var bytesRead: Int
            var totalBytesRead = 0L
            var lastEmittedProgress = -1f

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (contentLength > 0) {
                    val progress = (totalBytesRead.toFloat() / contentLength.toFloat()).coerceIn(0.0f, 1.0f)
                    if (progress - lastEmittedProgress >= 0.01f || progress >= 1.0f) {
                        lastEmittedProgress = progress
                        onProgress(progress)
                    }
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            onProgress(1.0f)
            Log.i(TAG, "Model $modelId downloaded successfully to ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception downloading model $modelId", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return false
        }
    }
}
