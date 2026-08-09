package dev.sebastian.vozlocal.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSilenceTrimmerTest {
    @Test
    fun trim_removesLeadingAndTrailingSilenceWithPadding() {
        val samples = FloatArray(SAMPLE_RATE * 3)
        val speechStart = SAMPLE_RATE
        val speechEnd = SAMPLE_RATE * 2
        for (i in speechStart until speechEnd) samples[i] = if (i % 2 == 0) 0.04f else -0.04f

        val trimmed = AudioSilenceTrimmer.trim(samples)

        assertTrue(trimmed.size < samples.size)
        assertTrue("keeps padding before/after speech", trimmed.size > SAMPLE_RATE)
        assertTrue("does not clip start of speech", trimmed.any { kotlin.math.abs(it) > 0.03f })
    }

    @Test
    fun trim_keepsQuietSpeechAboveLowNoiseFloor() {
        val samples = FloatArray(SAMPLE_RATE * 2) { 0.0005f }
        for (i in SAMPLE_RATE / 2 until SAMPLE_RATE + SAMPLE_RATE / 2) {
            samples[i] = if (i % 2 == 0) 0.006f else -0.006f
        }

        val trimmed = AudioSilenceTrimmer.trim(samples)

        assertTrue(trimmed.size >= SAMPLE_RATE)
        assertTrue(trimmed.any { kotlin.math.abs(it) >= 0.006f })
    }

    @Test
    fun trim_shortClipIsUnchanged() {
        val samples = FloatArray(SAMPLE_RATE / 2) { 0f }

        assertEquals(samples.size, AudioSilenceTrimmer.trim(samples).size)
    }
}
