package dev.sebastian.vozlocal.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTranscriptReconcilerTest {
    @Test
    fun overlapping_windows_commit_only_the_confirmed_prefix() {
        val reconciler = StreamingTranscriptReconciler()
        assertEquals("hola mundo desde México", reconciler.accept("hola mundo desde México").text)

        val state = reconciler.accept("desde México para todos")
        assertEquals("hola mundo", state.stableText)
        assertEquals("desde México para todos", state.provisionalText)
        assertEquals("hola mundo desde México para todos", state.text)
    }

    @Test
    fun one_word_overlap_is_not_enough_to_commit_or_duplicate_text() {
        val reconciler = StreamingTranscriptReconciler()
        reconciler.accept("una prueba breve")

        val state = reconciler.accept("breve diferente")
        assertEquals("", state.stableText)
        assertEquals("breve diferente", state.text)
    }

    @Test
    fun final_transcript_is_authoritative() {
        val reconciler = StreamingTranscriptReconciler()
        reconciler.accept("esto es provisional")
        val final = reconciler.finalizeTranscript("esto es el resultado final")

        assertTrue(final.isFinal)
        assertFalse(final.text.contains("provisional"))
        assertEquals("esto es el resultado final", final.text)
    }
}
