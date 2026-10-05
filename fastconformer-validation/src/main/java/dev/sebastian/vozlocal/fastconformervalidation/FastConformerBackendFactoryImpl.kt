package dev.sebastian.vozlocal.fastconformervalidation

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import java.io.File

internal object FastConformerBackendFactoryImpl : FastConformerBackendFactory {
    override fun create(modelDirectory: File, numThreads: Int): FastConformerBackend {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = FastConformerAdapter.SAMPLE_RATE, featureDim = FastConformerAdapter.FEATURE_DIM),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = File(modelDirectory, "model.int8.onnx").absolutePath),
                tokens = File(modelDirectory, "tokens.txt").absolutePath,
                numThreads = numThreads,
            ),
        )
        return SherpaFastConformerBackend(OfflineRecognizer(config = config))
    }
}

private class SherpaFastConformerBackend(private val recognizer: OfflineRecognizer) : FastConformerBackend {
    override fun transcribe(samples: FloatArray): String {
        val stream: OfflineStream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, FastConformerAdapter.SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}
