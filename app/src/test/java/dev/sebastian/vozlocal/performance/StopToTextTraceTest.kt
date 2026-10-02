package dev.sebastian.vozlocal.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopToTextTraceTest {
    @Test fun recordsMonotonicOffsetsForOverlappingStagesWithoutAddingThem() {
        var now = 1_000_000L
        val lines = mutableListOf<String>()
        val trace = StopToTextTrace.forTest(nowNanos = { now }, logger = lines::add)

        now = 11_000_000L
        val outer = trace.begin("outer")
        now = 21_000_000L
        val inner = trace.begin("inner")
        now = 31_000_000L
        trace.point("in_app_result_state_published")
        now = 41_000_000L
        trace.end(inner)
        now = 61_000_000L
        trace.end(outer)
        trace.recordPcmIdentity(floatArrayOf(0.5f))
        now = 71_000_000L
        trace.finish("moonshine_small_es", "overlay_action_accepted")
        trace.finish("moonshine_small_es", "ignored_second_finish")

        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("stop_to_app_state_published=30ms"))
        assertTrue(lines.single().contains("finalized_at=70ms"))
        assertTrue(lines.single().contains("outer:10-60ms"))
        assertTrue(lines.single().contains("inner:20-40ms"))
        assertTrue(lines.single().contains("in_app_result_state_published:30ms"))
        assertTrue(lines.single().contains("outcome=overlay_action_accepted"))
        assertTrue(lines.single().contains("source=test"))
        assertTrue(lines.single().contains("pcm_samples:1"))
        assertTrue(lines.single().contains("pcm_duration_ms:0"))
        assertTrue(lines.single().matches(Regex(".*pcm_sha256_f32le:[0-9a-f]{64}.*")))
        assertTrue(lines.single().contains("spans=["))
        assertTrue(lines.single().contains("points=["))
        assertFalse(lines.single().contains("private transcript"))
    }
}
