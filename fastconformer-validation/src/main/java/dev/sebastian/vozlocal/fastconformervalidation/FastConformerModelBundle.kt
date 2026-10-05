package dev.sebastian.vozlocal.fastconformervalidation

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class ModelArtifact(val name: String, val bytes: Long, val sha256: String, val url: String)

object FastConformerModelBundle {
    const val MODEL_ID = "nvidia/stt_es_fastconformer_hybrid_large_pc"
    const val DERIVATIVE_ID = "krut42/voice-fastconformer-es-ctc-int8"
    const val HF_REVISION = "d7694ab533e189621361a30deb701f189abc7568"
    const val SHERPA_VERSION = "v1.13.8"
    const val SHERPA_AAR_SHA256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
    const val LICENSE = "CC-BY-4.0"
    const val ATTRIBUTION = "NVIDIA stt_es_fastconformer_hybrid_large_pc; ONNX export by OpenVoiceOS; int8 CTC derivative by krut42"

    val artifacts = listOf(
        ModelArtifact(
            name = "model.int8.onnx",
            bytes = 173_888_284L,
            sha256 = "592d3342057253a342aa19cec9937f46645d35d30f52ac427a4ddc72c395a769",
            url = "https://huggingface.co/krut42/voice-fastconformer-es-ctc-int8/resolve/$HF_REVISION/model.int8.onnx",
        ),
        ModelArtifact(
            name = "tokens.txt",
            bytes = 10_776L,
            sha256 = "556118db7946e6636018ef59c9e0537b05660b0398e995d60825081e77625e6a",
            url = "https://huggingface.co/krut42/voice-fastconformer-es-ctc-int8/resolve/$HF_REVISION/tokens.txt",
        ),
    )

    fun verify(directory: File, expectedArtifacts: List<ModelArtifact> = artifacts): Boolean = expectedArtifacts.all { artifact ->
        val file = File(directory, artifact.name)
        file.isFile && file.length() == artifact.bytes && sha256(file) == artifact.sha256
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
