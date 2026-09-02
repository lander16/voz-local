package dev.sebastian.vozlocal.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpokenPunctuationCommandsTest {

    private fun repo(): DictationRepository {
        val r = DictationRepository(ApplicationProvider.getApplicationContext<Context>())
        r.saveSpokenPunctuationCommands(true)
        return r
    }

    @Test
    fun spanishPunctuationBasicConversion() = runTest {
        val result = repo().postProcessText(
            text = "Hola coma como estás signo de interrogación",
            smartPunctuation = true,
            autoCapitalize = false,
            applyDict = false
        )
        assertEquals("Hola, como estás?", result)
    }

    @Test
    fun englishPunctuationBasicConversion() = runTest {
        val result = repo().postProcessText(
            text = "Hello comma how are you question mark",
            smartPunctuation = true,
            autoCapitalize = false,
            applyDict = false
        )
        assertEquals("Hello, how are you?", result)
    }

    @Test
    fun spanishPeriodsAndVariations() = runTest {
        val r = repo()
        assertEquals("Uno. Dos. Tres. Cuatro.", r.applySpokenPunctuationCommands("Uno punto Dos punto final Tres punto y seguido Cuatro punto y aparte"))
    }

    @Test
    fun englishPeriodsAndFullStops() = runTest {
        val r = repo()
        assertEquals("One. Two.", r.applySpokenPunctuationCommands("One period Two full stop"))
    }

    @Test
    fun semicolonAndColonSpanishAndEnglish() = runTest {
        val r = repo()
        // Spanish
        assertEquals("primero; segundo: tercero", r.applySpokenPunctuationCommands("primero punto y coma segundo dos puntos tercero"))
        // English
        assertEquals("first; second: third", r.applySpokenPunctuationCommands("first semicolon second colon third"))
    }

    @Test
    fun ellipsisSpanishAndEnglish() = runTest {
        val r = repo()
        // Spanish
        assertEquals("espera...", r.applySpokenPunctuationCommands("espera puntos suspensivos"))
        // English
        assertEquals("wait...", r.applySpokenPunctuationCommands("wait ellipsis"))
        assertEquals("loading...", r.applySpokenPunctuationCommands("loading dot dot dot"))
    }

    @Test
    fun spanishInvertedAndClosingExclamationAndInterrogation() = runTest {
        val r = repo()
        assertEquals("¿cómo estás?", r.applySpokenPunctuationCommands("abrir interrogación cómo estás cerrar interrogación"))
        assertEquals("¿cómo estás?", r.applySpokenPunctuationCommands("abrir signo de interrogacion cómo estás signo de interrogacion"))
        assertEquals("¡qué alegría!", r.applySpokenPunctuationCommands("abrir exclamación qué alegría cerrar exclamación"))
        assertEquals("¡sorpresa!", r.applySpokenPunctuationCommands("abrir signo de admiracion sorpresa signo de admiracion"))
    }

    @Test
    fun englishExclamationAndQuestionMarks() = runTest {
        val r = repo()
        assertEquals("What is this? Incredible!", r.applySpokenPunctuationCommands("What is this question mark Incredible exclamation mark"))
        assertEquals("Wow!", r.applySpokenPunctuationCommands("Wow exclamation point"))
    }

    @Test
    fun quotesSpanishAndEnglish() = runTest {
        val r = repo()
        // Spanish
        assertEquals("dijo: \"hola mundo\"", r.applySpokenPunctuationCommands("dijo dos puntos abrir comillas hola mundo cerrar comillas"))
        // English
        assertEquals("he said: \"hello world\"", r.applySpokenPunctuationCommands("he said colon open quote hello world close quote"))
        assertEquals("quoted \"text\"", r.applySpokenPunctuationCommands("quoted open quotation mark text close quotation mark"))
    }

    @Test
    fun parenthesesSpanishAndEnglish() = runTest {
        val r = repo()
        // Spanish
        assertEquals("nota (información importante) fin", r.applySpokenPunctuationCommands("nota abrir paréntesis información importante cerrar paréntesis fin"))
        assertEquals("nota (sin acento) fin", r.applySpokenPunctuationCommands("nota abrir parentesis sin acento cerrar parentesis fin"))
        // English
        assertEquals("note (important details) end", r.applySpokenPunctuationCommands("note open parenthesis important details close parenthesis end"))
        assertEquals("note (short paren) end", r.applySpokenPunctuationCommands("note open paren short paren close paren end"))
    }

    @Test
    fun hyphensAndDashesSpanishAndEnglish() = runTest {
        val r = repo()
        assertEquals("palabra - otra - tercera", r.applySpokenPunctuationCommands("palabra guion otra guión tercera"))
        assertEquals("word - another - third", r.applySpokenPunctuationCommands("word hyphen another dash third"))
    }

    @Test
    fun newLinesAndParagraphsSpanishAndEnglish() = runTest {
        val r = repo()
        // Spanish
        val esResult = r.applySpokenPunctuationCommands("primera línea nueva línea segunda línea nuevo párrafo segundo párrafo")
        assertEquals("primera línea\nsegunda línea\n\nsegundo párrafo", esResult)
        val esUnaccented = r.applySpokenPunctuationCommands("primera linea nueva linea segunda linea nuevo parrafo segundo parrafo")
        assertEquals("primera linea\nsegunda linea\n\nsegundo parrafo", esUnaccented)

        // English
        val enResult = r.applySpokenPunctuationCommands("first line new line second line new paragraph second paragraph")
        assertEquals("first line\nsecond line\n\nsecond paragraph", enResult)
    }

    @Test
    fun spacingAndAttachmentRules() = runTest {
        val r = repo()
        // Closing punctuation attaches to preceding word without space:
        assertEquals("palabra,", r.formatPunctuationSpacing("palabra ,"))
        assertEquals("palabra.", r.formatPunctuationSpacing("palabra ."))
        assertEquals("palabra;", r.formatPunctuationSpacing("palabra ;"))
        assertEquals("palabra:", r.formatPunctuationSpacing("palabra :"))
        assertEquals("palabra?", r.formatPunctuationSpacing("palabra ?"))
        assertEquals("palabra!", r.formatPunctuationSpacing("palabra !"))
        assertEquals("palabra)", r.formatPunctuationSpacing("palabra )"))
        assertEquals("palabra\"", r.formatPunctuationSpacing("palabra \""))

        // Opening punctuation attaches to following word without space:
        assertEquals("¿palabra", r.formatPunctuationSpacing("¿ palabra"))
        assertEquals("¡palabra", r.formatPunctuationSpacing("¡ palabra"))
        assertEquals("(palabra", r.formatPunctuationSpacing("( palabra"))
        assertEquals("\"palabra\"", r.formatPunctuationSpacing("\" palabra \""))

        // Opening punctuation has space before if preceded by a letter:
        assertEquals("hola ¿palabra", r.formatPunctuationSpacing("hola¿palabra"))
        assertEquals("hola ¡palabra", r.formatPunctuationSpacing("hola¡palabra"))
        assertEquals("hola (palabra", r.formatPunctuationSpacing("hola(palabra"))
        assertEquals("hola \"palabra\"", r.formatPunctuationSpacing("hola\"palabra\""))

        // Clean newlines without trailing or leading stray spaces:
        assertEquals("primera linea\nsegunda linea", r.formatPunctuationSpacing("primera linea   \n   segunda linea"))
        assertEquals("parrafo uno\n\nparrafo dos", r.formatPunctuationSpacing("parrafo uno   \n\n   parrafo dos"))
    }
}
