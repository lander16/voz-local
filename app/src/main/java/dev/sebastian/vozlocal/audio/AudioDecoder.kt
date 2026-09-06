package dev.sebastian.vozlocal.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "AudioDecoder"
private const val TARGET_SAMPLE_RATE = 16_000

/** Temporary whole-file PCM storage is bounded until the streaming import pipeline exists. */
internal const val MAX_SHARED_AUDIO_DURATION_SECONDS = 15 * 60
internal const val MAX_SHARED_AUDIO_SAMPLES = TARGET_SAMPLE_RATE * MAX_SHARED_AUDIO_DURATION_SECONDS
private const val DEFAULT_INITIAL_SAMPLES = TARGET_SAMPLE_RATE * 30
private const val MIN_INITIAL_SAMPLES = 1_024
private const val MAX_INITIAL_SAMPLES = TARGET_SAMPLE_RATE * 60
private const val DECODER_NO_PROGRESS_TIMEOUT_NANOS = 30_000_000_000L

/** A recoverable error while decoding user-supplied shared audio. */
class AudioDecodingException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** A primitive float list that cannot grow past the shared-audio import budget. */
internal class PrimitiveFloatList(initialCapacity: Int, private val maxCapacity: Int) {
    var array = FloatArray(initialCapacity.coerceIn(1, maxCapacity))
    var size = 0
        private set

    fun add(value: Float) {
        if (size == maxCapacity) {
            throw AudioDecodingException(
                "Audio is longer than the ${MAX_SHARED_AUDIO_DURATION_SECONDS / 60}-minute import limit."
            )
        }
        if (size == array.size) {
            array = array.copyOf((array.size.toLong() * 2L).coerceAtMost(maxCapacity.toLong()).toInt())
        }
        array[size++] = value
    }

    fun toFloatArray(): FloatArray = array.copyOf(size)
}

private interface FloatSink {
    fun add(value: Float)
    fun toFloatArray(): FloatArray
}

private class ListFloatSink(initialCapacity: Int) : FloatSink {
    private val list = PrimitiveFloatList(initialCapacity, MAX_SHARED_AUDIO_SAMPLES)
    override fun add(value: Float) = list.add(value)
    override fun toFloatArray(): FloatArray = list.toFloatArray()
}

private class LinearResamplingSink(srcRate: Int, initialCapacity: Int) : FloatSink {
    private val output = PrimitiveFloatList(initialCapacity, MAX_SHARED_AUDIO_SAMPLES)
    private val step = srcRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
    private var previous = 0f
    private var hasPrevious = false
    private var sourceIndex = 0L
    private var nextOutputPos = 0.0

