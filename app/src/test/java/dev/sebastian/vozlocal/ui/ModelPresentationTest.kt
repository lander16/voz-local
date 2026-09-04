package dev.sebastian.vozlocal.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import dev.sebastian.vozlocal.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ModelPresentationTest {
    @Test
    fun catalogMetadataIsFactualAndComplete() {
        val expected = mapOf(
            "whisper_tiny" to Triple(R.string.model_badge_smallest_download, R.string.model_language_multilingual, "q8_0"),
            "whisper_base" to Triple(R.string.model_badge_default, R.string.model_language_multilingual, "q8_0"),
            "whisper_base_en" to Triple(R.string.model_badge_english_only, R.string.model_language_english_only, "q8_0"),
            "whisper_small" to Triple(R.string.model_badge_multilingual, R.string.model_language_multilingual, "q8_0"),
            "whisper_small_q5_1" to Triple(R.string.model_badge_smaller_small, R.string.model_language_multilingual, "q5_1"),
            "whisper_large_v3_turbo" to Triple(R.string.model_badge_turbo, R.string.model_language_multilingual, "q5_0"),
            "whisper_medium" to Triple(R.string.model_badge_largest_download, R.string.model_language_multilingual, "q8_0"),
        )

        expected.forEach { (id, values) ->
            val presentation = modelPresentation(id)
            assertEquals(values.first, presentation.badgeRes)
            assertEquals(values.second, presentation.languageRes)
            assertEquals(values.third, presentation.quantization)
        }
    }

    @Test
    fun unknownModelDoesNotInventRecommendation() {
        val presentation = modelPresentation("future_model")
        assertEquals(R.string.model_badge_local, presentation.badgeRes)
        assertEquals(R.string.model_language_unknown, presentation.languageRes)
        assertEquals("—", presentation.quantization)
    }

    @Test
    fun catalogCopyHasEnglishAndSpanishResources() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val english = localizedContext(base, Locale.ENGLISH)
        val spanish = localizedContext(base, Locale.forLanguageTag("es"))
        val ids = listOf(
            R.string.models_title,
            R.string.models_benchmark_notice,
            R.string.model_badge_default,
            R.string.model_badge_smaller_small,
            R.string.model_language_multilingual,
            R.string.model_description_small_q8,
            R.string.model_description_turbo_q5,
        )

        ids.forEach { id ->
            assertTrue(english.getString(id).isNotBlank())
            assertTrue(spanish.getString(id).isNotBlank())
            assertNotEquals(english.getString(id), spanish.getString(id))
        }
    }

    private fun localizedContext(context: Context, locale: Locale): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }
}
