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
    // Use quantized q8_0 / q5_1 models for fast mobile inference (optimal memory bandwidth & accuracy)
    val URL_MAP = mapOf(
        "whisper_base" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q8_0.bin",
        "whisper_tiny" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q8_0.bin",
        "whisper_base_en" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q8_0.bin",
        "whisper_small" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin",
        "whisper_small_q5_1" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        "whisper_large_v3_turbo" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
        "whisper_medium" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q8_0.bin",
        "silero_vad" to "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin"
    )

    private val MIN_VALID_BYTES = mapOf(
        "whisper_base" to 62_000_000L,
        "whisper_tiny" to 33_000_000L,
        "whisper_base_en" to 62_000_000L,
        "whisper_small" to 200_000_000L,
        "whisper_small_q5_1" to 140_000_000L,
        "whisper_large_v3_turbo" to 430_000_000L,
        "whisper_medium" to 650_000_000L,
        "silero_vad" to 800_000L
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

    /** True only when a file was checked against this app version's pinned digest. */
    fun isVerifiedDownloadedFile(file: File, modelId: String): Boolean {
        if (!isValidDownloadedFile(file, modelId)) return false
        val expected = ModelDownloader.sha256Map[modelId] ?: return false
        val record = File(file.parentFile, "${file.name}.sha256")
        return record.isFile && runCatching {
            record.readText().trim() == "$expected:${file.length()}"
        }.getOrDefault(false)
    }

    fun hasValidSize(file: File, modelId: String): Boolean {
        val minBytes = MIN_VALID_BYTES[modelId] ?: 1_000_000L
        return file.exists() && file.length() >= minBytes
    }

    fun minimumValidBytes(modelId: String): Long? = MIN_VALID_BYTES[modelId]
}

class ModelDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder().build()

    // SHA-256 verification map: pinned checksums for model security & integrity
    internal val sha256Map: Map<String, String>
        get() = ModelDownloader.sha256Map

    fun verificationLabel(modelId: String): String = if (sha256Map[modelId].isNullOrBlank()) {
        "Verified (Transport & Size)"
    } else {
        "Verified (SHA-256)"
    }

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
    suspend fun downloadVadModel(
        onProgress: suspend (Float) -> Unit = {},
        onContentLength: suspend (Long) -> Unit = {}
    ): String? {
        val file = vadModelFile()
        if (ModelUrls.isVerifiedDownloadedFile(file, "silero_vad")) {
            Log.i(TAG, "VAD model already present at ${file.absolutePath}")
            onContentLength(file.length())
            onProgress(1.0f)
            return file.absolutePath
        }
        deleteStalePartFiles(file)
        val url = ModelUrls.URL_MAP["silero_vad"] ?: return null
        val ok = downloadTo(url, file, "silero_vad", sha256Map["silero_vad"], onProgress, onContentLength)
        if (!ok) return null
        Log.i(TAG, "VAD model downloaded to ${file.absolutePath}")
        return file.absolutePath
    }

    fun deleteVadModel(): Boolean {
        val file = vadModelFile()
        deleteStalePartFiles(file)
        return !file.exists() || file.delete()
    }

    private suspend fun downloadTo(
        url: String,
        outputFile: File,
        modelId: String,
        expectedSha: String?,
        onProgress: suspend (Float) -> Unit,
        onContentLength: suspend (Long) -> Unit = {}
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
                if (contentLength > 0) onContentLength(contentLength)
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
                outputFile.delete()
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
                Log.w(TAG, "Atomic move not supported for ${outputFile.absolutePath}; falling back to replace move", e)
                Files.move(
                    partFile.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

            writeVerificationRecord(outputFile, expectedSha)

            onProgress(1.0f)

            Log.i(TAG, "Downloaded ${outputFile.name} successfully to ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception downloading to ${outputFile.absolutePath}", e)
            partFile.delete()
            return false
        }
    }

    private fun deleteStalePartFiles(outputFile: File) {
        outputFile.parentFile?.listFiles { file ->
            file.isFile && file.name.startsWith(outputFile.name) && file.name.endsWith(".part")
        }?.forEach { stale ->
            if (stale.delete()) {
                Log.i(TAG, "Deleted stale partial download ${stale.absolutePath}")
            }
        }
    }

    /**
     * Verifies a pre-existing model before it is loaded. This upgrades models
     * downloaded by older releases, which did not have a verification record.
     */
    fun verifyExistingModel(modelId: String): Boolean {
        val file = ModelUrls.getModelFile(context, modelId)
        return verifyExistingFile(file, modelId)
    }

    fun verifyExistingVadModel(): Boolean = verifyExistingFile(vadModelFile(), "silero_vad")

    private fun verifyExistingFile(file: File, modelId: String): Boolean {
        if (ModelUrls.isVerifiedDownloadedFile(file, modelId)) return true
        if (!ModelUrls.isValidDownloadedFile(file, modelId)) return false
        val expected = sha256Map[modelId] ?: return false
        if (!verifySha256(file, expected)) return false
        return runCatching {
            writeVerificationRecord(file, expected)
            true
        }.getOrElse {
            Log.e(TAG, "Could not persist verification record for ${file.name}", it)
            false
        }
    }

    private fun writeVerificationRecord(file: File, expectedSha: String?) {
        if (expectedSha.isNullOrBlank()) return
        val record = File(file.parentFile, "${file.name}.sha256")
        val temporary = File(file.parentFile, "${record.name}.${UUID.randomUUID()}.part")
        temporary.writeText("$expectedSha:${file.length()}")
        try {
            Files.move(
                temporary.toPath(),
                record.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), record.toPath(), StandardCopyOption.REPLACE_EXISTING)
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

    internal fun sha256(file: File): String = ModelDownloader.sha256(file)
    internal fun sha256(bytes: ByteArray): String = ModelDownloader.sha256(bytes)

    companion object {
        internal val sha256Map: Map<String, String> = mapOf(
            "whisper_base" to "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9",
            "whisper_tiny" to "c2085835d3f50733e2ff6e4b41ae8a2b8d8110461e18821b09a15c40c42d1cca",
            "whisper_base_en" to "a4d4a0768075e13cfd7e19df3ae2dbc4a68d37d36a7dad45e8410c9a34f8c87e",
            "whisper_small" to "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f",
            "whisper_small_q5_1" to "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
            "whisper_large_v3_turbo" to "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
            "whisper_medium" to "42a1ffcbe4167d224232443396968db4d02d4e8e87e213d3ee2e03095dea6502",
            "silero_vad" to "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987"
        )
        internal val SHA256_MAP = sha256Map

        internal fun sha256(file: File): String {
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

        internal fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
