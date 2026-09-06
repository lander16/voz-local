package dev.sebastian.vozlocal.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioDecoderSafetyTest {

    @Test
    fun decodedSamples_haveAnExplicitFifteenMinuteBudget() {
        assertEquals(15 * 60, MAX_SHARED_AUDIO_DURATION_SECONDS)
        assertEquals(16_000 * 15 * 60, MAX_SHARED_AUDIO_SAMPLES)
    }

    @Test
    fun boundedPcmStorage_rejectsInsteadOfGrowingPastItsLimit() {
        val samples = PrimitiveFloatList(initialCapacity = 1, maxCapacity = 2)
        samples.add(0.25f)
        samples.add(-0.5f)

        val error = assertThrows(AudioDecodingException::class.java) { samples.add(1f) }

        assertEquals("Audio is longer than the 15-minute import limit.", error.message)
        assertArrayEquals(floatArrayOf(0.25f, -0.5f), samples.toFloatArray(), 0f)
    }

    @Test
    fun decodeFailures_haveARecoverableTypedError() {
        val cause = IllegalArgumentException("bad media")
        val error = AudioDecodingException("Unable to read the selected audio file.", cause)

        assertEquals(cause, error.cause)
        assertEquals("Unable to read the selected audio file.", error.message)
    }
}
