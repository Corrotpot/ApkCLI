package dev.apkcli.core.agent

import dev.apkcli.core.model.Block
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * What the user sends while a turn runs.
 *
 * The loop takes it at a step boundary: after a round's tool results are
 * stored and before the next request, so a message never lands between a
 * call and its result (Anthropic and Kiro both refuse that); and when a reply
 * with no tool calls would end the turn, so a message sent while the answer
 * streamed gets its own answer in the same turn instead of waiting for the
 * user to send it again. The model reads it as the next thing the user said,
 * which is what it is: steering, not an interruption, and nothing running is
 * stopped for it.
 *
 * Whatever is still here when the turn ends without taking it — stopped,
 * failed, at the iteration limit, or sent after the loop's last look — the
 * caller takes back with [drain], so nothing the user wrote is lost.
 */
class TurnInbox {

    /** One message, with the id its stored [dev.apkcli.core.model.Message] will carry. */
    data class Pending(val id: String, val text: String, val images: List<Block.Image> = emptyList())

    private val queue = ConcurrentLinkedQueue<Pending>()

    fun offer(pending: Pending) {
        queue.add(pending)
    }

    /**
     * Take one back before the loop does. False when the loop has already
     * taken it: then it arrives as a user message, as sent.
     */
    fun withdraw(id: String): Boolean = queue.removeIf { it.id == id }

    /** Everything waiting, in the order it was sent, leaving nothing. */
    fun drain(): List<Pending> = buildList {
        while (true) add(queue.poll() ?: break)
    }

    fun isEmpty(): Boolean = queue.isEmpty()
}
