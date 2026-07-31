package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "AudioRecorder"
const val SAMPLE_RATE = 16000

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private val pcmOutputStream = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun startRecording(
        scope: CoroutineScope,
        onRmsChanged: ((Float) -> Unit)? = null
    ) {
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

        pcmOutputStream.reset()
        isRecording = true
        audioRecord?.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(minBufferSize / 2)
            val tempByteStream = ByteArrayOutputStream()

            while (isActive && isRecording) {
                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (readCount > 0) {
                    var sumSquares = 0.0
                    for (i in 0 until readCount) {
                        val sample = buffer[i]
                        sumSquares += (sample * sample).toDouble()

                        // Convert short to 2 bytes (little endian)
                        val b0 = (sample.toInt() and 0xFF).toByte()
                        val b1 = ((sample.toInt() shr 8) and 0xFF).toByte()
                        tempByteStream.write(byteArrayOf(b0, b1), 0, 2)
                    }

                    synchronized(pcmOutputStream) {
                        pcmOutputStream.write(tempByteStream.toByteArray())
                    }
                    tempByteStream.reset()

                    // Calculate RMS amplitude for live waveform UI
                    val rms = sqrt(sumSquares / readCount).toFloat()
                    val rmsDb = if (rms > 0) (20 * Math.log10(rms.toDouble())).toFloat() else 0f
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

        val pcmBytes: ByteArray
        synchronized(pcmOutputStream) {
            pcmBytes = pcmOutputStream.toByteArray()
            pcmOutputStream.reset()
        }

        // Convert 16-bit PCM bytes (little endian) to FloatArray (-1.0 to 1.0)
        val numSamples = pcmBytes.size / 2
        val floatSamples = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val low = pcmBytes[i * 2].toInt() and 0xFF
            val high = pcmBytes[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            floatSamples[i] = sample.toShort() / 32768.0f
        }

        return floatSamples
    }

    fun isRecording(): Boolean = isRecording
}
