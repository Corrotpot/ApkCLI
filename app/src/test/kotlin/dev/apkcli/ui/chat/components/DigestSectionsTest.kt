package dev.apkcli.ui.chat.components

import dev.apkcli.R
import dev.apkcli.core.model.ConversationDigest
import dev.apkcli.ui.chat.markdown.MarkdownParser
import dev.apkcli.ui.chat.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The summary under a seam as its card draws it, and as find in the chat searches it. */
class DigestSectionsTest {

    private val digest = ConversationDigest(
        objective = "Ship the bot",
        currentState = "Handlers done.\n\nTests next.",
        keptByRequest = listOf("Token in\nBOT_TOKEN"),
        edits = listOf(
            ConversationDigest.EditNote("bot/main.py", "added /start"),
            ConversationDigest.EditNote("bot/old.py", "removed", applied = false),
        ),
        commands = listOf(ConversationDigest.CommandNote("pytest -q", "1 failed", exitCode = 1)),
        verbatimPins = listOf("```\nfenced inside\n```"),
        nextSteps = listOf("fix the test", "ship"),
    )

    @Test
    fun `sections say something, in the card's order`() {
        val sections = digestSections(digest)
        assertEquals(listOf("objective", "state", "kept", "edits", "commands", "verbatim", "next"), sections.map { it.key })
        assertEquals(R.string.digest_objective, sections.first().title)
        assertTrue(digestSections(ConversationDigest()).isEmpty())
    }

    @Test
    fun `a list item stays one item, and a reverted change is struck through`() {
        val sections = digestSections(digest).associateBy { it.key }
        assertEquals("- Token in BOT_TOKEN", sections.getValue("kept").markdown)
        assertEquals("- `bot/main.py` — added /start\n- ~~`bot/old.py` — removed~~", sections.getValue("edits").markdown)
        assertEquals("- `pytest -q` → 1 failed (exit 1)", sections.getValue("commands").markdown)
        assertEquals("1. fix the test\n2. ship", sections.getValue("next").markdown)
    }

    @Test
    fun `a verbatim pin is one code block, whatever fences it holds`() {
        val pin = digestSections(digest).single { it.key == "verbatim" }.markdown
        val blocks = MarkdownParser.parse(pin)
        assertEquals(1, blocks.size)
        assertEquals("```\nfenced inside\n```", (blocks.single() as MdBlock.Code).code)
    }

    @Test
    fun `find searches the summary under the keys the card draws it with`() {
        val segments = digestFindSegments(digest)
        assertTrue(segments.all { (key, _) -> key.startsWith("seam:") })
        assertTrue(segments.any { (key, text) -> key.startsWith("seam:objective:") && text == "Ship the bot" })
        // Two paragraphs, two pieces; and the words without their marks.
        assertEquals(2, segments.count { (key, _) -> key.startsWith("seam:state:") })
        assertTrue(segments.any { (_, text) -> text == "bot/old.py — removed" })
        assertTrue(digestFindSegments(null).isEmpty())
    }
}
