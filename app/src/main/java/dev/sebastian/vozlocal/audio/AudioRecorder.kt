package dev.sebastian.vozlocal.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "AudioRecorder"
const val SAMPLE_RATE = 16000

/** A terminal capture failure which callers can present as a recoverable microphone error. */
class AudioRecordingException(message: String) : IllegalStateException(message)

/** Small seam around the platform recorder so lifecycle failures are testable without an audio HAL. */
internal interface AudioRecordHandle {
    val isInitialized: Boolean
    fun startRecording()
    fun read(buffer: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int
    fun stop()
    fun release()
}

internal fun interface AudioRecordFactory {
    fun create(source: Int, bufferSizeInBytes: Int): AudioRecordHandle
}

private class PlatformAudioRecord(private val delegate: AudioRecord) : AudioRecordHandle {
    override val isInitialized get() = delegate.state == AudioRecord.STATE_INITIALIZED
    override fun startRecording() = delegate.startRecording()
    override fun read(buffer: ShortArray, offsetInShorts: Int, sizeInShorts: Int) =
        delegate.read(buffer, offsetInShorts, sizeInShorts)
    override fun stop() = delegate.stop()
    override fun release() = delegate.release()
}

// Construction is reached only through startRecording(), which checks the
// caller-supplied permission state and catches a revoked-permission exception.
@SuppressLint("MissingPermission")
private val platformAudioRecordFactory = AudioRecordFactory { source, bufferSize ->
    PlatformAudioRecord(
        AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
    )
}

/** High-performance primitive buffer for raw PCM recording. */
private class FastFloatBuffer(initialCapacity: Int = SAMPLE_RATE * 15) {
    private var buffer = FloatArray(initialCapacity)
    var size = 0
        private set

