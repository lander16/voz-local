package dev.sebastian.vozlocal.asr

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import android.content.Context
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Experimental, complete-clip Moonshine adapter. It is deliberately not a microphone API. */
class MoonshineEngine(
    context: Context,
    private val adapterFactory: MoonshineAdapterFactory = MoonshineAdapterFactory.real(context.applicationContext),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "moonshine-native").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private val stateLock = Any()
    private var draining = false
    private var drainSignal: CompletableDeferred<Unit>? = null

    suspend fun transcribe(directory: File, modelId: String, samples: FloatArray): String {
        require(directory.isDirectory) { "Moonshine model directory does not exist: $directory" }
        require(samples.isNotEmpty()) { "Moonshine clips must not be empty" }
        val architecture = architectureFor(modelId)
        // Copy caller-owned PCM before dispatching it to native code.
        val pcm = samples.copyOf()
        return withContext(dispatcher) {
            suspendCancellableCoroutine { continuation ->
                // Admission belongs to the cancellable section. If the caller is
                // cancelled before this block runs, no request is admitted and
                // busy cannot be stranded.
                synchronized(stateLock) {
                    if (draining || busy.get()) {
                        continuation.resumeWithException(MoonshineBusyException())
                        return@suspendCancellableCoroutine
                    }
                    busy.set(true)
                    try {
                        // Submit while holding the admission lock. This makes
                        // admission and queue ordering one atomic operation with
                        // release's barrier submission.
                        worker.execute {
                            var requestAdapter: MoonshineAdapter? = null
                            var result: String? = null
                            var failure: Throwable? = null
                            try {
                                val nativeAdapter = adapterFactory.create()
                                requestAdapter = nativeAdapter
                                nativeAdapter.load(directory, architecture)
                                result = nativeAdapter.transcribe(pcm, SAMPLE_RATE)
                            } catch (t: Throwable) {
                                failure = t
                            } finally {
                                try {
                                    requestAdapter?.close()
                                } catch (t: Throwable) {
                                    if (failure == null) failure = t
                                }
                                pcm.fill(0f)
                                synchronized(stateLock) { busy.set(false) }
                            }
                            if (continuation.isActive) {
                                failure?.let { continuation.resumeWithException(it) }
                                    ?: continuation.resume(result.orEmpty())
                            }
                        }
                    } catch (t: Throwable) {
                        busy.set(false)
                        if (continuation.isActive) continuation.resumeWithException(t)
                    }
                }
            }
        }
    }

    suspend fun release() {
        withContext(NonCancellable + dispatcher) {
            val signal: CompletableDeferred<Unit>
            val enqueue: Boolean
            synchronized(stateLock) {
                val existing = drainSignal
                if (existing != null) {
                    signal = existing
                    enqueue = false
                } else {
                    signal = CompletableDeferred()
                    drainSignal = signal
                    draining = true
                    enqueue = true
                }
            }
            if (enqueue) {
                synchronized(stateLock) {
                    try {
                        // A barrier on the single native worker drains the request (and,
                        // importantly, its close()) while keeping the executor reusable.
                        worker.execute {
                            synchronized(stateLock) {
                                draining = false
                                drainSignal = null
                            }
                            signal.complete(Unit)
                        }
                    } catch (t: Throwable) {
                        draining = false
                        drainSignal = null
                        signal.completeExceptionally(t)
                    }
                }
            }
            signal.await()
        }
    }

    fun isBusy(): Boolean = busy.get()

    companion object {
        const val TINY_ES = "moonshine_tiny_es"
        const val SMALL_ES = "moonshine_small_es"
        const val SAMPLE_RATE = 16_000

        fun architectureFor(modelId: String): Int = when (modelId) {
            TINY_ES -> JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING
            // 0.1.5 publishes the Spanish small model under the small streaming
            // architecture; this adapter still uses one complete clip per call.
            SMALL_ES -> JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
            else -> throw IllegalArgumentException("Unsupported Moonshine model id: $modelId")
        }
    }
}

class MoonshineBusyException : IllegalStateException(
    "Moonshine is busy or is being released; wait for the current clip to finish",
)

interface MoonshineAdapterFactory {
    fun create(): MoonshineAdapter

    companion object {
        fun real(@Suppress("UNUSED_PARAMETER") context: Context): MoonshineAdapterFactory =
            object : MoonshineAdapterFactory {
                override fun create(): MoonshineAdapter = RealMoonshineAdapter()
            }
    }
}

interface MoonshineAdapter {
    fun load(directory: File, architecture: Int)
    fun transcribe(samples: FloatArray, sampleRate: Int): String
    fun close()
}

private class RealMoonshineAdapter : MoonshineAdapter {
    private var transcriber: Transcriber? = null

    override fun load(directory: File, architecture: Int) {
        check(transcriber == null) { "Moonshine model is already loaded" }
        val candidate = Transcriber()
        transcriber = candidate
        candidate.loadFromFiles(directory.absolutePath, architecture)
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val result = transcriber?.transcribeWithoutStreaming(samples, sampleRate)
            ?: error("Moonshine model is not loaded")
        return result.lines.joinToString(" ") { it.text }.trim()
    }

    override fun close() {
        transcriber?.close()
        transcriber = null
    }
}
