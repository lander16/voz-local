package dev.sebastian.vozlocal.audio

import kotlin.math.sqrt

/**
 * Conservative RMS-based leading/trailing silence trimmer for Whisper input.
 *
 * The thresholds intentionally favor keeping extra audio over clipping quiet speech:
 * - estimate the noise floor from the first/last few frames,
 * - require only a modest rise above that floor,
 * - keep generous padding around detected speech.
 */
object AudioSilenceTrimmer {
    private const val FRAME_MS = 20
    private const val EDGE_ANALYSIS_MS = 500
    private const val PADDING_MS = 250
    private const val MIN_DURATION_MS = 700
    private const val ABSOLUTE_FLOOR_RMS = 0.0035f
    private const val NOISE_MULTIPLIER = 2.2f
    private const val MIN_TRIM_MS = 120

    fun trim(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): FloatArray {
        if (samples.isEmpty() || sampleRate <= 0) return samples
        if (samples.size < sampleRate * MIN_DURATION_MS / 1000) return samples

        val frameSize = (sampleRate * FRAME_MS / 1000).coerceAtLeast(1)
        val frameCount = samples.size / frameSize
        if (frameCount < 4) return samples

        val rms = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            val start = frame * frameSize
            var sum = 0.0
            for (i in start until start + frameSize) {
                val v = samples[i].toDouble()
                sum += v * v
            }
            rms[frame] = sqrt(sum / frameSize).toFloat()
        }

        val edgeFrames = (sampleRate * EDGE_ANALYSIS_MS / 1000 / frameSize).coerceIn(1, frameCount)
        var noise = 0f
        for (i in 0 until edgeFrames) noise += rms[i]
        for (i in frameCount - edgeFrames until frameCount) noise += rms[i]
        noise /= (edgeFrames * 2)
        val threshold = maxOf(ABSOLUTE_FLOOR_RMS, noise * NOISE_MULTIPLIER)

        var first = 0
        while (first < frameCount && rms[first] < threshold) first++
        var last = frameCount - 1
        while (last >= first && rms[last] < threshold) last--
        if (first >= frameCount || last < first) return samples

        val paddingFrames = sampleRate * PADDING_MS / 1000 / frameSize
        val startSample = ((first - paddingFrames).coerceAtLeast(0)) * frameSize
        val endSample = (((last + paddingFrames + 1).coerceAtMost(frameCount)) * frameSize).coerceAtMost(samples.size)

        val minTrimSamples = sampleRate * MIN_TRIM_MS / 1000
        val leadingTrim = startSample
        val trailingTrim = samples.size - endSample
        if (leadingTrim < minTrimSamples && trailingTrim < minTrimSamples) return samples
        if (endSample <= startSample) return samples
        return samples.copyOfRange(startSample, endSample)
    }
}