    init { require(srcRate > 0) { "Source sample rate must be positive." } }

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
    suspend fun decodeToPcm16k(uri: Uri, onProgress: ((Float) -> Unit)? = null): FloatArray =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            var codecStarted = false
            try {
                try {
                    extractor.setDataSource(context, uri, null)
                } catch (e: Exception) {
                    throw AudioDecodingException("Unable to read the selected audio file.", e)
                }
                currentCoroutineContext().ensureActive()

                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    currentCoroutineContext().ensureActive()
                    val trackFormat = extractor.getTrackFormat(i)
                    if ((trackFormat.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                        trackIndex = i
                        format = trackFormat
                        break
                    }
                }
                val inputFormat = format ?: throw AudioDecodingException("The selected file does not contain an audio track.")
                if (trackIndex < 0) throw AudioDecodingException("The selected file does not contain an audio track.")
                extractor.selectTrack(trackIndex)
                val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw AudioDecodingException("The audio track has no MIME type.")
                codec = try {
                    MediaCodec.createDecoderByType(mime)
                } catch (e: Exception) {
                    throw AudioDecodingException("This audio format is not supported on this device.", e)
                }
                try {
                    codec.configure(inputFormat, null, null, 0)
                    codec.start()
                    codecStarted = true
                } catch (e: Exception) {
                    throw AudioDecodingException("Unable to start the audio decoder.", e)
                }

                var sampleRate = inputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
                var channelCount = inputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
                var pcmEncoding = inputFormat.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                validateOutputFormat(sampleRate, channelCount, pcmEncoding)
                val durationUs = inputFormat.longOrDefault(MediaFormat.KEY_DURATION, 0L)
                val estimatedTargetSamples = initialCapacityForDuration(durationUs)
                var pcmSink: FloatSink = createSink(sampleRate, estimatedTargetSamples)
                var outputStarted = false
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastProgressNanos = System.nanoTime()

                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (System.nanoTime() - lastProgressNanos > DECODER_NO_PROGRESS_TIMEOUT_NANOS) {
                        throw AudioDecodingException("Audio decoding stopped making progress. Please try another file.")
                    }
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            currentCoroutineContext().ensureActive()
                            val inputBuffer = codec.getInputBuffer(inIndex)
                                ?: throw AudioDecodingException("Audio decoder returned an invalid input buffer.")
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                                if (durationUs > 0L) {
                                    onProgress?.invoke((presentationTimeUs.toDouble() / durationUs).toFloat().coerceIn(0f, 1f))
                                }
                            }
                            lastProgressNanos = System.nanoTime()
                        }
                    }

                    var outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    while (outIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        currentCoroutineContext().ensureActive()
                        when (outIndex) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val outputFormat = codec.outputFormat
                                val nextSampleRate = outputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                                val nextChannelCount = outputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                                val nextPcmEncoding = outputFormat.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                                validateOutputFormat(nextSampleRate, nextChannelCount, nextPcmEncoding)
                                if (outputStarted && (nextSampleRate != sampleRate || nextChannelCount != channelCount || nextPcmEncoding != pcmEncoding)) {
                                    throw AudioDecodingException("Audio format changed while decoding; this file cannot be imported safely.")
                                }
                                sampleRate = nextSampleRate
                                channelCount = nextChannelCount
                                pcmEncoding = nextPcmEncoding
                                if (!outputStarted) pcmSink = createSink(sampleRate, estimatedTargetSamples)
                                lastProgressNanos = System.nanoTime()
                            }
                            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> lastProgressNanos = System.nanoTime()
                            else -> if (outIndex >= 0) {
                                try {
                                    val outputBuffer = codec.getOutputBuffer(outIndex)
                                        ?: throw AudioDecodingException("Audio decoder returned an invalid output buffer.")
                                    if (info.size > 0) {
                                        val endOffset = info.offset.toLong() + info.size.toLong()
                                        if (info.offset < 0 || endOffset > outputBuffer.capacity()) {
                                            throw AudioDecodingException("Audio decoder returned an invalid output buffer range.")
                                        }
                                        outputBuffer.position(info.offset)
                                        outputBuffer.limit(endOffset.toInt())
                                        appendPcmAsMono(outputBuffer.slice(), pcmEncoding, channelCount, pcmSink)
                                        outputStarted = true
                                    }
                                    lastProgressNanos = System.nanoTime()
                                } finally {
                                    codec.releaseOutputBuffer(outIndex, false)
                                }
                                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    outputDone = true
                                    break
                                }
                            }
                        }
                        outIndex = codec.dequeueOutputBuffer(info, 0)
                    }
                }
                pcmSink.toFloatArray()
            } finally {
                if (codec != null) {
                    if (codecStarted) try { codec.stop() } catch (e: Exception) { Log.w(TAG, "Decoder stop failed during cleanup", e) }
                    try { codec.release() } catch (e: Exception) { Log.w(TAG, "Decoder release failed during cleanup", e) }
                }
                try { extractor.release() } catch (e: Exception) { Log.w(TAG, "Extractor release failed during cleanup", e) }
            }
        }

    private fun createSink(sampleRate: Int, initialCapacity: Int): FloatSink =
        if (sampleRate == TARGET_SAMPLE_RATE) ListFloatSink(initialCapacity) else LinearResamplingSink(sampleRate, initialCapacity)

    private fun validateOutputFormat(sampleRate: Int, channelCount: Int, encoding: Int) {
        if (sampleRate !in 1..384_000 || channelCount !in 1..8) {
            throw AudioDecodingException("The audio file has an invalid sample rate or channel count.")
        }
        if (encoding !in SUPPORTED_PCM_ENCODINGS) {
            throw AudioDecodingException("The audio decoder returned an unsupported PCM format.")
        }
    }

    private fun initialCapacityForDuration(durationUs: Long): Int {
        if (durationUs <= 0L) return DEFAULT_INITIAL_SAMPLES
        val boundedDurationUs = durationUs.coerceAtMost(MAX_SHARED_AUDIO_DURATION_SECONDS * 1_000_000L)
        return ((boundedDurationUs / 1_000_000L) * TARGET_SAMPLE_RATE).toInt()
            .coerceIn(MIN_INITIAL_SAMPLES, MAX_INITIAL_SAMPLES)
    }

    private fun MediaFormat.intOrDefault(key: String, default: Int): Int = if (containsKey(key)) getInteger(key) else default
    private fun MediaFormat.longOrDefault(key: String, default: Long): Long = if (containsKey(key)) getLong(key) else default

    private fun appendPcmAsMono(buffer: ByteBuffer, encoding: Int, channelCount: Int, output: FloatSink) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val channels = channelCount.coerceAtLeast(1)
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val samples = buffer.asShortBuffer()
                repeat(samples.remaining() / channels) { frame ->
                    var sum = 0f
                    repeat(channels) { channel -> sum += samples.get(frame * channels + channel) / 32768f }
                    output.add(sum / channels)
                }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val samples = buffer.asFloatBuffer()
                repeat(samples.remaining() / channels) { frame ->
                    var sum = 0f
                    repeat(channels) { channel -> sum += samples.get(frame * channels + channel).coerceIn(-1f, 1f) }
                    output.add(sum / channels)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> repeat(buffer.remaining() / channels) { frame ->
                var sum = 0f
                repeat(channels) { channel -> sum += ((buffer.get(frame * channels + channel).toInt() and 0xff) - 128) / 128f }
                output.add(sum / channels)
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val bytesPerFrame = channels * 3
                repeat(buffer.remaining() / bytesPerFrame) { frame ->
                    var sum = 0f
                    repeat(channels) { channel ->
                        val offset = frame * bytesPerFrame + channel * 3
                        val raw = (buffer.get(offset).toInt() and 0xff) or ((buffer.get(offset + 1).toInt() and 0xff) shl 8) or (buffer.get(offset + 2).toInt() shl 16)
                        sum += raw / 8_388_608f
                    }
                    output.add(sum / channels)
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val samples = buffer.asIntBuffer()
                repeat(samples.remaining() / channels) { frame ->
                    var sum = 0f
                    repeat(channels) { channel -> sum += samples.get(frame * channels + channel) / 2_147_483_648f }
                    output.add(sum / channels)
                }
            }
        }
    }

    private companion object {
        val SUPPORTED_PCM_ENCODINGS = setOf(
            AudioFormat.ENCODING_PCM_8BIT, AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT
        )
    }
}