    fun appendPCM16(shorts: ShortArray, count: Int): Double {
        val requiredCapacity = size + count
        if (requiredCapacity > buffer.size) buffer = buffer.copyOf(max(buffer.size * 2, requiredCapacity))
        var sumSquares = 0.0
        for (i in 0 until count) {
            val sample = shorts[i]
            buffer[size++] = sample / 32768.0f
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        return sumSquares
    }

    fun toFloatArray() = buffer.copyOf(size)

    fun snapshotLast(maxSamples: Int): FloatArray {
        require(maxSamples >= 0) { "maxSamples must be non-negative" }
        val sampleCount = minOf(size, maxSamples)
        return buffer.copyOfRange(size - sampleCount, size)
    }

    fun reset() { size = 0 }
}

/**
 * Records PCM from the microphone.
 *
 * State transitions are short synchronized sections. Potentially blocking platform shutdown and
 * reader joining deliberately happen outside that monitor and on Dispatchers.IO, so callers may
 * safely stop recording from a UI or accessibility-service callback.
 */
class AudioRecorder internal constructor(
    private val recorderFactory: AudioRecordFactory = platformAudioRecordFactory
) {
    private enum class SessionState { IDLE, RECORDING, STOPPING }

    @Volatile private var sessionState = SessionState.IDLE
    @Volatile private var audioRecord: AudioRecordHandle? = null
    @Volatile private var recordingJob: Job? = null
    private var sessionId = 0L
    private val floatBuffer = FastFloatBuffer()
    // A parent scope can be cancelled before a lazily-created reader gets a chance to enter its
    // try/finally block. This independent IO scope performs the one required hardware cleanup in
    // that case, without making the cancelling UI/service thread wait for AudioRecord.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @SuppressLint("MissingPermission")
    fun startRecording(
        scope: CoroutineScope,
        hasRecordPermission: Boolean = true,
        onRmsChanged: ((Float) -> Unit)? = null,
        onRecordingError: ((AudioRecordingException) -> Unit)? = null
    ): Boolean {
        if (!hasRecordPermission) throw SecurityException("RECORD_AUDIO permission not granted")
        if (!scope.isActive) return false

        synchronized(this) {
            if (sessionState != SessionState.IDLE) return false
            val minBufferSize = max(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                4096
            )
            val recorder = createInitializedRecorder(minBufferSize) ?: return false
            try {
                // Do not publish RECORDING until Android has accepted the start request.
                recorder.startRecording()
            } catch (error: Exception) {
                Log.e(TAG, "AudioRecord failed to start", error)
                releaseRecorder(recorder)
                return false
            }

            synchronized(floatBuffer) { floatBuffer.reset() }
            val id = ++sessionId
            audioRecord = recorder
            sessionState = SessionState.RECORDING
            val reader = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                readLoop(id, recorder, minBufferSize / 2, onRmsChanged, onRecordingError)
            }
            recordingJob = reader
            reader.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    cleanupScope.launch { finishReader(id, recorder, null, onRecordingError) }
                }
            }
            reader.start()
            return true
        }
    }

    private fun createInitializedRecorder(bufferSize: Int): AudioRecordHandle? {
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val candidate = try {
                recorderFactory.create(source, bufferSize)
            } catch (error: Exception) {
                Log.w(TAG, "AudioSource $source failed to construct", error)
                continue
            }
            if (candidate.isInitialized) return candidate
            releaseRecorder(candidate)
        }
        Log.e(TAG, "AudioRecord initialization failed across all audio sources")
        return null
    }

    private suspend fun readLoop(
        id: Long,
        recorder: AudioRecordHandle,
        bufferSizeInShorts: Int,
        onRmsChanged: ((Float) -> Unit)?,
        onRecordingError: ((AudioRecordingException) -> Unit)?
    ) {
        var failure: AudioRecordingException? = null
        try {
            val buffer = ShortArray(bufferSizeInShorts)
            while (currentCoroutineContext().isActive && sessionState == SessionState.RECORDING) {
                when (val readCount = recorder.read(buffer, 0, buffer.size)) {
                    in 1..Int.MAX_VALUE -> {
                        val sumSquares = synchronized(floatBuffer) { floatBuffer.appendPCM16(buffer, readCount) }
                        val rms = sqrt(sumSquares / readCount).toFloat()
                        val rmsDb = if (rms > 0f) (20 * kotlin.math.log10(rms.toDouble())).toFloat() else 0f
                        onRmsChanged?.invoke((rmsDb / 90f).coerceIn(0.05f, 1.0f))
                    }
                    0 -> kotlinx.coroutines.delay(10) // Prevent a defective implementation spinning a CPU core.
                    else -> {
                        failure = AudioRecordingException("Microphone capture failed (AudioRecord error $readCount)")
                        break
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = AudioRecordingException(
                "Microphone capture stopped unexpectedly: ${error.message ?: error.javaClass.simpleName}"
            )
        } finally {
            finishReader(id, recorder, failure, onRecordingError)
        }
    }

    private fun finishReader(
        id: Long,
        recorder: AudioRecordHandle,
        failure: AudioRecordingException?,
        onRecordingError: ((AudioRecordingException) -> Unit)?
    ) {
        val ownsUnexpectedShutdown = synchronized(this) {
            if (id != sessionId || audioRecord !== recorder || sessionState == SessionState.STOPPING) false
            else {
                sessionState = SessionState.STOPPING
                audioRecord = null
                recordingJob = null
                true
            }
        }
        if (!ownsUnexpectedShutdown) return

        // The reader is already on Dispatchers.IO. Always release even if stop fails.
        stopAndRelease(recorder)
        synchronized(floatBuffer) { floatBuffer.reset() }
        synchronized(this) {
            if (id == sessionId && sessionState == SessionState.STOPPING) sessionState = SessionState.IDLE
        }
        failure?.let { onRecordingError?.invoke(it) }
    }

    /** Stops capture, awaits the reader off the main thread, and returns the final PCM once. */
    suspend fun stopRecording(): FloatArray = finishSession(keepSamples = true)

    /** Stops capture without allocating a full PCM copy. */
    suspend fun discardRecording() { finishSession(keepSamples = false) }

    private suspend fun finishSession(keepSamples: Boolean): FloatArray {
        val session = synchronized(this) {
            if (sessionState != SessionState.RECORDING) return@synchronized null
            sessionState = SessionState.STOPPING
            Triple(sessionId, audioRecord, recordingJob).also {
                audioRecord = null
                recordingJob = null
            }
        } ?: return FloatArray(0)

        withContext(Dispatchers.IO) {
            session.second?.let(::stopAndRelease)
            session.third?.join()
        }
        val samples = synchronized(floatBuffer) {
            (if (keepSamples) floatBuffer.toFloatArray() else FloatArray(0)).also { floatBuffer.reset() }
        }
        synchronized(this) {
            if (session.first == sessionId && sessionState == SessionState.STOPPING) sessionState = SessionState.IDLE
        }
        return samples
    }

    private fun stopAndRelease(recorder: AudioRecordHandle) {
        try {
            recorder.stop()
        } catch (error: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", error)
        } finally {
            releaseRecorder(recorder)
        }
    }

    private fun releaseRecorder(recorder: AudioRecordHandle) {
        try {
            recorder.release()
        } catch (error: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", error)
        }
    }

    fun snapshotRecording(maxSamples: Int = Int.MAX_VALUE): FloatArray = synchronized(floatBuffer) {
        floatBuffer.snapshotLast(maxSamples)
    }

    fun isRecording(): Boolean = sessionState == SessionState.RECORDING

    /** Frees recorder resources without blocking the caller's thread. */
    suspend fun release() = discardRecording()
}
