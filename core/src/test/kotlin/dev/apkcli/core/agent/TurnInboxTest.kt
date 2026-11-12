package dev.apkcli.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnInboxTest {

    @Test
    fun `messages come out in the order sent, once`() {
        val inbox = TurnInbox()
        inbox.offer(TurnInbox.Pending("a", "first"))
        inbox.offer(TurnInbox.Pending("b", "second"))
        assertFalse(inbox.isEmpty())
        assertEquals(listOf("a", "b"), inbox.drain().map { it.id })
        assertTrue(inbox.isEmpty())
        assertTrue("drained once", inbox.drain().isEmpty())
    }

    @Test
    fun `a message can be taken back until the loop takes it`() {
        val inbox = TurnInbox()
        inbox.offer(TurnInbox.Pending("a", "first"))
        inbox.offer(TurnInbox.Pending("b", "second"))
        assertTrue(inbox.withdraw("a"))
        assertEquals(listOf("b"), inbox.drain().map { it.id })
        assertFalse("already taken: it arrives as sent", inbox.withdraw("b"))
    }
}
