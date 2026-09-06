package dev.sebastian.vozlocal.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.sebastian.vozlocal.data.model.DictionaryWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DictionaryReplacementTest {
    private fun repo() = DictationRepository(ApplicationProvider.getApplicationContext<Context>())

    private suspend fun DictationRepository.process(text: String): String = postProcessText(
        text = text,
        smartPunctuation = false,
        autoCapitalize = false,
        applyDict = true,
        useAiPolisher = false
    )

    @Test
    fun preservesCanonicalTextLiterally() = runTest {
        val r = repo()
        val source = "literal-source-984203"
        val canonical = "\$5\\ruta\\niño😀[x]"
        r.insertWord(DictionaryWord(word = canonical, replacement = source))

        assertEquals(canonical, r.process(source))
    }

    @Test
    fun refreshesWhenOnlyPhoneticVariantsChange() = runTest {
        val r = repo()
        val id = 910_001
        val canonical = "canonical-variant-910001"
        r.insertWord(DictionaryWord(id = id, word = canonical, replacement = "old-variant-910001"))
        r.insertWord(DictionaryWord(id = id, word = canonical, replacement = "new-variant-910001"))

        assertEquals(canonical, r.process("new-variant-910001"))
    }

    @Test
    fun replacementsDoNotCascadeIntoLaterDictionaryEntries() = runTest {
        val r = repo()
        val first = "first-canonical-984204"
        val second = "second-canonical-984204"
        r.insertWord(DictionaryWord(word = first, replacement = "source-984204"))
        r.insertWord(DictionaryWord(word = second, replacement = first))

        assertEquals(first, r.process("source-984204"))
    }

    @Test
    fun concurrentPostProcessingUsesCompleteReplacementSnapshots() = runTest {
        val r = repo()
        val canonical = "concurrent-canonical-984205"
        val source = "concurrent-source-984205"
        r.insertWord(DictionaryWord(word = canonical, replacement = source))

        val results = coroutineScope {
            (1..32).map {
                async(Dispatchers.Default) { r.process(source) }
            }.awaitAll()
        }

        assertTrue(results.all { it == canonical })
    }
}
