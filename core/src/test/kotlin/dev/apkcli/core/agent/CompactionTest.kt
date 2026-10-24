package dev.apkcli.core.agent

import dev.apkcli.core.model.Block
import dev.apkcli.core.model.ContextSettings
import dev.apkcli.core.model.ConversationDigest
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.Role
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** /compact's side of compaction: what it folds, and what its summary carries for the chat's seam. */
class CompactionTest {

    private var seq = 0L

    private fun message(role: Role, text: String, tokens: Int = 100, pinned: Boolean = false, stored: Boolean = true) = Message(
        id = "m$seq",
        sessionId = "s",
        seq = if (stored) seq++ else -1,
        role = role,
        content = listOf(Block.Text(text)),
        tokens = tokens,
        pinned = pinned,
    )

    private val budget = ContextBudget(ContextSettings())

    @Test
    fun `compact folds every stored message, the latest turn too`() {
        val digest = message(Role.DIGEST, "previous summary", stored = false)
        val window = listOf(
            digest,
            message(Role.USER, "one"),
            message(Role.ASSISTANT, "answer one"),
            message(Role.USER, "keep this", pinned = true),
            message(Role.USER, "two"),
            message(Role.ASSISTANT, "answer two"),
        )
        val plan = budget.planWhole(window)

        assertEquals(window[1].seq, plan.archiveFromSeq)
        assertEquals(window.last().seq, plan.archiveToSeq)
        // The pinned one stays, and the prompt's own entries are no messages to fold.
        assertEquals(4, plan.archivedCount)
        assertEquals(1, plan.keptCount)
        assertEquals(400, plan.estimatedTokensFreed)
        assertFalse(plan.isNoop)
    }

    @Test
    fun `compact of nothing but pinned and synthetic messages does nothing`() {
        assertTrue(budget.planWhole(emptyList()).isNoop)
        val plan = budget.planWhole(listOf(message(Role.DIGEST, "summary", stored = false), message(Role.USER, "pinned", pinned = true)))
        assertTrue(plan.isNoop)
    }

    @Test
    fun `the seam's facts are kept with the summary and never read to the model`() {
        val digest = ConversationDigest(
            objective = "Ship the bot",
            keptByRequest = listOf("The API token goes in BOT_TOKEN"),
            trigger = ConversationDigest.TRIGGER_COMMAND,
            focus = "FOCUS-WORDS",
            tokensFreed = 18_200,
        )
        val rendered = digest.render()

        assertTrue(rendered.contains("## Kept in detail, as the user asked\n- The API token goes in BOT_TOKEN"))
        assertFalse(rendered.contains("FOCUS-WORDS"))
        assertFalse(rendered.contains("18200"))
        assertFalse(rendered.contains(ConversationDigest.TRIGGER_COMMAND))
    }

    @Test
    fun `a merged summary has the newer compaction's facts and both sets of kept details`() {
        val older = ConversationDigest(
            objective = "Ship the bot",
            keptByRequest = listOf("a"),
            trigger = ConversationDigest.TRIGGER_AUTO,
            tokensFreed = 40_000,
        )
        val newer = ConversationDigest(
            keptByRequest = listOf("a", "b"),
            trigger = ConversationDigest.TRIGGER_COMMAND,
            focus = "the tests",
            tokensFreed = 9_000,
        )
        val merged = older.mergedWith(newer)

        assertEquals("Ship the bot", merged.objective)
        assertEquals(listOf("a", "b"), merged.keptByRequest)
        assertEquals(ConversationDigest.TRIGGER_COMMAND, merged.trigger)
        assertEquals("the tests", merged.focus)
        assertEquals(9_000L, merged.tokensFreed)
    }

    @Test
    fun `a summary stored by an earlier build reads, with no seam facts`() {
        val stored = """{"version":2,"coversFromSeq":0,"coversToSeq":9,"objective":"Ship the bot","currentState":"Tests pass"}"""
        val digest = Json { ignoreUnknownKeys = true }.decodeFromString(ConversationDigest.serializer(), stored)

        assertEquals("Ship the bot", digest.objective)
        assertNull(digest.trigger)
        assertNull(digest.focus)
        assertEquals(0L, digest.tokensFreed)
        assertTrue(digest.keptByRequest.isEmpty())
    }

    @Test
    fun `a summary with a field this build no longer has still reads`() {
        // "requests" was written by a build that counted the folded requests for the seam.
        val stored = """{"version":1,"objective":"Ship the bot","trigger":"command","requests":2,"tokensFreed":2900}"""
        val digest = Json { ignoreUnknownKeys = true }.decodeFromString(ConversationDigest.serializer(), stored)

        assertEquals(ConversationDigest.TRIGGER_COMMAND, digest.trigger)
        assertEquals(2_900L, digest.tokensFreed)
    }
}
