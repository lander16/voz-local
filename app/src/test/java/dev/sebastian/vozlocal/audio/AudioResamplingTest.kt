package dev.sebastian.vozlocal.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioResamplingTest {

    private fun rms(samples: FloatArray, startFraction: Double = 0.25, endFraction: Double = 0.75): Double {
        val start = (samples.size * startFraction).toInt()
        val end = (samples.size * endFraction).toInt().coerceAtLeast(start + 1)
        var sum = 0.0
        for (i in start until end) {
            sum += samples[i] * samples[i]
        }
        return sqrt(sum / (end - start))
    }

    @Test
    fun antiAliasingFilter_attenuatesStopbandAboveNyquist_at48kHz() {
        val durationSec = 0.5
        val srcRate = 48000
        val sampleCount = (durationSec * srcRate).toInt()

        // 1 kHz pure tone (passband)
        val passbandTone = FloatArray(sampleCount) { i ->
            sin(2.0 * PI * 1000.0 * i / srcRate).toFloat()
        }
        // 10 kHz pure tone (stopband / aliasing zone for 16 kHz target whose Nyquist is 8 kHz)
        val stopbandTone = FloatArray(sampleCount) { i ->
            sin(2.0 * PI * 10000.0 * i / srcRate).toFloat()
        }

        val resampledPassband = BandlimitedResamplingSink.resample(passbandTone, srcRate, 16000)
        val resampledStopband = BandlimitedResamplingSink.resample(stopbandTone, srcRate, 16000)

        val passbandRms = rms(resampledPassband)
        val stopbandRms = rms(resampledStopband)

        // Passband (1 kHz) should preserve amplitude: ideal RMS is 1 / sqrt(2) ≈ 0.7071
        assertEquals("1 kHz passband should retain amplitude", 0.7071, passbandRms, 0.05)

        // Stopband (10 kHz) must be strongly attenuated (> 20 dB suppression relative to passband)
        val suppressionDb = 20.0 * log10(passbandRms / stopbandRms.coerceAtLeast(1e-9))
        assertTrue(
            "10 kHz tone should be suppressed by > 20 dB, but was $suppressionDb dB",
            suppressionDb > 20.0
        )
    }

    @Test
    fun antiAliasingFilter_attenuatesStopbandAboveNyquist_at44_1kHz() {
        val durationSec = 0.5
        val srcRate = 44100
        val sampleCount = (durationSec * srcRate).toInt()

        val passbandTone = FloatArray(sampleCount) { i ->
            sin(2.0 * PI * 1000.0 * i / srcRate).toFloat()
        }
        val stopbandTone = FloatArray(sampleCount) { i ->
            sin(2.0 * PI * 10000.0 * i / srcRate).toFloat()
        }

        val resampledPassband = BandlimitedResamplingSink.resample(passbandTone, srcRate, 16000)
        val resampledStopband = BandlimitedResamplingSink.resample(stopbandTone, srcRate, 16000)

        val passbandRms = rms(resampledPassband)
        val stopbandRms = rms(resampledStopband)

        assertEquals("1 kHz passband should retain amplitude at 44.1 kHz", 0.7071, passbandRms, 0.05)

        val suppressionDb = 20.0 * log10(passbandRms / stopbandRms.coerceAtLeast(1e-9))
        assertTrue(
            "10 kHz tone at 44.1 kHz should be suppressed by > 20 dB, but was $suppressionDb dB",
            suppressionDb > 20.0
        )
    }

    @Test
    fun resampling_producesCorrectSampleCountsMatchingDuration() {
        val testDurations = listOf(0.5, 1.0, 2.5)

        for (duration in testDurations) {
            val expectedSamples = (duration * 16000).toInt()

            // 48000 Hz input
            val samples48k = FloatArray((duration * 48000).toInt()) { 0.1f }
            val out48k = BandlimitedResamplingSink.resample(samples48k, 48000, 16000)
            assertEquals("48 kHz sample count for duration $duration", expectedSamples, out48k.size)

            // 44100 Hz input
            val samples44k = FloatArray((duration * 44100).toInt()) { 0.1f }
            val out44k = BandlimitedResamplingSink.resample(samples44k, 44100, 16000)
            assertEquals("44.1 kHz sample count for duration $duration", expectedSamples, out44k.size)

            // 16000 Hz input (pass-through)
            val samples16k = FloatArray((duration * 16000).toInt()) { 0.1f }
            val out16k = BandlimitedResamplingSink.resample(samples16k, 16000, 16000)
            assertEquals("16 kHz sample count for duration $duration", expectedSamples, out16k.size)
        }
    }

    @Test
    fun resampling_maintainsOutputRangeWithoutClipping() {
        val srcRate = 48000
        val samples = FloatArray(srcRate) { i ->
            sin(2.0 * PI * 440.0 * i / srcRate).toFloat() // peak 1.0
        }

        val resampled = BandlimitedResamplingSink.resample(samples, srcRate, 16000)
        for (sample in resampled) {
            assertTrue("Sample must not exceed [-1.0, 1.0]", sample in -1.0f..1.0f)
        }
    }

    @Test
    fun resampling_chunkedBuffersDoNotIntroduceSeamsOrDiscontinuities() {
        val srcRate = 48000
        val totalSamples = srcRate // 1 second
        val tone = FloatArray(totalSamples) { i ->
            sin(2.0 * PI * 1000.0 * i / srcRate).toFloat()
        }

        // Stream all at once
        val singleSink = BandlimitedResamplingSink(srcRate)
        for (sample in tone) singleSink.add(sample)
        val singleResult = singleSink.toFloatArray()

        // Stream in chunks of 512 samples
        val chunkedSink = BandlimitedResamplingSink(srcRate)
        var offset = 0
        val chunkSize = 512
        while (offset < totalSamples) {
            val length = minOf(chunkSize, totalSamples - offset)
            for (i in 0 until length) {
                chunkedSink.add(tone[offset + i])
            }
            offset += length
        }
        val chunkedResult = chunkedSink.toFloatArray()

        assertEquals("Chunked output must have same size as single stream", singleResult.size, chunkedResult.size)
        for (i in singleResult.indices) {
            assertEquals("Sample at index $i must match across chunk boundaries", singleResult[i], chunkedResult[i], 0.0001f)
        }
    }

    @Test
    fun silenceTrimmer_preservesTrailingPartialFrameAtEndOfSpeech() {
        val sampleRate = 16000
        val frameSize = (sampleRate * 20 / 1000) // 320 samples per 20ms frame
        val partialTailSamples = 17
        val totalSamples = frameSize * 150 + partialTailSamples // 48017 samples (~3 seconds)

        val samples = FloatArray(totalSamples)
        // 1 second of leading silence (first 50 frames = 1000ms), speech starts at frame 50
        // and continues all the way to the very last sample (index 48016)
        val speechStart = frameSize * 50 // frame 50
        for (i in speechStart until totalSamples) {
            samples[i] = if (i % 2 == 0) 0.05f else -0.05f
        }

        val trimmed = AudioSilenceTrimmer.trim(samples, sampleRate)

        // Leading silence must have been trimmed
        assertTrue("Leading silence was trimmed", trimmed.size < samples.size)
        // Trailing partial frame must be preserved up to the very last sample
        assertEquals("The trimmed audio should end with the exact last sample of input", samples.last(), trimmed.last(), 0.0001f)
        // Verify all 17 partial tail samples are present at the end
        val expectedTail = samples.takeLast(partialTailSamples)
        val actualTail = trimmed.takeLast(partialTailSamples)
        for (i in 0 until partialTailSamples) {
            assertEquals("Tail sample $i should be preserved", expectedTail[i], actualTail[i], 0.0001f)
        }
    }

    @Test
    fun silenceTrimmer_pureSilenceReturnsEmptyArray() {
        val sampleRate = 16000
        // 2 seconds of pure silence
        val silentSamples = FloatArray(sampleRate * 2) { 0.0f }
        val trimmed = AudioSilenceTrimmer.trim(silentSamples, sampleRate)

        assertTrue("Pure silence should return empty array", trimmed.isEmpty())
        assertEquals(0, trimmed.size)

        // Low ambient noise below 0.015f threshold should also return empty array
        val ambientNoise = FloatArray(sampleRate * 2) { 0.001f }
        val trimmedNoise = AudioSilenceTrimmer.trim(ambientNoise, sampleRate)
        assertTrue("Low ambient noise should return empty array", trimmedNoise.isEmpty())
    }

    @Test
    fun silenceTrimmer_quietSpeechAboveNoiseThresholdIsPreserved() {
        val sampleRate = 16000
        // 2 seconds with quiet speech: all frames have low RMS, but peak reaches 0.02f (> 0.015f)
        val samples = FloatArray(sampleRate * 2) { 0.0005f }
        // Add a quiet whisper peak at 0.02f
        samples[sampleRate] = 0.02f
        samples[sampleRate + 1] = -0.02f

        val trimmed = AudioSilenceTrimmer.trim(samples, sampleRate)

        assertTrue("Quiet speech above noise threshold must not be dropped", trimmed.isNotEmpty())
        assertEquals("Quiet speech must retain full audio", samples.size, trimmed.size)
    }
}
