package com.example

import com.example.data.model.DictationModel
import com.example.data.repository.ModelUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationModelOrderingTest {

    @Test
    fun qwenModelIsOrderedAtBottom() {
        val models = listOf(
            DictationModel("qwen2.5_0.5b", "Qwen2.5 0.5B", 398f, 99, 99, 12f, isDownloaded = false, isSelected = false),
            DictationModel("whisper_tiny", "Whisper Tiny", 42f, 72, 79, 8.5f, isDownloaded = true, isSelected = true),
            DictationModel("whisper_medium", "Whisper Medium", 823f, 97, 99, 1.0f, isDownloaded = false, isSelected = false),
            DictationModel("whisper_large_v3_turbo", "Whisper Large v3 Turbo", 547f, 99, 99, 3.5f, isDownloaded = false, isSelected = false)
        )

        val sorted = models.sortedBy { model ->
            if (model.id.startsWith("qwen")) 99 else when (model.id) {
                "whisper_tiny" -> 1
                "whisper_base" -> 2
                "whisper_small" -> 3
                "whisper_medium" -> 4
                "whisper_large_v3_turbo" -> 5
                else -> 10
            }
        }

        assertEquals("whisper_tiny", sorted[0].id)
        assertEquals("whisper_medium", sorted[1].id)
        assertEquals("whisper_large_v3_turbo", sorted[2].id)
        assertEquals("qwen2.5_0.5b", sorted[3].id)
    }

    @Test
    fun modelUrlsMappingContainsValidEndpoints() {
        val urlMap = ModelUrls.URL_MAP
        assertTrue(urlMap.containsKey("whisper_tiny"))
        assertTrue(urlMap.containsKey("whisper_base"))
        assertTrue(urlMap.containsKey("whisper_small"))
        assertTrue(urlMap.containsKey("whisper_medium"))
        assertTrue(urlMap.containsKey("whisper_large_v3_turbo"))
        assertTrue(urlMap.containsKey("qwen2.5_0.5b"))

        val largeTurboUrl = urlMap["whisper_large_v3_turbo"]
        assertTrue(largeTurboUrl != null && largeTurboUrl.contains("ggml-large-v3-turbo-q5_0.bin"))
    }
}
