package dev.sebastian.vozlocal.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
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

private interface FloatSink {
    fun add(value: Float)
    fun toFloatArray(): FloatArray
}

private class ListFloatSink(initialCapacity: Int) : FloatSink {
    private val list = PrimitiveFloatList(initialCapacity.coerceAtLeast(1024))
    override fun add(value: Float) = list.add(value)
    override fun toFloatArray(): FloatArray = list.toFloatArray()
}

private class LinearResamplingSink(srcRate: Int, initialCapacity: Int) : FloatSink {
    private val output = PrimitiveFloatList(initialCapacity.coerceAtLeast(1024))
    private val step = srcRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
    private var previous = 0f
    private var hasPrevious = false
    private var sourceIndex = 0L
    private var nextOutputPos = 0.0

    override fun add(value: Float) {
        if (!hasPrevious) {
            previous = value
            hasPrevious = true
            output.add(value)
            nextOutputPos = step
            sourceIndex = 1L
            return
        }
        while (nextOutputPos <= sourceIndex.toDouble()) {
            val frac = (nextOutputPos - (sourceIndex - 1)).toFloat().coerceIn(0f, 1f)
            output.add(previous * (1f - frac) + value * frac)
            nextOutputPos += step
        }
        previous = value
        sourceIndex++
    }

    override fun toFloatArray(): FloatArray = output.toFloatArray()
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

        var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else TARGET_SAMPLE_RATE
        var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        var pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 1L

        val estimatedTargetSamples = if (durationUs > 0) {
            ((durationUs / 1_000_000.0) * TARGET_SAMPLE_RATE).toInt().coerceAtLeast(1024)
        } else {
            TARGET_SAMPLE_RATE * 60
        }
        var pcmSink: FloatSink = if (sampleRate != TARGET_SAMPLE_RATE && sampleRate > 0) {
            LinearResamplingSink(sampleRate, estimatedTargetSamples)
        } else {
            ListFloatSink(estimatedTargetSamples)
        }
        var outputStarted = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()

                            if (durationUs > 0) {
                                val prog = (presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                                onProgress?.invoke(prog)
                            }
                        }
                    }
                }
            }

            var outIndex = codec.dequeueOutputBuffer(info, 10000)
            while (outIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                when (outIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        if (!outputStarted) {
                            pcmSink = if (sampleRate != TARGET_SAMPLE_RATE && sampleRate > 0) {
                                LinearResamplingSink(sampleRate, estimatedTargetSamples)
                            } else {
                                ListFloatSink(estimatedTargetSamples)
                            }
                        }
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outIndex)
                        if (outputBuffer != null && info.size > 0) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            appendPcmAsMono(outputBuffer.slice(), pcmEncoding, channelCount, pcmSink)
                            outputStarted = true
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                            break
                        }
                    }
                }
                outIndex = codec.dequeueOutputBuffer(info, 0)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        return@withContext pcmSink.toFloatArray()
    }

    private fun appendPcmAsMono(buffer: ByteBuffer, encoding: Int, channelCount: Int, output: FloatSink) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val channels = channelCount.coerceAtLeast(1)
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val samples = buffer.asShortBuffer()
                val frames = samples.remaining() / channels
                for (frame in 0 until frames) {
                    var sum = 0f
                    val base = frame * channels
                    for (channel in 0 until channels) sum += samples.get(base + channel) / 32768f
                    output.add(sum / channels)
                }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val samples = buffer.asFloatBuffer()
                val frames = samples.remaining() / channels
                for (frame in 0 until frames) {
                    var sum = 0f
                    val base = frame * channels
                    for (channel in 0 until channels) sum += samples.get(base + channel).coerceIn(-1f, 1f)
                    output.add(sum / channels)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val frames = buffer.remaining() / channels
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) sum += ((buffer.get(frame * channels + channel).toInt() and 0xff) - 128) / 128f
                    output.add(sum / channels)
                }
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val bytesPerFrame = channels * 3
                val frames = buffer.remaining() / bytesPerFrame
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        val offset = frame * bytesPerFrame + channel * 3
                        val raw = (buffer.get(offset).toInt() and 0xff) or
                            ((buffer.get(offset + 1).toInt() and 0xff) shl 8) or
                            (buffer.get(offset + 2).toInt() shl 16)
                        sum += raw / 8388608f
                    }
                    output.add(sum / channels)
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val samples = buffer.asIntBuffer()
                val frames = samples.remaining() / channels
                for (frame in 0 until frames) {
                    var sum = 0f
                    val base = frame * channels
                    for (channel in 0 until channels) sum += samples.get(base + channel) / 2147483648f
                    output.add(sum / channels)
                }
            }
            else -> Log.w(TAG, "Unsupported PCM encoding from decoder: $encoding")
        }
    }
}
