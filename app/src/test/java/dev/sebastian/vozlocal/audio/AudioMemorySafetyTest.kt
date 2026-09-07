package dev.sebastian.vozlocal.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory safety, bounded allocation, and copy-elimination tests for audio storage.
 * Covers P04 requirements:
 * - Bounded recording capacity in FastFloatBuffer (30-minute max capacity)
 * - Buffer shrinking after reset for oversized recording sessions
 * - Safe initial capacity clamping for AudioDecoder against corrupt/huge metadata
 * - Overflow-safe capacity doubling arithmetic in PrimitiveFloatList
 */
class AudioMemorySafetyTest {

    @Test
    fun fastFloatBuffer_enforcesMaxCapacityAndThrowsWhenLimitExceeded() {
        val buffer = FastFloatBuffer(initialCapacity = 4, maxCapacity = 10)
        val pcm = ShortArray(6) { 1000 }
        buffer.appendPCM16(pcm, 6)
        assertEquals(6, buffer.size)

        // Appending 5 more samples would reach 11 > 10 (exceeding maximum capacity)
        val exception = assertThrows(AudioRecordingException::class.java) {
            buffer.appendPCM16(pcm, 5)
        }
        assertTrue(
            exception.message!!.contains("maximum budget") || exception.message!!.contains("limit")
        )
        // Buffer contents are unmodified past the failed append
        assertEquals(6, buffer.size)
    }

    @Test
    fun fastFloatBuffer_hasExpectedDefaultCapacityConstants() {
        assertEquals(30 * 60, MAX_RECORDING_DURATION_SECONDS)
        assertEquals(16_000 * 30 * 60, MAX_RECORDING_SAMPLES)
        assertEquals(FastFloatBuffer.MAX_RECORDING_DURATION_SECONDS, MAX_RECORDING_DURATION_SECONDS)
        assertEquals(FastFloatBuffer.MAX_RECORDING_SAMPLES, MAX_RECORDING_SAMPLES)
    }

    @Test
    fun fastFloatBuffer_shrinksOversizedArraysBackToInitialCapacityAfterReset() {
        val initialCapacity = 10
        val buffer = FastFloatBuffer(initialCapacity = initialCapacity, maxCapacity = 100)
        // Append 50 samples so capacity doubles past 4 * initialCapacity (40)
        val pcm = ShortArray(50) { 1000 }
        buffer.appendPCM16(pcm, 50)
        assertTrue(buffer.capacity > initialCapacity * 4)

        buffer.reset()
        assertEquals(0, buffer.size)
        // Before shrink, capacity remains retained on the heap
        assertTrue(buffer.capacity > initialCapacity * 4)

        buffer.shrinkIfOversized(initialCapacity)
        assertEquals(initialCapacity, buffer.capacity)
        assertEquals(0, buffer.size)
    }

    @Test
    fun fastFloatBuffer_doesNotShrinkWhenNotOversized() {
        val initialCapacity = 10
        val buffer = FastFloatBuffer(initialCapacity = initialCapacity, maxCapacity = 100)
        val pcm = ShortArray(20) { 1000 }
        buffer.appendPCM16(pcm, 20)
        val capacityBefore = buffer.capacity
        assertTrue(capacityBefore <= initialCapacity * 4)

        buffer.reset()
        buffer.shrinkIfOversized(initialCapacity)
        // Capacity should not be reallocated when size was within 4x initialCapacity
        assertEquals(capacityBefore, buffer.capacity)
    }

    @Test
    fun audioDecoder_handlesCorruptOrHugeDurationMetadataSafely() {
        // Negative / zero / Long.MIN_VALUE durations default to DEFAULT_INITIAL_SAMPLES
        assertEquals(DEFAULT_INITIAL_SAMPLES, AudioDecoder.initialCapacityForDuration(-1L))
        assertEquals(DEFAULT_INITIAL_SAMPLES, AudioDecoder.initialCapacityForDuration(0L))
        assertEquals(DEFAULT_INITIAL_SAMPLES, AudioDecoder.initialCapacityForDuration(Long.MIN_VALUE))

        // Exaggerated / huge durations clamp to MAX_INITIAL_SAMPLES
        assertEquals(MAX_INITIAL_SAMPLES, AudioDecoder.initialCapacityForDuration(Long.MAX_VALUE))
        assertEquals(MAX_INITIAL_SAMPLES, AudioDecoder.initialCapacityForDuration(Int.MAX_VALUE.toLong()))
        // 100 hours in microseconds (corrupt file header)
        val hundredHoursUs = 100L * 3600L * 1_000_000L
        assertEquals(MAX_INITIAL_SAMPLES, AudioDecoder.initialCapacityForDuration(hundredHoursUs))

        // Tiny duration clamps to MIN_INITIAL_SAMPLES
        assertEquals(MIN_INITIAL_SAMPLES, AudioDecoder.initialCapacityForDuration(10_000L))

        // Reasonable duration scales linearly within bounds
        val tenSecondsUs = 10L * 1_000_000L
        assertEquals(16_000 * 10, AudioDecoder.initialCapacityForDuration(tenSecondsUs))

        // Verify bounds guarantees
        assertTrue(MIN_INITIAL_SAMPLES <= DEFAULT_INITIAL_SAMPLES)
        assertTrue(DEFAULT_INITIAL_SAMPLES <= MAX_INITIAL_SAMPLES)
    }

    @Test
    fun primitiveFloatList_capacityScalingWithOverflowSafeArithmetic() {
        val list = PrimitiveFloatList(initialCapacity = 2, maxCapacity = 10)
        assertEquals(2, list.capacity)
        list.add(1f)
        list.add(2f)
        assertEquals(2, list.capacity)

        // Growing doubles capacity
        list.add(3f)
        assertEquals(4, list.capacity)
        assertEquals(3, list.size)

        list.add(4f)
        list.add(5f)
        assertEquals(8, list.capacity)
        assertEquals(5, list.size)

        // Next growth clamps to maxCapacity (10) instead of doubling to 16
        repeat(4) { list.add(6f) }
        assertEquals(9, list.size)
        assertEquals(10, list.capacity)

        list.add(10f)
        assertEquals(10, list.size)

        // Attempting to exceed maxCapacity throws AudioDecodingException
        val error = assertThrows(AudioDecodingException::class.java) {
            list.add(11f)
        }
        assertTrue(error.message!!.contains("limit"))
    }

    @Test
    fun primitiveFloatList_nextCapacityDoesNotOverflowWithHugeValues() {
        // Int arithmetic (1_500_000_000 * 2) would overflow to negative integer (-1_294_967_296)
        // With overflow-safe Long arithmetic clamped to maxCapacity:
        val scaled = PrimitiveFloatList.nextCapacity(1_500_000_000, Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, scaled)

        // Clamping to maxCapacity
        assertEquals(100, PrimitiveFloatList.nextCapacity(80, 100))
        assertEquals(160, PrimitiveFloatList.nextCapacity(80, 200))
    }

    @Test
    fun primitiveFloatList_initialCapacityBounds() {
        // Negative initial capacity is coerced to at least 1
        val safeNegative = PrimitiveFloatList(initialCapacity = -10, maxCapacity = 10)
        assertEquals(1, safeNegative.capacity)

        // Initial capacity exceeding maxCapacity is coerced to maxCapacity
        val safeExcessive = PrimitiveFloatList(initialCapacity = 100, maxCapacity = 10)
        assertEquals(10, safeExcessive.capacity)
    }
}
