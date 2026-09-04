package dev.sebastian.vozlocal.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelPresentationTest {
    @Test
    fun catalogMetadataIsFactualAndComplete() {
        val expected = mapOf(
            "whisper_tiny" to Triple("Smallest download", "Multilingual", "q8_0"),
            "whisper_base" to Triple("Default", "Multilingual", "q8_0"),
            "whisper_base_en" to Triple("English only", "English only", "q8_0"),
            "whisper_small" to Triple("Multilingual", "Multilingual", "q8_0"),
            "whisper_small_q5_1" to Triple("Smaller Small file", "Multilingual", "q5_1"),
            "whisper_large_v3_turbo" to Triple("Turbo checkpoint", "Multilingual", "q5_0"),
            "whisper_medium" to Triple("Largest download", "Multilingual", "q8_0"),
        )

        expected.forEach { (id, values) ->
            val presentation = modelPresentation(id)
            assertEquals(values.first, presentation.badge)
            assertEquals(values.second, presentation.language)
            assertEquals(values.third, presentation.quantization)
            val copy = listOf(presentation.badge, presentation.description).joinToString(" ").lowercase()
            assertFalse(copy.contains("best accuracy"))
            assertFalse(copy.contains("fastest"))
            assertFalse(copy.contains("sota"))
            assertFalse(Regex("\\d+(\\.\\d+)?x").containsMatchIn(copy))
            assertFalse(Regex("\\d+%").containsMatchIn(copy))
        }
    }

    @Test
    fun unknownModelDoesNotInventRecommendation() {
        val presentation = modelPresentation("future_model")
        assertEquals("Local model", presentation.badge)
        assertEquals("Unknown", presentation.language)
        assertEquals("Unknown", presentation.quantization)
    }
}
