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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "AudioDecoder"
internal const val TARGET_SAMPLE_RATE = 16_000

/** Temporary whole-file PCM storage is bounded until the streaming import pipeline exists. */
internal const val MAX_SHARED_AUDIO_DURATION_SECONDS = 15 * 60
internal const val MAX_SHARED_AUDIO_SAMPLES = TARGET_SAMPLE_RATE * MAX_SHARED_AUDIO_DURATION_SECONDS
internal const val DEFAULT_INITIAL_SAMPLES = TARGET_SAMPLE_RATE * 30
internal const val MIN_INITIAL_SAMPLES = 1_024
internal const val MAX_INITIAL_SAMPLES = TARGET_SAMPLE_RATE * 60
private const val DECODER_NO_PROGRESS_TIMEOUT_NANOS = 30_000_000_000L

/** A recoverable error while decoding user-supplied shared audio. */
class AudioDecodingException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** A primitive float list that cannot grow past the shared-audio import budget. */
internal class PrimitiveFloatList(initialCapacity: Int, private val maxCapacity: Int) {
    init {
        require(maxCapacity > 0) { "maxCapacity must be positive." }
    }

    var array = FloatArray(initialCapacity.coerceIn(1, maxCapacity))
    var size = 0
        private set

    val capacity: Int get() = array.size

    fun add(value: Float) {
        if (size == maxCapacity) {
            throw AudioDecodingException(
                "Audio is longer than the ${MAX_SHARED_AUDIO_DURATION_SECONDS / 60}-minute import limit."
            )
        }
        if (size == array.size) {
            val newCapacity = nextCapacity(array.size, maxCapacity)
            array = array.copyOf(newCapacity)
        }
        array[size++] = value
    }

    fun toFloatArray(): FloatArray = array.copyOf(size)

    internal companion object {
        internal fun nextCapacity(currentCapacity: Int, maxCapacity: Int): Int {
            return (currentCapacity.toLong() * 2L)
                .coerceAtLeast(currentCapacity.toLong() + 1L)
                .coerceAtMost(maxCapacity.toLong())
                .toInt()
        }
    }
}

internal interface FloatSink {
    fun add(value: Float)
    fun toFloatArray(): FloatArray
}

internal class ListFloatSink(initialCapacity: Int) : FloatSink {
    private val list = PrimitiveFloatList(initialCapacity, MAX_SHARED_AUDIO_SAMPLES)
    override fun add(value: Float) = list.add(value)
    override fun toFloatArray(): FloatArray = list.toFloatArray()
}

/**
 * Band-limited anti-aliasing resampling sink.
 *
 * When downsampling audio (e.g. 48000 Hz or 44100 Hz to 16000 Hz), Nyquist frequency is
 * targetRate / 2 (8000 Hz). Frequencies above 8000 Hz must be low-pass filtered to prevent
 * aliasing into the target 0-8 kHz speech spectrum.
 *
 * Employs a rate-scaled windowed-sinc FIR low-pass filter with Hann window. Filter coefficients
 * are normalized to guarantee exact unity DC gain, ensuring output scaling stays within [-1.0, 1.0]
 * without clipping or DC offset. Filter history is maintained across input chunks to prevent
 * phase clicks or seams at buffer boundaries.
 */
