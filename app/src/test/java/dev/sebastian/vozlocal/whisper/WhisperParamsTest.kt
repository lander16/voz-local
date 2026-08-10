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
        assertTrue(params.singleSegment)
        assertFalse(params.printTimestamps)
        assertEquals(0.6f, params.noSpeechThold, 0.0001f)
        assertEquals(-1.0f, params.logprobThold, 0.0001f)
        assertEquals(2.4f, params.entropyThold, 0.0001f)
        assertNull(params.vadModelPath)
        assertEquals(0, params.beamSize)
        assertEquals(0.2f, params.temperatureInc, 0.0001f)
        assertTrue(params.noContext)
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
    fun live_audio_up_to_25_seconds_uses_low_latency_single_window_mode() {
        val params = WhisperParams(
            singleSegment = false,
            printTimestamps = true,
            noTimestamps = false,
        ).forLiveAudio(LIVE_SINGLE_WINDOW_MAX_SAMPLES)

        assertTrue(params.singleSegment)
        assertFalse(params.printTimestamps)
        assertTrue(params.noTimestamps)
    }

    @Test
    fun live_audio_over_25_seconds_enables_multi_window_timestamp_decoding() {
        val params = WhisperParams().forLiveAudio(LIVE_SINGLE_WINDOW_MAX_SAMPLES + 1)

        assertFalse(params.singleSegment)
        assertFalse(params.printTimestamps)
        assertFalse(params.noTimestamps)
    }
}
