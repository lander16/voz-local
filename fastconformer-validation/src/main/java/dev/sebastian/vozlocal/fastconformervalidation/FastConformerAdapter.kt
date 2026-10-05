package dev.sebastian.vozlocal.fastconformervalidation

import java.io.File

/** Validation-only complete-clip CTC adapter; never used by production routing. */
class FastConformerAdapter internal constructor(
    modelDirectory: File,
    numThreads: Int = 2,
    backendFactory: FastConformerBackendFactory = FastConformerBackendFactory.real(),
    bundleVerifier: (File) -> Boolean = FastConformerModelBundle::verify,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val backend: FastConformerBackend

    init {
        require(numThreads in 1..8)
        require(bundleVerifier(modelDirectory)) { "FastConformer model bundle failed size/SHA-256 verification" }
        backend = backendFactory.create(modelDirectory, numThreads)
    }

    fun transcribe(samples: FloatArray): String = synchronized(lock) {
        check(!closed) { "FastConformer adapter is closed" }
        require(samples.isNotEmpty() && samples.all { it.isFinite() && it in -1f..1f })
        backend.transcribe(samples)
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            backend.close()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
    }
}

internal interface FastConformerBackend : AutoCloseable {
    fun transcribe(samples: FloatArray): String
}

internal interface FastConformerBackendFactory {
    fun create(modelDirectory: File, numThreads: Int): FastConformerBackend

    companion object {
        fun real(): FastConformerBackendFactory = FastConformerBackendFactoryImpl
    }
}
