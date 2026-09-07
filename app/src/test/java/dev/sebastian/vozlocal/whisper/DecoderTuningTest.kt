package dev.sebastian.vozlocal.whisper

import dev.sebastian.vozlocal.benchmark.TranscriptionBenchmarkConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests covering P08: explicit prompt mode configuration and short-context tuning support.
 */
class DecoderTuningTest {

    @Test
    fun effectivePrompt_promptModeOff_returnsNullEvenForSpanish() {
        // Spanish with no initial prompt should return null when PromptMode is OFF
        assertNull(effectivePrompt("es", null, PromptMode.OFF))

        // Spanish with initial prompt provided should still return null when PromptMode is OFF
        assertNull(effectivePrompt("es", "Custom prompt", PromptMode.OFF))

        // Other languages should also return null
        assertNull(effectivePrompt("en", null, PromptMode.OFF))
        assertNull(effectivePrompt("en", "English prompt", PromptMode.OFF))
    }

    @Test
    fun effectivePrompt_promptModeAutomatic_returnsSpanishPromptForSpanishAndNullForEnglish() {
        // Default PromptMode is AUTOMATIC
        assertEquals(SPANISH_PROMPT, effectivePrompt("es", null, PromptMode.AUTOMATIC))
        assertEquals(SPANISH_PROMPT, effectivePrompt("es", null)) // default arg test

        // English returns null under AUTOMATIC
        assertNull(effectivePrompt("en", null, PromptMode.AUTOMATIC))
        assertNull(effectivePrompt("en", null))

        // If explicit prompt is provided in AUTOMATIC, it takes precedence
        assertEquals("Explicit prompt", effectivePrompt("es", "Explicit prompt", PromptMode.AUTOMATIC))
        assertEquals("Explicit prompt", effectivePrompt("en", "Explicit prompt", PromptMode.AUTOMATIC))
    }

    @Test
    fun effectivePrompt_promptModeCustom_returnsCustomPromptOrNullIfBlank() {
        // Custom prompt text is returned
        assertEquals("Vocabulario médico y nombres propios",
            effectivePrompt("es", "Vocabulario médico y nombres propios", PromptMode.CUSTOM))
        assertEquals("English vocabulary prompt",
            effectivePrompt("en", "English vocabulary prompt", PromptMode.CUSTOM))

        // Returns null if initialPrompt is null
        assertNull(effectivePrompt("es", null, PromptMode.CUSTOM))
        assertNull(effectivePrompt("en", null, PromptMode.CUSTOM))

        // Returns null if initialPrompt is empty or blank
        assertNull(effectivePrompt("es", "", PromptMode.CUSTOM))
        assertNull(effectivePrompt("es", "   ", PromptMode.CUSTOM))
        assertNull(effectivePrompt("es", "\t\n  ", PromptMode.CUSTOM))
        assertNull(effectivePrompt("en", "   ", PromptMode.CUSTOM))
    }

    @Test
    fun whisperParams_withAudioCtxAndPromptMode_copyAndLiveAudioConfiguration() {
        val defaultParams = WhisperParams()
        assertEquals(PromptMode.AUTOMATIC, defaultParams.promptMode)
        assertEquals(0, defaultParams.audioCtx)

        // Custom parameters
        val customParams = WhisperParams(
            promptMode = PromptMode.OFF,
            audioCtx = 512,
            singleSegment = true,
            printTimestamps = true
        )
        assertEquals(PromptMode.OFF, customParams.promptMode)
        assertEquals(512, customParams.audioCtx)
        assertTrue(customParams.singleSegment)
        assertTrue(customParams.printTimestamps)

        // Test copy
        val copied = customParams.copy(
            promptMode = PromptMode.CUSTOM,
            audioCtx = 1024
        )
        assertEquals(PromptMode.CUSTOM, copied.promptMode)
        assertEquals(1024, copied.audioCtx)
        assertTrue(copied.singleSegment)

        // Test forLiveAudio resets audioCtx to 0 and adjusts live audio flags,
        // while preserving promptMode
        val liveAudioParams = customParams.forLiveAudio(16_000 * 5)
        assertEquals(0, liveAudioParams.audioCtx)
        assertEquals(PromptMode.OFF, liveAudioParams.promptMode)
        assertFalse(liveAudioParams.singleSegment)
        assertFalse(liveAudioParams.printTimestamps)
    }

    @Test
    fun transcriptionBenchmarkConfig_withCustomPromptModesAndAudioCtx() {
        val defaultConfig = TranscriptionBenchmarkConfig(
            modelId = "whisper_small",
            quantization = "q8_0",
            threadCount = 4,
            language = "es",
            beamSize = 0,
            temperatureIncrement = 0.2f,
            vadEnabled = true,
            audioSource = "VOICE_RECOGNITION"
        )
        assertEquals(PromptMode.AUTOMATIC, defaultConfig.promptMode)
        assertEquals(0, defaultConfig.audioCtx)

        // Duration-binned audio context sizes (e.g. 0, 512, 1024, 1500) and prompt modes
        val bins = listOf(0, 512, 1024, 1500)
        for (ctx in bins) {
            val offConfig = defaultConfig.copy(promptMode = PromptMode.OFF, audioCtx = ctx)
            assertEquals(PromptMode.OFF, offConfig.promptMode)
            assertEquals(ctx, offConfig.audioCtx)

            val autoConfig = defaultConfig.copy(promptMode = PromptMode.AUTOMATIC, audioCtx = ctx)
            assertEquals(PromptMode.AUTOMATIC, autoConfig.promptMode)
            assertEquals(ctx, autoConfig.audioCtx)
        }

        // Validate that negative audioCtx is rejected
        try {
            defaultConfig.copy(audioCtx = -1)
            fail("Expected IllegalArgumentException for negative audioCtx")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("audioCtx"))
        }
    }
}
