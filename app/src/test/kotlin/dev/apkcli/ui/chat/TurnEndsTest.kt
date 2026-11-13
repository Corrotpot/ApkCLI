package dev.apkcli.ui.chat

import dev.apkcli.core.model.Message
import dev.apkcli.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which replies get the line with their time and model: [turnEnds]. */
class TurnEndsTest {

    private fun message(id: String, role: Role, at: Long) = Message(id = id, sessionId = "s", role = role, createdAt = at)

    /** Two turns; the second was sent at 1 000. */
    private val earlier = listOf(
        message("u1", Role.USER, 100),
        message("a1", Role.ASSISTANT, 200),
        message("t1", Role.TOOL, 300),
        message("a2", Role.ASSISTANT, 400),
        message("seam", Role.DIGEST, 500),
    )
    private val chat = earlier + listOf(
        message("u2", Role.USER, 1_000),
        message("a3", Role.ASSISTANT, 1_200),
    )

    @Test
    fun `a finished turn has it under its last reply, past a seam`() {
        assertEquals(setOf("a2", "a3"), turnEnds(chat, runningSince = null))
    }

    @Test
    fun `the turn still running has it nowhere, so it never moves down a round`() {
        assertEquals(setOf("a2"), turnEnds(chat, runningSince = 1_000))
    }

    @Test
    fun `the reply before a turn just sent keeps it while that turn's message is not in yet`() {
        assertEquals(setOf("a2"), turnEnds(earlier, runningSince = 1_000))
    }
}
