package dev.sebastian.vozlocal.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import dev.sebastian.vozlocal.fastconformer.FastConformerModels
import dev.sebastian.vozlocal.performance.StopToTextTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Serialized complete-clip inference for the explicitly selected Spanish experimental model. */
class FastConformerEngine internal constructor(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val adapterFactory: FastConformerAdapterFactory = FastConformerAdapterFactory.real(),
) {
    private data class Resident(val path: String, val identity: String, val adapter: FastConformerAdapter)
    private var resident: Resident? = null
    private val nativeMutex = Mutex()
    private val busy = AtomicBoolean(false)

    internal suspend fun transcribe(directory: File, samples: FloatArray, trace: StopToTextTrace? = null): String =
        withContext(Dispatchers.IO) { nativeMutex.withLock {
            require(samples.isNotEmpty() && samples.all { it.isFinite() && it in -1f..1f })
            busy.set(true)
            try {
                val path = directory.canonicalFile.absolutePath
                val identity = FastConformerModels.spec.assets.joinToString("|") { "${it.name}:${it.sizeBytes}:${it.sha256}" }
                var current = resident
                if (current == null || current.path != path || current.identity != identity) {
                    closeResident(trace)
                    val load = trace?.begin("fastconformer_native_init")
                    val adapter = try { adapterFactory.create(directory) } finally { trace?.end(load) }
                    current = Resident(path, identity, adapter)
                    resident = current
                }
                val inference = trace?.begin("fastconformer_inference")
                try {
                    current.adapter.transcribe(samples)
                } catch (error: Throwable) {
                    runCatching { closeResident(trace) }.exceptionOrNull()?.let(error::addSuppressed)
                    throw error
                } finally {
                    trace?.end(inference)
                }
            } finally {
                busy.set(false)
            }
        } }

    suspend fun release() = withContext(NonCancellable + Dispatchers.IO) {
        nativeMutex.withLock { closeResident(trace = null) }
    }

    suspend fun releaseIfIdle(): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        if (!nativeMutex.tryLock()) return@withContext false
        try {
            if (busy.get()) return@withContext false
            closeResident(trace = null)
            true
        } finally {
            nativeMutex.unlock()
        }
    }

    fun isBusy(): Boolean = busy.get()

    private fun closeResident(trace: StopToTextTrace?) {
        val current = resident ?: return
        resident = null
        val close = trace?.begin("fastconformer_native_teardown")
        try { current.adapter.close() } finally { trace?.end(close) }
    }
}

internal interface FastConformerAdapterFactory {
    fun create(directory: File): FastConformerAdapter

    companion object {
        fun real(): FastConformerAdapterFactory = object : FastConformerAdapterFactory {
            override fun create(directory: File): FastConformerAdapter = SherpaFastConformerAdapter(directory)
        }
    }
}

internal interface FastConformerAdapter : AutoCloseable {
    fun transcribe(samples: FloatArray): String
    override fun close()
}

private class SherpaFastConformerAdapter(directory: File) : FastConformerAdapter {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = FastConformerModels.SAMPLE_RATE,
                featureDim = FastConformerModels.FEATURE_DIM,
            ),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = File(directory, "model.int8.onnx").absolutePath),
                tokens = File(directory, "tokens.txt").absolutePath,
                numThreads = 2,
            ),
        ),
    )
    private var closed = false

    override fun transcribe(samples: FloatArray): String {
        check(!closed) { "FastConformer adapter is closed" }
        val stream: OfflineStream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, FastConformerModels.SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            recognizer.release()
        }
    }
}
