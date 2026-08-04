package dev.sebastian.vozlocal

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPcmConverterTest {

    @Test
    fun pcmBytesToFloatArrayConversion_isAccurate() {
        // Prepare 16-bit PCM sample bytes (little endian)
        // Sample 1: 0 -> 0x00, 0x00
        // Sample 2: 32767 (Max positive) -> 0xFF, 0x7F
        // Sample 3: -32768 (Min negative) -> 0x00, 0x80
        val pcmBytes = byteArrayOf(
            0x00.toByte(), 0x00.toByte(),
            0xFF.toByte(), 0x7F.toByte(),
            0x00.toByte(), 0x80.toByte()
        )

        val numSamples = pcmBytes.size / 2
        val floatSamples = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val low = pcmBytes[i * 2].toInt() and 0xFF
            val high = pcmBytes[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            floatSamples[i] = sample.toShort() / 32768.0f
        }

        assertEquals(3, floatSamples.size)
        assertEquals(0.0f, floatSamples[0], 0.001f)
        assertEquals(1.0f, floatSamples[1], 0.001f)
        assertEquals(-1.0f, floatSamples[2], 0.001f)
    }

    @Test
    fun rmsAmplitudeCalculation_isCorrect() {
        val floatSamples = floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f)
        var sumSquare = 0.0
        for (s in floatSamples) {
            sumSquare += (s * s).toDouble()
        }
        val rms = Math.sqrt(sumSquare / floatSamples.size).toFloat()

        assertEquals(0.5f, rms, 0.001f)
    }
}
