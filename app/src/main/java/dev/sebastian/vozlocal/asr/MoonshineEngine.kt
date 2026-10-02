package dev.sebastian.vozlocal.asr

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import android.content.Context
import dev.sebastian.vozlocal.performance.StopToTextTrace
import dev.sebastian.vozlocal.moonshine.MoonshineModels
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

    // Accessed only on `worker`; every directory reaches this engine only after
    // DictationRepository has re-verified the full pinned bundle under its model
    // lifecycle lock. The manifest identity prevents an instance for a different
    // pinned bundle from being reused at the same path.
    private data class Resident(
        val modelId: String,
        val directoryPath: String,
        val bundleIdentity: String,
        val adapter: MoonshineAdapter,
    )
    private var resident: Resident? = null

    internal suspend fun transcribe(
        directory: File,
        modelId: String,
        samples: FloatArray,
        trace: StopToTextTrace? = null,
    ): String {
        require(directory.isDirectory) { "Moonshine model directory does not exist: $directory" }
        require(samples.isNotEmpty()) { "Moonshine clips must not be empty" }
        val architecture = architectureFor(modelId)
        // Copy caller-owned PCM before dispatching it to native code.
        val copy = trace?.begin("moonshine_pcm_request_copy")
        val pcm = try { samples.copyOf() } finally { trace?.end(copy) }
        val dispatch = trace?.begin("moonshine_coroutine_dispatch")
        return withContext(dispatcher) {
            trace?.end(dispatch)
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
                    val queued = trace?.begin("moonshine_native_worker_queue")
                    try {
                        // Submit while holding the admission lock. This makes
                        // admission and queue ordering one atomic operation with
                        // release's barrier submission.
                        worker.execute {
                            trace?.end(queued)
                            var result: String? = null
                            var failure: Throwable? = null
                            try {
                                val nativeAdapter = getOrLoadResident(directory, modelId, architecture, trace)
                                val inference = trace?.begin("moonshine_inference")
                                try { result = nativeAdapter.transcribe(pcm, SAMPLE_RATE) } finally { trace?.end(inference) }
                            } catch (t: Throwable) {
                                failure = t
                                // An inference failure may leave native state unusable. Do
                                // not retain that adapter for a retry.
                                try {
                                    closeResident(trace)
                                } catch (closeFailure: Throwable) {
                                    failure?.addSuppressed(closeFailure)
                                }
                            } finally {
                                val clear = trace?.begin("moonshine_pcm_clear")
                                try { pcm.fill(0f) } finally { trace?.end(clear) }
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
        releaseResident(requireIdle = false)
    }

    /** Evicts the retained adapter only when there is no admitted native request. */
    suspend fun releaseIfIdle(): Boolean = releaseResident(requireIdle = true)

    private suspend fun releaseResident(requireIdle: Boolean): Boolean {
        return withContext(NonCancellable + dispatcher) {
            val signal: CompletableDeferred<Unit>
            val enqueue: Boolean
            synchronized(stateLock) {
                if (requireIdle && (busy.get() || draining)) return@withContext false
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
                        // Queue behind native use before closing. Keep admission blocked
                        // until close completes; the single worker remains reusable.
                        worker.execute {
                            var closeFailure: Throwable? = null
                            try {
                                closeResident(trace = null)
                            } catch (t: Throwable) {
                                closeFailure = t
                            } finally {
                                synchronized(stateLock) {
                                    draining = false
                                    drainSignal = null
                                }
                            }
                            if (closeFailure == null) signal.complete(Unit)
                            else signal.completeExceptionally(closeFailure)
                        }
                    } catch (t: Throwable) {
                        draining = false
                        drainSignal = null
                        signal.completeExceptionally(t)
                    }
                }
            }
            signal.await()
            true
        }
    }

    fun isBusy(): Boolean = busy.get() || synchronized(stateLock) { draining }

    private fun getOrLoadResident(
        directory: File,
        modelId: String,
        architecture: Int,
        trace: StopToTextTrace?,
    ): MoonshineAdapter {
        val path = directory.canonicalFile.absolutePath
        val bundleIdentity = MoonshineModels.model(modelId)?.assets
            ?.joinToString("|") { "${it.name}:${it.sizeBytes}:${it.sha256}" }
            ?: throw IllegalArgumentException("Unsupported Moonshine model id: $modelId")
        val active = resident
        if (active != null && active.modelId == modelId && active.directoryPath == path && active.bundleIdentity == bundleIdentity) {
            trace?.point("moonshine_resident_reused")
            return active.adapter
        }

        closeResident(trace)
        val create = trace?.begin("moonshine_adapter_create")
        val adapter = try { adapterFactory.create() } finally { trace?.end(create) }
        var loaded = false
        try {
            val load = trace?.begin("moonshine_model_load")
            try { adapter.load(directory, architecture) } finally { trace?.end(load) }
            resident = Resident(modelId, path, bundleIdentity, adapter)
            loaded = true
            return adapter
        } finally {
            if (!loaded) {
                val close = trace?.begin("moonshine_native_teardown")
                try { adapter.close() } finally { trace?.end(close) }
            }
        }
    }

    /** Must run on the native worker, after any request using the adapter. */
    private fun closeResident(trace: StopToTextTrace?) {
        val current = resident ?: return
        resident = null
        val close = trace?.begin("moonshine_native_teardown")
        try { current.adapter.close() } finally { trace?.end(close) }
    }

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
