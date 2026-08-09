package dev.sebastian.vozlocal.data.repository

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

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

    private val MIN_VALID_BYTES = mapOf(
        "whisper_tiny" to 33_000_000L,
        "whisper_base" to 62_000_000L,
        "whisper_small" to 200_000_000L,
        "whisper_medium" to 650_000_000L,
        "whisper_large_v3_turbo" to 430_000_000L,
        "silero_vad" to 1_000_000L
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
        return isValidDownloadedFile(file, modelId)
    }

    fun isValidDownloadedFile(file: File, modelId: String): Boolean {
        if (!file.exists() || file.name.endsWith(".part")) return false
        return hasValidSize(file, modelId)
    }

    fun hasValidSize(file: File, modelId: String): Boolean {
        val minBytes = MIN_VALID_BYTES[modelId] ?: 1_000_000L
        return file.exists() && file.length() >= minBytes
    }
}

class ModelDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder().build()

    // Real SHA-256 values are not currently pinned for these mutable Hugging Face URLs.
    // Keep unknown hashes as null so logs/results never imply cryptographic verification.
    private val sha256Map: Map<String, String?> = mapOf(
        "whisper_tiny" to null,
        "whisper_base" to null,
        "whisper_small" to null,
        "whisper_medium" to null,
        "whisper_large_v3_turbo" to null,
        "silero_vad" to null
    )

    suspend fun downloadModel(
        modelId: String,
        onProgress: suspend (Float) -> Unit
    ): Boolean {
        val url = ModelUrls.URL_MAP[modelId] ?: return false
        val outputFile = ModelUrls.getModelFile(context, modelId)
        return downloadTo(url, outputFile, modelId, sha256Map[modelId], onProgress)
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
        if (ModelUrls.isValidDownloadedFile(file, "silero_vad")) {
            Log.i(TAG, "VAD model already present at ${file.absolutePath}")
            return file.absolutePath
        }
        val url = ModelUrls.URL_MAP["silero_vad"] ?: return null
        val ok = downloadTo(url, file, "silero_vad", sha256Map["silero_vad"]) { }
        if (!ok) return null
        Log.i(TAG, "VAD model downloaded to ${file.absolutePath}")
        return file.absolutePath
    }

    private suspend fun downloadTo(
        url: String,
        outputFile: File,
        modelId: String,
        expectedSha: String?,
        onProgress: suspend (Float) -> Unit
    ): Boolean {
        val partFile = File(outputFile.parentFile, "${outputFile.name}.${UUID.randomUUID()}.part")
        try {
            outputFile.parentFile?.mkdirs()
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            response.use {
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download from $url: ${response.code}")
                    return false
                }

                val body = response.body ?: return false
                val contentLength = body.contentLength()
                val inputStream: InputStream = body.byteStream()

                val buffer = ByteArray(65536) // 64KB buffer for faster throughput
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastEmittedProgress = -1f

                inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (contentLength > 0) {
                                val progress = (totalBytesRead.toFloat() / contentLength.toFloat()).coerceIn(0.0f, 1.0f)
                                if (progress - lastEmittedProgress >= 0.01f || progress >= 1.0f) {
                                    lastEmittedProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (contentLength > 0 && totalBytesRead != contentLength) {
                    Log.e(TAG, "Incomplete download for ${outputFile.name} (expected=$contentLength, actual=$totalBytesRead)")
                    partFile.delete()
                    return false
                }
            }

            if (!ModelUrls.hasValidSize(partFile, modelId)) {
                Log.e(TAG, "Downloaded ${outputFile.name} failed size/metadata validation (${partFile.length()} bytes)")
                partFile.delete()
                return false
            }

            // Integrity check: verify the downloaded file against the expected SHA-256.
            if (!verifySha256(partFile, expectedSha)) {
                return false
            }

            try {
                Files.move(
                    partFile.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Log.e(TAG, "Atomic move not supported for ${outputFile.absolutePath}", e)
                partFile.delete()
                return false
            }

            onProgress(1.0f)

            Log.i(TAG, "Downloaded ${outputFile.name} successfully to ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception downloading to ${outputFile.absolutePath}", e)
            partFile.delete()
            return false
        }
    }

    /**
     * Verifies [file] against [expected] when a real SHA-256 is pinned. A null expected
     * value explicitly means the model is not cryptographically verified. On mismatch
     * the file is deleted and false is returned.
     */
    internal fun verifySha256(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) {
            Log.w(TAG, "No SHA-256 pinned for ${file.name}; download accepted after transport and size checks only")
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
