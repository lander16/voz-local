package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

private const val TAG = "AudioDecoder"
private const val TARGET_SAMPLE_RATE = 16000

/**
 * High-performance primitive float list to prevent JVM Object boxing during MediaCodec decoding.
 */
private class PrimitiveFloatList(initialCapacity: Int = 16000 * 60) {
    var array = FloatArray(initialCapacity)
    var size = 0
        private set

    fun add(value: Float) {
        if (size >= array.size) {
            array = array.copyOf(array.size * 2)
        }
        array[size++] = value
    }

    fun toFloatArray(): FloatArray {
        return array.copyOf(size)
    }
}

class AudioDecoder(private val context: Context) {

    suspend fun decodeToPcm16k(
        uri: Uri,
        onProgress: ((Float) -> Unit)? = null
    ): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting extractor data source", e)
            return@withContext FloatArray(0)
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = trackFormat
                break
            }
        }

        if (trackIndex < 0 || format == null) {
            extractor.release()
            Log.e(TAG, "No audio track found in file")
            return@withContext FloatArray(0)
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else TARGET_SAMPLE_RATE
        val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 1L

        val pcmSampleList = PrimitiveFloatList()
        val info = MediaCodec.BufferInfo()
        var isEOS = false

        while (!isEOS) {
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex)
                if (inputBuffer != null) {
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()

                        if (durationUs > 0) {
                            val prog = (presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                            onProgress?.invoke(prog * 0.5f) // first 50% is decoding
                        }
                    }
                }
            }

            var outIndex = codec.dequeueOutputBuffer(info, 10000)
            while (outIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outIndex)
                if (outputBuffer != null && info.size > 0) {
                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)

                    val shortBuf = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val numSamples = shortBuf.remaining()

                    for (i in 0 until numSamples step channelCount) {
                        val sample = shortBuf.get(i)
                        pcmSampleList.add(sample.toFloat() / 32768.0f)
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
                outIndex = codec.dequeueOutputBuffer(info, 0)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        val decodedSamples = pcmSampleList.toFloatArray()

        // Resample to 16kHz if source sample rate differs
        val finalSamples = if (sampleRate != TARGET_SAMPLE_RATE && sampleRate > 0) {
            resampleLinear(decodedSamples, sampleRate, TARGET_SAMPLE_RATE)
        } else {
            decodedSamples
        }

        return@withContext finalSamples
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, targetRate: Int): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val ratio = srcRate.toDouble() / targetRate.toDouble()
        val outputLen = (input.size / ratio).toInt()
        val output = FloatArray(outputLen)

        for (i in 0 until outputLen) {
            val srcPos = i * ratio
            val index0 = srcPos.toInt()
            val index1 = (index0 + 1).coerceAtMost(input.size - 1)
            val frac = (srcPos - index0).toFloat()
            output[i] = input[index0] * (1.0f - frac) + input[index1] * frac
        }
        return output
    }
}