internal class BandlimitedResamplingSink(
    val srcRate: Int,
    initialCapacity: Int = DEFAULT_INITIAL_SAMPLES,
    val targetRate: Int = TARGET_SAMPLE_RATE
) : FloatSink {
    init {
        require(srcRate > 0) { "Source sample rate must be positive." }
        require(targetRate > 0) { "Target sample rate must be positive." }
    }

    private val output = PrimitiveFloatList(initialCapacity, MAX_SHARED_AUDIO_SAMPLES)
    private val step = srcRate.toDouble() / targetRate.toDouble()

    // Four source-rate periods across the transition band give >45 dB rejection
    // at target Nyquist. A fixed 31 taps was insufficient at common input rates.
    private val transitionHz = minOf(800.0, minOf(targetRate, srcRate) * 0.05)
    private val numTaps = (kotlin.math.ceil(4.0 * srcRate / transitionHz).toInt() or 1)
    private val filterCoeffs: FloatArray

    init {
        val nyquist = minOf(targetRate, srcRate) / 2.0
        val cutoff = minOf(7500.0, nyquist * 0.9)
        val fc = (cutoff / srcRate).toFloat()

        val m = (numTaps - 1) / 2
        val raw = FloatArray(numTaps)
        var sum = 0.0
        for (i in 0 until numTaps) {
            val n = i - m
            val h = if (n == 0) {
                2f * fc
            } else {
                (sin(2.0 * PI * fc * n) / (PI * n)).toFloat()
            }
            val w = (0.5 - 0.5 * cos(2.0 * PI * i / (numTaps - 1))).toFloat()
            val coeff = h * w
            raw[i] = coeff
            sum += coeff.toDouble()
        }
        filterCoeffs = FloatArray(numTaps) { i ->
            if (sum != 0.0) (raw[i] / sum).toFloat() else raw[i]
        }
    }

    private val history = FloatArray(numTaps + 1)
    private var historyPos = 0
    private var hasPrevious = false
    private var sourceIndex = 0L
    private var nextOutputPos = 0.0

    private fun filterCurrent(offset: Int = 0): Float {
        var sum = 0f
        var idx = (historyPos - 1 - offset + history.size) % history.size
        for (i in 0 until numTaps) {
            sum += filterCoeffs[i] * history[idx]
            idx--
            if (idx < 0) idx += history.size
        }
        return sum
    }

    override fun add(value: Float) {
        if (srcRate == targetRate) {
            output.add(value.coerceIn(-1f, 1f))
            return
        }

        if (!hasPrevious) {
            history.fill(value)
            hasPrevious = true
            output.add(value.coerceIn(-1f, 1f))
            nextOutputPos = step
            sourceIndex = 1L
            return
        }

        history[historyPos] = value
        historyPos = (historyPos + 1) % history.size

        while (nextOutputPos <= sourceIndex.toDouble()) {
            val frac = (nextOutputPos - (sourceIndex - 1)).toFloat().coerceIn(0f, 1f)
            // Evaluate only samples used by the decimator. At integer ratios
            // (48k -> 16k) this avoids filtering two discarded frames out of three.
            val interpolated = if (frac >= 1f) filterCurrent()
                else filterCurrent(1) * (1f - frac) + filterCurrent() * frac
            output.add(interpolated.coerceIn(-1f, 1f))
            nextOutputPos += step
        }
        sourceIndex++
    }

    override fun toFloatArray(): FloatArray = output.toFloatArray()

    companion object {
        fun resample(samples: FloatArray, srcRate: Int, targetRate: Int = TARGET_SAMPLE_RATE): FloatArray {
            if (srcRate == targetRate) return samples.copyOf()
            val capacity = ((samples.size.toDouble() * targetRate) / srcRate).toInt() + 100
            val sink = BandlimitedResamplingSink(srcRate, capacity, targetRate)
            for (sample in samples) {
                sink.add(sample)
            }
            return sink.toFloatArray()
        }
    }
}

internal typealias LinearResamplingSink = BandlimitedResamplingSink

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
        if (sampleRate == TARGET_SAMPLE_RATE) ListFloatSink(initialCapacity) else BandlimitedResamplingSink(sampleRate, initialCapacity)

    private fun validateOutputFormat(sampleRate: Int, channelCount: Int, encoding: Int) {
        if (sampleRate !in 1..384_000 || channelCount !in 1..8) {
            throw AudioDecodingException("The audio file has an invalid sample rate or channel count.")
        }
        if (encoding !in SUPPORTED_PCM_ENCODINGS) {
            throw AudioDecodingException("The audio decoder returned an unsupported PCM format.")
        }
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

    internal companion object {
        val SUPPORTED_PCM_ENCODINGS = setOf(
            AudioFormat.ENCODING_PCM_8BIT, AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        internal fun initialCapacityForDuration(durationUs: Long): Int {
            if (durationUs <= 0L) return DEFAULT_INITIAL_SAMPLES
            val estimatedSamples = (durationUs.toDouble() / 1_000_000.0) * TARGET_SAMPLE_RATE
            if (estimatedSamples.isNaN() || estimatedSamples.isInfinite()) {
                return DEFAULT_INITIAL_SAMPLES
            }
            return estimatedSamples.toLong()
                .coerceIn(MIN_INITIAL_SAMPLES.toLong(), MAX_INITIAL_SAMPLES.toLong())
                .toInt()
        }
    }
}
