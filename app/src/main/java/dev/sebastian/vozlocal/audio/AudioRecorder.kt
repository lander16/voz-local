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

    fun reset() {
        size = 0
    }
}

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private val floatBuffer = FastFloatBuffer()

    @SuppressLint("MissingPermission")
    fun startRecording(
        scope: CoroutineScope,
        hasRecordPermission: Boolean = true,
        onRmsChanged: ((Float) -> Unit)? = null
    ) {
        if (!hasRecordPermission) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
        if (isRecording) return

        val minBufferSize = max(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            4096
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed!")
            audioRecord = null
            return
        }

        synchronized(floatBuffer) {
            floatBuffer.reset()
        }
        isRecording = true
        audioRecord?.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(minBufferSize / 2)

            while (isActive && isRecording) {
                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1
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
    }

    fun stopRecording(): FloatArray {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null

        synchronized(floatBuffer) {
            val samples = floatBuffer.toFloatArray()
            floatBuffer.reset()
            return samples
        }
    }

    fun isRecording(): Boolean = isRecording

    /**
     * Frees all recorder resources. Safe to call whether or not recording is active.
     */
    fun release() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        synchronized(floatBuffer) {
            floatBuffer.reset()
        }
    }
}
