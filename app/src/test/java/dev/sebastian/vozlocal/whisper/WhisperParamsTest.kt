package dev.sebastian.vozlocal.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the WhisperParams defaults (from the research pass) and the Spanish
 * initial-prompt substitution logic used by WhisperEngine.transcribe.
 */
class WhisperParamsTest {

    @Test
    fun default_values_match_research() {
        val params = WhisperParams()
        assertEquals("es", params.language)
        assertNull(params.initialPrompt)
        assertFalse(params.singleSegment)
        assertFalse(params.printTimestamps)
        assertEquals(0.6f, params.noSpeechThold, 0.0001f)
        assertEquals(-1.0f, params.logprobThold, 0.0001f)
        assertEquals(2.4f, params.entropyThold, 0.0001f)
        assertNull(params.vadModelPath)
        assertEquals(0, params.beamSize)
        assertEquals(0.2f, params.temperatureInc, 0.0001f)
        assertFalse(params.noContext)
        assertFalse(params.noTimestamps)
        assertEquals(0, params.audioCtx)
    }

    @Test
    fun spanish_prompt_applies_when_language_is_es() {
        // Null prompt + Spanish => the default Spanish priming prompt is substituted.
        assertEquals(SPANISH_PROMPT, effectivePrompt("es", null))
        // Other languages get no default prompt (Whisper auto-prompts).
        assertNull(effectivePrompt("en", null))
        assertNull(effectivePrompt("fr", null))
        // An explicitly-configured prompt always wins.
        assertEquals("custom", effectivePrompt("es", "custom"))
        assertEquals("custom", effectivePrompt("en", "custom"))
    }

    @Test
    fun live_audio_decodes_full_utterance_without_premature_truncation() {
        val params = WhisperParams(
            singleSegment = true,
            printTimestamps = true,
            noTimestamps = false,
        ).forLiveAudio(16_000 * 5)

        assertFalse(params.singleSegment)
        assertFalse(params.printTimestamps)
        assertTrue(params.noTimestamps)
        assertEquals(0.0f, params.temperatureInc, 0.0001f)
        assertEquals(287, params.audioCtx)
    }

    @Test
    fun dynamic_audio_context_scaling_calculates_expected_frames() {
        val baseParams = WhisperParams()

        // Audio < 0.5s returns 0 (default context)
        assertEquals(0, baseParams.forLiveAudio((16_000 * 0.2f).toInt()).audioCtx)
        assertEquals(0, baseParams.forLiveAudio(0).audioCtx)

        // Audio in 0.5s..28s returns frames clamped between 256 and 1500
        // At 0.5s: ((0.5 + 0.75) * 50) = 62 -> clamped to 256
        assertEquals(256, baseParams.forLiveAudio((16_000 * 0.5f).toInt()).audioCtx)

        // At 5.0s: ((5.0 + 0.75) * 50) = 287
        assertEquals(287, baseParams.forLiveAudio(16_000 * 5).audioCtx)

        // At 10.0s: ((10.0 + 0.75) * 50) = 537
        assertEquals(537, baseParams.forLiveAudio(16_000 * 10).audioCtx)

        // At 28.0s: ((28.0 + 0.75) * 50) = 1437
        assertEquals(1437, baseParams.forLiveAudio(16_000 * 28).audioCtx)

        // Audio > 28s returns 0 (full 30s context / default)
        assertEquals(0, baseParams.forLiveAudio(16_000 * 29).audioCtx)
        assertEquals(0, baseParams.forLiveAudio(16_000 * 35).audioCtx)
    }
}
