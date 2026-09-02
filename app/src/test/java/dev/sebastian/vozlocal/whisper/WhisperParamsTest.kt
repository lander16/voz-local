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
            noTimestamps = true,
        ).forLiveAudio(16_000 * 5)

        assertFalse(params.singleSegment)
        assertFalse(params.printTimestamps)
        assertFalse(params.noTimestamps)
    }
}
