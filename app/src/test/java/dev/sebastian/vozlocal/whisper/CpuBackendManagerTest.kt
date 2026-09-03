package dev.sebastian.vozlocal.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuBackendManagerTest {
    @Test
    fun modeParsingIsOptInAndRejectsUnknownValues() {
        assertEquals(CpuBackendMode.COMPATIBILITY, CpuBackendMode.parse(null))
        assertEquals(CpuBackendMode.COMPATIBILITY, CpuBackendMode.parse("future-mode"))
        assertEquals(CpuBackendMode.AUTOMATIC, CpuBackendMode.parse("AUTOMATIC"))
    }

    @Test
    fun nativeDiagnosticsExposeSelectedI8mmTier() {
        val diagnostics = CpuBackendDiagnostics.fromNative(
            raw = "status=ready;mode=automatic;tier=i8mm;features=NEON,FP16_VA,MATMUL_INT8,DOTPROD;hwcap=1;hwcap2=2",
            requestedMode = CpuBackendMode.AUTOMATIC,
            effectiveMode = CpuBackendMode.AUTOMATIC,
            recovered = false,
        )

        assertTrue(diagnostics.isReady)
        assertEquals("i8mm", diagnostics.tier)
        assertTrue("MATMUL_INT8" in diagnostics.features)
        assertNull(diagnostics.fallbackReason)
    }

    @Test
    fun interruptedProbeIsReportedWithoutLeakingNativeDetails() {
        val diagnostics = CpuBackendDiagnostics.fromNative(
            raw = "status=ready;mode=compatibility;tier=baseline;features=NEON",
            requestedMode = CpuBackendMode.AUTOMATIC,
            effectiveMode = CpuBackendMode.COMPATIBILITY,
            recovered = true,
        )

        assertTrue(diagnostics.recoveredFromInterruptedProbe)
        assertEquals("previous_optimized_probe_interrupted", diagnostics.fallbackReason)
        assertFalse(diagnostics.features.any { it.contains('/') })
    }
}
