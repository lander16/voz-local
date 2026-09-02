package dev.sebastian.vozlocal.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "AudioRecorder"
const val SAMPLE_RATE = 16000

/**
 * High-performance, zero-allocation primitive float buffer for raw PCM recording.
 * Avoids Java Object boxing and byte array stream conversions for smooth 60fps recording.
 */
private class FastFloatBuffer(initialCapacity: Int = SAMPLE_RATE * 15) {
    private var buffer = FloatArray(initialCapacity)
    var size = 0
        private set

    fun appendPCM16(shorts: ShortArray, count: Int): Double {
        val requiredCapacity = size + count
        if (requiredCapacity > buffer.size) {
            var newCap = buffer.size * 2
            if (newCap < requiredCapacity) newCap = requiredCapacity
            buffer = buffer.copyOf(newCap)
        }

        var sumSquares = 0.0
        for (i in 0 until count) {
            val sample = shorts[i]
            val floatVal = sample / 32768.0f
            buffer[size++] = floatVal
            sumSquares += (sample.toDouble() * sample.toDouble())
        }
        return sumSquares
    }

    fun toFloatArray(): FloatArray {
        return buffer.copyOf(size)
    }

    fun snapshotLast(maxSamples: Int): FloatArray {
        require(maxSamples >= 0) { "maxSamples must be non-negative" }
        val sampleCount = minOf(size, maxSamples)
        return buffer.copyOfRange(size - sampleCount, size)
    }

    fun reset() {
        size = 0
    }
}

/**
 * Locking discipline:
 *  - State transitions (`isRecording`, `audioRecord`, `recordingJob`) are guarded by
 *    `synchronized(this)`. Kotlin's intrinsic monitors are reentrant, so a call that
 *    arrives on a thread already holding the monitor (e.g. the accessibility service
 *    posting back to the main thread) is safe.
 *  - The IO reader thread reads `isRecording` and `audioRecord` directly inside its
 *    `while` loop; both are `@Volatile` so it always sees fresh writes without holding
 *    the monitor.
 *  - The float buffer is independently synchronized on its own monitor so the lock is
 *    held only for the (cheap) state mutations, never while draining PCM.
 */
class AudioRecorder {
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var recordingJob: Job? = null
    @Volatile private var isRecording = false
    private val floatBuffer = FastFloatBuffer()

    @SuppressLint("MissingPermission")
    fun startRecording(
        scope: CoroutineScope,
        hasRecordPermission: Boolean = true,
        onRmsChanged: ((Float) -> Unit)? = null
    ): Boolean {
        if (!hasRecordPermission) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        synchronized(this) {
            if (isRecording) return false

            val minBufferSize = max(
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ),
                4096
            )

            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
            var initializedRecord: AudioRecord? = null
            for (source in sources) {
                try {
                    val candidate = AudioRecord(
                        source,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufferSize
                    )
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        initializedRecord = candidate
                        break
                    } else {
                        candidate.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioSource $source failed: ${e.message}")
                }
            }

            if (initializedRecord == null) {
                Log.e(TAG, "AudioRecord initialization failed across all audio sources!")
                audioRecord = null
                return false
            }
            audioRecord = initializedRecord

            synchronized(floatBuffer) {
                floatBuffer.reset()
            }
            isRecording = true
            audioRecord?.startRecording()

            // Keep one stable native recorder reference for the lifetime of this reader.
            // `stopRecording()` stops it before joining this job, which unblocks a
            // pending blocking read without allowing a new session to swap the field
            // underneath the reader.
            val recorder = audioRecord ?: return false
            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(minBufferSize / 2)

                while (isActive && isRecording) {
                    val readCount = recorder.read(buffer, 0, buffer.size)
                    if (readCount > 0) {
                        val sumSquares: Double
                        synchronized(floatBuffer) {
                            sumSquares = floatBuffer.appendPCM16(buffer, readCount)
                        }

                        // Calculate RMS amplitude for live waveform UI
                        val rms = sqrt(sumSquares / readCount).toFloat()
                        val rmsDb = if (rms > 0) (20 * kotlin.math.log10(rms.toDouble())).toFloat() else 0f
                        val normalizedAmp = (rmsDb / 90f).coerceIn(0.05f, 1.0f)
                        onRmsChanged?.invoke(normalizedAmp)
                    }
                }
            }
            return true
        }
    }

    fun stopRecording(): FloatArray {
        synchronized(this) {
            isRecording = false
            val readerJob = recordingJob
            recordingJob = null

            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioRecord", e)
            }
            audioRecord = null

            // Do not snapshot/reset the shared PCM buffer until the reader has
            // completely exited. A cancelled coroutine can still be returning from
            // AudioRecord.read(); without this join it could append one final block
            // after the snapshot, losing the end of an utterance (or leaking it into
            // the next session).
            readerJob?.cancel()
            runBlocking { readerJob?.join() }

            synchronized(floatBuffer) {
                val samples = floatBuffer.toFloatArray()
                floatBuffer.reset()
                return samples
            }
        }
    }

    /**
     * Returns a point-in-time copy of the raw PCM accumulated by the active session.
     *
     * This does not stop capture, trim silence, or consume samples. Callers implementing
     * opt-in streaming can request only their rolling window via [maxSamples], avoiding an
     * ever-growing copy for long recordings. The returned array never aliases the recorder's
     * internal buffer and is therefore safe to transcribe or mutate on another thread.
     *
     * When no session has produced audio yet this returns an empty array. The final, trimmed
     * recording remains available through [stopRecording] exactly as before.
     */
    fun snapshotRecording(maxSamples: Int = Int.MAX_VALUE): FloatArray {
        synchronized(floatBuffer) {
            return floatBuffer.snapshotLast(maxSamples)
        }
    }

    fun isRecording(): Boolean = isRecording

    /**
     * Frees all recorder resources. Safe to call whether or not recording is active.
     */
    fun release() {
        synchronized(this) {
            isRecording = false
            val readerJob = recordingJob
            recordingJob = null

            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioRecord", e)
            }
            audioRecord = null

            // Apply the same ordering as stopRecording: a reader that is just
            // returning from a blocking read must finish before its buffer is reset.
            readerJob?.cancel()
            runBlocking { readerJob?.join() }

            synchronized(floatBuffer) {
                floatBuffer.reset()
            }
        }
    }
}
