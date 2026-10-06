package dev.sebastian.vozlocal.fastconformer

import android.content.Context
import dev.sebastian.vozlocal.moonshine.MoonshineModels
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class FastConformerAsset(val name: String, val sizeBytes: Long, val sha256: String, val url: String)
data class FastConformerModelSpec(val id: String, val assets: List<FastConformerAsset>) {
    val sizeBytes: Long get() = assets.sumOf { it.sizeBytes }
}

/** Immutable provenance for the experimental Spanish CTC export. */
object FastConformerModels {
    const val ID = "fastconformer_es_experimental"
    const val UPSTREAM_MODEL = "nvidia/stt_es_fastconformer_hybrid_large_pc"
    const val DERIVATIVE_MODEL = "krut42/voice-fastconformer-es-ctc-int8"
    const val REVISION = "d7694ab533e189621361a30deb701f189abc7568"
    const val LICENSE = "CC-BY-4.0"
    const val ATTRIBUTION = "NVIDIA; ONNX export by OpenVoiceOS; int8 CTC derivative by krut42"
    const val SAMPLE_RATE = 16_000
    const val FEATURE_DIM = 80
    const val MODEL_SHA256 = "592d3342057253a342aa19cec9937f46645d35d30f52ac427a4ddc72c395a769"
    const val TOKENS_SHA256 = "556118db7946e6636018ef59c9e0537b05660b0398e995d60825081e77625e6a"

    val spec = FastConformerModelSpec(ID, listOf(
        FastConformerAsset(
            "model.int8.onnx", 173_888_284L, MODEL_SHA256,
            "https://huggingface.co/$DERIVATIVE_MODEL/resolve/$REVISION/model.int8.onnx",
        ),
        FastConformerAsset(
            "tokens.txt", 10_776L, TOKENS_SHA256,
            "https://huggingface.co/$DERIVATIVE_MODEL/resolve/$REVISION/tokens.txt",
        ),
    ))

    fun isFastConformer(id: String): Boolean = id == ID

    /** Explicit guard for routes that must remain Whisper-only. */
    fun isWhisperModel(id: String): Boolean = !isFastConformer(id) && !MoonshineModels.isMoonshine(id)

    fun directory(context: Context): File = File(File(context.filesDir, "models"), ID)

    fun verify(directory: File, expected: FastConformerModelSpec = spec): Boolean = try {
        expected.assets.all { asset ->
            val file = File(directory, asset.name)
            file.isFile && file.length() == asset.sizeBytes && sha256(file) == asset.sha256
        } && File(directory, ".verified").takeIf { it.isFile }?.readText() == marker(expected)
    } catch (_: Exception) {
        false
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun marker(bundle: FastConformerModelSpec = spec): String = buildString {
        append("id=").append(bundle.id).append('\n')
        append("revision=").append(REVISION).append('\n')
        append("license=").append(LICENSE).append('\n')
        bundle.assets.forEach { append(it.name).append(':').append(it.sizeBytes).append(':').append(it.sha256).append('\n') }
    }
}
