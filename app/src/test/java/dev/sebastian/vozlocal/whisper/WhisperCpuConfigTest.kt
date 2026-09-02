package dev.sebastian.vozlocal.whisper

import com.whispercpp.whisper.WhisperCpuConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperCpuConfigTest {

    private val threadProperty = "vozlocal.whisper.threads"

    @After
    fun tearDown() {
        System.clearProperty(threadProperty)
    }

    @Test
    fun adaptiveThreadCount_scales_with_cpu_cores() {
        // 8-core modern mobile SoC (e.g. Snapdragon 8 Gen 2/3, Dimensity, Tensor)
        assertEquals(4, WhisperCpuConfig.adaptiveThreadCount(available = 8, highPerf = 4))
        assertEquals(4, WhisperCpuConfig.adaptiveThreadCount(available = 8, highPerf = 6))
        assertEquals(5, WhisperCpuConfig.maxThreadCap(available = 8))

        // 6-core midrange SoC
        assertEquals(4, WhisperCpuConfig.adaptiveThreadCount(available = 6, highPerf = 4))
        assertEquals(4, WhisperCpuConfig.maxThreadCap(available = 6))

        // 4-core low-end SoC
        assertEquals(2, WhisperCpuConfig.adaptiveThreadCount(available = 4, highPerf = 2))
        assertEquals(4, WhisperCpuConfig.maxThreadCap(available = 4))

        // 2-core / single-core devices
        assertEquals(1, WhisperCpuConfig.adaptiveThreadCount(available = 2, highPerf = 1))
        assertEquals(1, WhisperCpuConfig.adaptiveThreadCount(available = 1, highPerf = 1))
        assertEquals(4, WhisperCpuConfig.maxThreadCap(available = 2))
    }

    @Test
    fun threadAllocation_on_8_core_soc_allocates_expected_threads() {
        val base = 4
        val maxCap = 5

        // Base models allow up to 4 threads (expanded from previous cap of 3)
        val baseParams1 = WhisperParams(modelIdHint = "whisper_base")
        val baseParams2 = WhisperParams(modelIdHint = "base")
        assertEquals(4, WhisperCpuConfig.threadCountFor(baseParams1, base = base, maxCap = maxCap))
        assertEquals(4, WhisperCpuConfig.threadCountFor(baseParams2, base = base, maxCap = maxCap))

        // Tiny models allow up to 3 threads (expanded from previous cap of 2)
        val tinyParams1 = WhisperParams(modelIdHint = "whisper_tiny")
        val tinyParams2 = WhisperParams(modelIdHint = "tiny")
        assertEquals(3, WhisperCpuConfig.threadCountFor(tinyParams1, base = base, maxCap = maxCap))
        assertEquals(3, WhisperCpuConfig.threadCountFor(tinyParams2, base = base, maxCap = maxCap))

        // Medium and Large models use (base + 1).coerceAtMost(maxThreadCap) -> 5 threads
        val mediumParams = WhisperParams(modelIdHint = "whisper_medium")
        val largeParams = WhisperParams(modelIdHint = "whisper_large_v3_turbo")
        assertEquals(5, WhisperCpuConfig.threadCountFor(mediumParams, base = base, maxCap = maxCap))
        assertEquals(5, WhisperCpuConfig.threadCountFor(largeParams, base = base, maxCap = maxCap))

        // Unspecified / default hint falls back to base threads
        val defaultParams = WhisperParams(modelIdHint = null)
        assertEquals(4, WhisperCpuConfig.threadCountFor(defaultParams, base = base, maxCap = maxCap))
    }

    @Test
    fun threadAllocation_on_4_core_soc_respects_headroom() {
        val base = 2
        val maxCap = 4

        // Base models capped at base (2 <= 4)
        val baseParams = WhisperParams(modelIdHint = "whisper_base")
        assertEquals(2, WhisperCpuConfig.threadCountFor(baseParams, base = base, maxCap = maxCap))

        // Tiny models capped at base (2 <= 3)
        val tinyParams = WhisperParams(modelIdHint = "whisper_tiny")
        assertEquals(2, WhisperCpuConfig.threadCountFor(tinyParams, base = base, maxCap = maxCap))

        // Medium and Large models get base + 1 = 3
        val mediumParams = WhisperParams(modelIdHint = "whisper_medium")
        val largeParams = WhisperParams(modelIdHint = "whisper_large")
        assertEquals(3, WhisperCpuConfig.threadCountFor(mediumParams, base = base, maxCap = maxCap))
        assertEquals(3, WhisperCpuConfig.threadCountFor(largeParams, base = base, maxCap = maxCap))
    }

    @Test
    fun threadAllocation_on_6_core_soc() {
        val base = 4
        val maxCap = 4

        val baseParams = WhisperParams(modelIdHint = "whisper_base")
        assertEquals(4, WhisperCpuConfig.threadCountFor(baseParams, base = base, maxCap = maxCap))

        val tinyParams = WhisperParams(modelIdHint = "whisper_tiny")
        assertEquals(3, WhisperCpuConfig.threadCountFor(tinyParams, base = base, maxCap = maxCap))

        // Medium/large capped at maxCap = 4
        val largeParams = WhisperParams(modelIdHint = "large")
        assertEquals(4, WhisperCpuConfig.threadCountFor(largeParams, base = base, maxCap = maxCap))
    }

    @Test
    fun system_property_override_takes_precedence() {
        System.setProperty(threadProperty, "2")
        val params = WhisperParams(modelIdHint = "whisper_large_v3_turbo")
        assertEquals(2, WhisperCpuConfig.threadCountFor(params))
    }

    @Test
    fun live_threadCountFor_returns_positive_valid_threads() {
        val baseParams = WhisperParams(modelIdHint = "whisper_base")
        val count = WhisperCpuConfig.threadCountFor(baseParams)
        assertTrue("Thread count should be >= 1", count >= 1)
    }
}
