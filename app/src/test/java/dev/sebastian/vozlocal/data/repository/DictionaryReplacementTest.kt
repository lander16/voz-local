package dev.sebastian.vozlocal.data.repository

import dev.sebastian.vozlocal.data.model.DictionaryWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class DictionaryReplacementTest {
    private fun snapshotOf(vararg words: DictionaryWord) =
        DictionaryReplacementProcessor.buildSnapshot(words.toList())

    private fun process(text: String, snapshot: DictionaryReplacementSnapshot): String =
        DictionaryReplacementProcessor.replace(text, snapshot)

    @Test
    fun preservesCanonicalTextLiterally() = runTest {
        val source = "literal-source-984203"
        val canonical = "\$5\\ruta\\niño😀[x]"

        assertEquals(canonical, process(source, snapshotOf(DictionaryWord(word = canonical, replacement = source))))
    }

    @Test
    fun refreshesWhenOnlyPhoneticVariantsChange() = runTest {
        val id = 910_001
        val canonical = "canonical-variant-910001"
        val replacement = AtomicReference(
            snapshotOf(DictionaryWord(id = id, word = canonical, replacement = "old-variant-910001"))
        )
        replacement.set(snapshotOf(DictionaryWord(id = id, word = canonical, replacement = "new-variant-910001")))

        assertEquals(canonical, process("new-variant-910001", replacement.get()))
    }

    @Test
    fun replacementsDoNotCascadeIntoLaterDictionaryEntries() = runTest {
        val first = "first-canonical-984204"
        val second = "second-canonical-984204"

        assertEquals(
            first,
            process(
                "source-984204",
                snapshotOf(
                    DictionaryWord(word = first, replacement = "source-984204"),
                    DictionaryWord(word = second, replacement = first)
                )
            )
        )
    }

    @Test
    fun concurrentPostProcessingUsesCompleteReplacementSnapshots() = runTest {
        val canonical = "concurrent-canonical-984205"
        val source = "concurrent-source-984205"
        val replacement = AtomicReference(
            snapshotOf(DictionaryWord(word = canonical, replacement = source))
        )

        val results = coroutineScope {
            (1..32).map {
                async(Dispatchers.Default) { process(source, replacement.get()) }
            }.awaitAll()
        }

        assertTrue(results.all { it == canonical })
    }
}
