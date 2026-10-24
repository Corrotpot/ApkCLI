package dev.apkcli.core.agent

import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.Role

/**
 * The window as both protocols accept it: every call answered right after
 * the message that made it, and no answer without its call.
 *
 * A stopped turn leaves calls with no result: the user pressed stop while
 * they ran, or while the model was still writing them. The next request then
 * carried a call nothing answered, which Anthropic and OpenAI both refuse,
 * so after one stop every later request in the chat failed. Here such a call
 * is answered with [STOPPED_RESULT], and a result whose call is not in the
 * window is left out. Only in the request: the stored chat keeps showing the
 * call as stopped, which it was. Kiro's wire pairs calls again on its own
 * (KiroWire.canonical) and finds nothing left to do.
 */
internal object Transcript {

    /** What a call that never finished is answered with. */
    const val STOPPED_RESULT =
        "No result: the turn was stopped before this call finished. It may not have run, or may " +
            "have run in part; check its effect before relying on it or repeating it."

    /** [messages], oldest first, with every call answered; the same list when nothing needed it. */
    fun answered(messages: List<Message>): List<Message> {
        val out = ArrayList<Message>(messages.size + 1)
        var changed = false
        // The last message that made calls, while its results are being read.
        var asking: Message? = null
        val answered = HashSet<String>()

        fun close() {
            val calls = asking ?: return
            asking = null
            val missing = calls.toolUses.filter { it.callId !in answered }
            if (missing.isEmpty()) return
            changed = true
            val stopped = missing.map { Block.ToolResult(it.callId, it.name, STOPPED_RESULT, isError = true) }
            // After the calls, out holds only results that answer them; the
            // last such message takes the rest, in the order of the calls.
            val last = out.last()
            if (last.role == Role.TOOL) {
                val byCall = (last.toolResults + stopped).associateBy { it.callId }
                out[out.lastIndex] = last.copy(content = calls.toolUses.mapNotNull { byCall[it.callId] })
            } else {
                out += Message(
                    id = "${calls.id}-stopped",
                    sessionId = calls.sessionId,
                    seq = -1,
                    role = Role.TOOL,
                    content = stopped,
                )
            }
        }

        for (message in messages) {
            when (message.role) {
                Role.TOOL -> {
                    val open = asking?.toolUses?.mapTo(HashSet()) { it.callId }.orEmpty()
                    val kept = message.content.filter { it is Block.ToolResult && it.callId in open && answered.add(it.callId) }
                    when {
                        kept.size == message.content.size -> out += message
                        kept.isEmpty() -> changed = true
                        else -> {
                            changed = true
                            out += message.copy(content = kept)
                        }
                    }
                }
                Role.ASSISTANT -> {
                    close()
                    out += message
                    if (message.toolUses.isNotEmpty()) {
                        asking = message
                        answered.clear()
                    }
                }
                else -> {
                    close()
                    out += message
                }
            }
        }
        close()
        return if (changed) out else messages
    }
}

/** What a reply's finish reason says about how it ended, beyond "done" and "calls follow". */
internal object StopReasons {

    /** The reply stopped at the output limit: `length` (OpenAI), `max_tokens` (Anthropic, Bedrock), and kin. */
    fun outputLimit(reason: String?): Boolean = reason?.lowercase() in OUTPUT_LIMIT

    /** The provider's content filter stopped it. */
    fun filtered(reason: String?): Boolean = reason?.lowercase() in FILTERED

    /** The notice for a reply that ended with [reason], or null for an ordinary end. */
    fun notice(reason: String?, outputLimit: Int): Block.Notice? = when {
        outputLimit(reason) -> Block.Notice(Block.Notice.OUTPUT_LIMIT, limit = outputLimit)
        filtered(reason) -> Block.Notice(Block.Notice.FILTERED)
        else -> null
    }

    private val OUTPUT_LIMIT = setOf(
        "length", "max_tokens", "max_output_tokens", "model_length", "model_context_window_exceeded",
    )

    private val FILTERED = setOf(
        "content_filter", "content_filtered", "refusal", "guardrail_intervened",
        "safety", "recitation", "prohibited_content", "blocklist", "spii",
    )
}
