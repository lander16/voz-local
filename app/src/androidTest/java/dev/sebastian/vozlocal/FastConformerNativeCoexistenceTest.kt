package dev.sebastian.vozlocal

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import dev.sebastian.vozlocal.moonshine.MoonshineModels
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Same-process native compatibility gate. No transcript is persisted or logged. */
@RunWith(AndroidJUnit4::class)
class FastConformerNativeCoexistenceTest {
    @Test
    fun moonshineAndSherpaDecodeInSameValidationProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "dev.sebastian.vozlocal.validation") {
            "Coexistence test must run in the isolated validation package"
        }
        val fcDir = requireNotNull(context.getExternalFilesDir("validation/fastconformer"))
        val moonshineDir = File(context.filesDir, "validation/moonshine/small-es")
        val pcmFile = File(fcDir, "speech.f32")

        // Fail closed on any staged-input mismatch before loading either native runtime.
        requireSha(File(fcDir, "model.int8.onnx"), FASTCONFORMER_MODEL_SHA256)
        requireSha(File(fcDir, "tokens.txt"), FASTCONFORMER_TOKENS_SHA256)
        requireSha(pcmFile, FIXTURE_PCM_SHA256)
        val moonshineSpec = requireNotNull(MoonshineModels.model("moonshine_small_es"))
        require(moonshineSpec.assets.size == 8)
        moonshineSpec.assets.forEach { asset ->
            val file = File(moonshineDir, asset.name)
            require(file.isFile && file.length() == asset.sizeBytes) { "Invalid Moonshine asset: ${asset.name}" }
            require(sha256(file) == asset.sha256) { "Moonshine asset SHA-256 mismatch: ${asset.name}" }
        }
        val pcmBytes = pcmFile.readBytes()
        require(pcmBytes.size in 4..(10 * 16000 * 4) && pcmBytes.size % 4 == 0)
        val samples = FloatArray(pcmBytes.size / 4)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
        require(samples.all { it.isFinite() && it in -1f..1f })

        val moonshine = Transcriber()
        try {
            moonshine.loadFromFiles(moonshineDir.absolutePath, JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING)
            val firstMoonshine = moonshine.transcribeWithoutStreaming(samples, 16_000, 0).lines
                .joinToString(" ") { it.text }.trim()
            assertTrue("Moonshine returned no text", firstMoonshine.isNotBlank())

            val sherpa = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = File(fcDir, "model.int8.onnx").absolutePath),
                        tokens = File(fcDir, "tokens.txt").absolutePath,
                        numThreads = 2,
                    ),
                ),
            )
            try {
                val stream: OfflineStream = sherpa.createStream()
                try {
                    stream.acceptWaveform(samples, 16_000)
                    sherpa.decode(stream)
                    assertTrue("FastConformer returned no text", sherpa.getResult(stream).text.isNotBlank())
                } finally {
                    stream.release()
                }
            } finally {
                sherpa.release()
            }

            // Confirm loading and using sherpa did not invalidate Moonshine's live native instance.
            val secondMoonshine = moonshine.transcribeWithoutStreaming(samples, 16_000, 0).lines
                .joinToString(" ") { it.text }.trim()
            assertTrue("Moonshine failed after Sherpa decode", secondMoonshine.isNotBlank())
        } finally {
            moonshine.close()
        }
    }

    private fun requireSha(file: File, expected: String) {
        require(file.isFile) { "Missing validation input: ${file.name}" }
        require(sha256(file) == expected) { "SHA-256 mismatch: ${file.name}" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FASTCONFORMER_MODEL_SHA256 = "592d3342057253a342aa19cec9937f46645d35d30f52ac427a4ddc72c395a769"
        const val FASTCONFORMER_TOKENS_SHA256 = "556118db7946e6636018ef59c9e0537b05660b0398e995d60825081e77625e6a"
        const val FIXTURE_PCM_SHA256 = "23b8db3cdc813839c01bc4d7f7920a780de5f078a91a1fd67184c8acea81f25a"
    }
}
