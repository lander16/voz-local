package dev.sebastian.vozlocal.data.repository

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

private const val TAG = "ModelDownloader"

object ModelUrls {
    // Use quantized q8_0 models for ~2x faster inference on mobile (reduced memory bandwidth)
    val URL_MAP = mapOf(
        "whisper_tiny" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q8_0.bin",
        "whisper_base" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q8_0.bin",
        "whisper_small" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin",
        "whisper_medium" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q8_0.bin",
        "whisper_large_v3_turbo" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
        "silero_vad" to "https://huggingface.co/ggml-org/whisper.cpp/resolve/main/ggml-silero-v6.2.0.bin"
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

    // TODO: replace with real Hugging Face sha256s
    private val sha256Map: Map<String, String> = mapOf(
        "whisper_tiny" to "placeholder-whisper_tiny",
        "whisper_base" to "placeholder-whisper_base",
        "whisper_small" to "placeholder-whisper_small",
        "whisper_medium" to "placeholder-whisper_medium",
        "whisper_large_v3_turbo" to "placeholder-whisper_large_v3_turbo",
        "silero_vad" to "placeholder-silero_vad"
    )

    suspend fun downloadModel(
        modelId: String,
        onProgress: suspend (Float) -> Unit
    ): Boolean {
        val url = ModelUrls.URL_MAP[modelId] ?: return false
        val outputFile = ModelUrls.getModelFile(context, modelId)
        return downloadTo(url, outputFile, sha256Map[modelId], onProgress)
    }

    /**
     * Absolute path to the Silero VAD model file (distinct name from the ASR
     * models so the two never collide).
     */
    fun vadModelFile(): File {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return File(modelsDir, "ggml-silero-vad.bin")
    }

    /**
     * Downloads the Silero VAD model (~2 MB) if not already present. Returns the
     * absolute local path on success, null on failure.
     */
    suspend fun downloadVadModel(): String? {
        val file = vadModelFile()
        if (file.exists() && file.length() > 0L) {
            Log.i(TAG, "VAD model already present at ${file.absolutePath}")
            return file.absolutePath
        }
        val url = ModelUrls.URL_MAP["silero_vad"] ?: return null
        val ok = downloadTo(url, file, sha256Map["silero_vad"]) { }
        if (!ok) return null
        Log.i(TAG, "VAD model downloaded to ${file.absolutePath}")
        return file.absolutePath
    }

    private suspend fun downloadTo(
        url: String,
        outputFile: File,
        expectedSha: String?,
        onProgress: suspend (Float) -> Unit
    ): Boolean {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to download from $url: ${response.code}")
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

            // Integrity check: verify the downloaded file against the expected SHA-256.
            if (!expectedSha.isNullOrEmpty() && !verifySha256(outputFile, expectedSha)) {
                return false
            }

            Log.i(TAG, "Downloaded ${outputFile.name} successfully to ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception downloading to ${outputFile.absolutePath}", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return false
        }
    }

    /**
     * Verifies [file] against the expected SHA-256. Placeholder hashes (see [sha256Map])
     * skip the check and return true. On a mismatch the file is deleted and false is
     * returned. Extracted from [downloadModel] so it can be unit-tested in isolation.
     */
    internal fun verifySha256(file: File, expected: String): Boolean {
        if (expected.startsWith("placeholder")) {
            Log.w(TAG, "SHA-256 for ${file.name} is a placeholder; skipping integrity check")
            return true
        }
        val actualSha = sha256(file)
        if (actualSha != expected) {
            Log.e(TAG, "SHA-256 mismatch for ${file.name} (expected=$expected, actual=$actualSha)")
            file.delete()
            return false
        }
        Log.i(TAG, "SHA-256 verified for ${file.name}")
        return true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
