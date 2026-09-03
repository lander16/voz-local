package dev.sebastian.vozlocal.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartParagraphFormatterTest {

    @Test
    fun shortTextRemainsSingleParagraph() {
        val short = "Hola, esto es una prueba corta de dictado sin saltos."
        assertEquals(short, SmartParagraphFormatter.format(short))
    }

    @Test
    fun longMonolithicTextIsSegmentedIntoCleanParagraphs() {
        val longText = """
            Hola Mica ya te veo con muchas ganas de escucharte. Pues sí suena como algo muy complicado y difícil de creer que alguien pueda mantenerse indiferente ante una situación tan violenta. En esta fase tan evidente pues como que uno sí tal vez es un poco más fácil entenderlo dentro de la comunidad. Pero es difícil de creer que en este año se haya querido ir para allá y además un poco como dices sin importar las consecuencias. Pero bueno estamos hablando de ti y de cómo te sientes tú con todo esto. Yo pienso que con esas personas podemos mantener una relación más distante sin necesidad de cortar todo vínculo. En fin es un debate que también a mí me toca con mi familia y es algo que todavía no he resuelto.
        """.trimIndent().replace("\n", " ")

        val formatted = SmartParagraphFormatter.format(longText)

        // Must have inserted paragraph breaks (\n\n)
        assertTrue("Expected paragraph breaks in long text", formatted.contains("\n\n"))
        val paragraphs = formatted.split("\n\n")
        assertTrue("Expected at least 2 paragraphs, got ${paragraphs.size}", paragraphs.size >= 2)
        // Each paragraph should be clean
        paragraphs.forEach { p ->
            assertTrue("Paragraph should not be blank", p.isNotBlank())
        }
    }

    @Test
    fun preservesExistingDoubleNewlines() {
        val withExisting = "Primer párrafo aquí.\n\nSegundo párrafo aquí."
        assertEquals(withExisting, SmartParagraphFormatter.format(withExisting))
    }
}
