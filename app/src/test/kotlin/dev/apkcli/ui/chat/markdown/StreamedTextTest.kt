package dev.apkcli.ui.chat.markdown

import dev.apkcli.ui.chat.components.monoShown
import dev.apkcli.ui.chat.components.reasoningPieces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Text that arrives a piece at a time: a reply parsed again only from its
 * last blocks reads as the whole reply parsed at once, at every point of its
 * arrival, and reasoning drawn in pieces keeps the pieces it has drawn.
 */
class StreamedTextTest {

    private val samples = listOf(
        """
        # Plan

        Paragraph one with **bold** and `code`.
        Still paragraph one.

        1. first
        2. second

        3. third, after a blank line
           continued

        - [ ] task
        - [x] done
          - nested

        > quoted line
        lazy continuation

        Title
        =====

        | a | b |
        |---|:-:|
        | 1 | 2 |
        3 | 4
        after the table

        ```kotlin
        fun main() {

            println("hi")
        }
        ```

        $$
        x^2 + y^2
        $$

        ---
        * * *
        Last words.
        """.trimIndent(),
        "para\n# not a heading at first\n#also\n\n2. starts at two\n\n1) paren\n2) list\n\n```\nopen fence to the end\n\n",
        "- a\n\n- b\n\n  indented continuation\n\nplain\n| x |\n|--|\n| y |\n\n~~~\ntilde\n~~~\n",
    )

    /** Every prefix, in steps of [step] characters, and the whole. */
    private fun prefixes(text: String, step: Int): List<String> =
        (step..text.length step step).map { text.substring(0, it) } + text

    @Test
    fun `a reply parsed as it arrives reads as the reply parsed at once`() {
        for (sample in samples) {
            for (step in listOf(1, 2, 3, 7, 13)) {
                val streaming = MarkdownParser.Streaming()
                for (prefix in prefixes(sample, step)) {
                    assertEquals("step $step at ${prefix.length}: ${prefix.takeLast(40)}", MarkdownParser.parse(prefix), streaming.update(prefix))
                }
            }
        }
    }

    @Test
    fun `blocks that cannot change any more are kept, not parsed again`() {
        val streaming = MarkdownParser.Streaming()
        val first = streaming.update("One.\n\nTwo.\n\nThree.\n\nFour")
        val second = streaming.update("One.\n\nTwo.\n\nThree.\n\nFour and more")
        assertSame(first[0], second[0])
        assertSame(first[1], second[1])
        // Text that is not the same reply grown starts again.
        assertEquals(MarkdownParser.parse("Other."), streaming.update("Other."))
    }

    @Test
    fun `reasoning in pieces keeps every piece but the last as it streams`() {
        val reasoning = buildString {
            repeat(6) { p ->
                append("Paragraph $p. ")
                repeat(40) { append("word$it ") }
                append("\nsecond line of $p\n\n")
            }
            append("x".repeat(3_000))
            append(" tail words here")
        }
        val whole = reasoningPieces(reasoning)
        assertTrue(whole.all { it.text.length <= 1_200 })
        assertEquals(reasoning.replace(Regex("\\s+"), ""), whole.joinToString("") { it.text }.replace(Regex("\\s+"), ""))
        for (prefix in prefixes(reasoning, 5)) {
            val pieces = reasoningPieces(prefix)
            for (i in 0 until pieces.size - 2) {
                assertEquals("at ${prefix.length}, piece $i", whole[i].text, pieces[i].text)
                assertEquals(whole[i].gap, pieces[i].gap)
            }
        }
    }

    @Test
    fun `an output box draws a long text's start and end, and says what is left out`() {
        val short = "line\n".repeat(100)
        assertSame(short, monoShown(short, "… %1\$s …", Locale.ROOT))

        val long = (1..20_000).joinToString("\n") { "line $it" }
        val shown = monoShown(long, "… %1\$s not shown …", Locale.ROOT)
        assertTrue(shown.length < 40_000)
        assertTrue(shown.startsWith("line 1\n"))
        assertTrue(shown.endsWith("line 20000"))
        val marker = checkNotNull(Regex("… ([\\d,]+) not shown …").find(shown)) { "no marker in the shown text" }
        val omitted = marker.groupValues[1].replace(",", "").toInt()
        val kept = shown.length - "\n\n… ${"%,d".format(Locale.ROOT, omitted)} not shown …\n\n".length
        assertEquals(long.length, kept + omitted)
    }
}
