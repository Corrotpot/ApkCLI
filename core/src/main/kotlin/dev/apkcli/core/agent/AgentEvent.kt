package dev.apkcli.core.agent

import dev.apkcli.core.llm.LlmError
import dev.apkcli.core.model.ConversationDigest
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.ToolCallStatus

/**
 * What the agent emits while it works.
 *
 * A flow of events rather than a suspend function returning a result, because
 * everything interesting happens *during* a turn: tokens arrive one at a time,
 * tool calls need approving mid-flight, and the user may stop it. A function
 * that returned only the final message would make the UI a progress spinner.
 *
 * The events carry ids rather than whole objects where they can, so the UI can
 * update one message in a list of hundreds without re-diffing all of them.
 */
sealed interface AgentEvent {

    /** The user's message has been persisted and the request is being built. */
    data class UserMessage(val message: Message) : AgentEvent

    /**
     * An assistant message exists and will be filled in by the deltas that
     * follow. Emitted before the first token so the UI can show the bubble.
     */
    data class AssistantStarted(val messageId: String, val iteration: Int) : AgentEvent

    data class TextDelta(val messageId: String, val text: String) : AgentEvent

    data class ThinkingDelta(val messageId: String, val text: String) : AgentEvent

    /** The text streamed so far for [messageId] was reasoning after all; move it. */
    data class TextWasThinking(val messageId: String) : AgentEvent

    /** Reasoning finished; [tokens] is the provider's count where available. */
    data class ThinkingFinished(val messageId: String, val tokens: Int) : AgentEvent

    /** The model began writing a call; its arguments follow ([ToolCallProgress]). */
    data class ToolCallStarted(
        val messageId: String,
        val callId: String,
        val name: String,
        val argumentsJson: String,
    ) : AgentEvent

    /**
     * The model is still writing a call: how much of its arguments has
     * arrived, and their [head], from which the card reads the path or the
     * command before the call is complete. A few per second at most.
     *
     * A large write or edit spends most of its time here, in the model, and a
     * card that showed this as "running" read as a file write taking seconds.
     */
    data class ToolCallProgress(val messageId: String, val callId: String, val chars: Int, val head: String) : AgentEvent

    /** A call's arguments are complete and it starts now; calls before it in the batch have finished. */
    data class ToolCallRunning(val callId: String) : AgentEvent

    /**
     * What a running call has printed so far: the last screenful, replacing
     * the previous one, so a dropped event costs nothing.
     *
     * Tools run in `async` groups and a flow may not be emitted into from
     * several coroutines, so tools do not emit this: they post to
     * [ToolContext.onOutput], and the loop, in the flow's own coroutine,
     * passes it on while the group runs. "Awaiting approval" still travels
     * through ApprovalGate.request, which carries the callId for that reason.
     */
    data class ToolOutput(val callId: String, val rows: List<dev.apkcli.core.runtime.TerminalBuffer.Row>) : AgentEvent

    data class ToolCallFinished(
        val callId: String,
        val name: String,
        val status: ToolCallStatus,
        val result: ToolResult,
        val durationMs: Long,
    ) : AgentEvent

    /** A message reached its final state and is stored. */
    data class MessageCompleted(val message: Message) : AgentEvent

    /** Files a tool changed, so the editor can reload them. */
    data class FilesChanged(val paths: List<String>) : AgentEvent

    data class ContextUpdated(val accounting: ContextBudget.Accounting) : AgentEvent

    /** A compaction started: [manual] for /compact, else the app's own as the window fills. */
    data class CompactionStarted(val reason: String, val manual: Boolean = false) : AgentEvent

    /** [tokensFreed]: what the window gave back, net of what the summary grew by. */
    data class CompactionFinished(
        val digest: ConversationDigest,
        val archivedMessages: Int,
        val tokensFreed: Long,
    ) : AgentEvent

    /** A compaction did not happen, and why; nothing changed. */
    data class CompactionSkipped(val why: Why, val manual: Boolean) : AgentEvent {
        enum class Why {
            /** /compact: no conversation since the last summary. */
            NOTHING_NEW,

            /** /compact: too little conversation to be worth a summary (Compactor.MIN_MANUAL_TOKENS). */
            TOO_SHORT,

            /** /compact: the summary came out no shorter than what it would replace. */
            NOT_SHORTER,

            /** The window is nearly full, and nothing could be archived: the latest turns are most of it. */
            NOTHING_ARCHIVABLE,
        }
    }

    /**
     * /compact could not write its summary. Nothing was archived. [detail]
     * is the provider's or the parser's own words, when there are any.
     */
    data class CompactionFailed(val kind: Kind, val detail: String? = null) : AgentEvent {
        enum class Kind {
            /** No connection or no model to ask. */
            NO_MODEL,

            /** The model was asked and returned no usable summary. */
            NO_SUMMARY,
        }
    }

    /**
     * A subagent dispatched by call [callId], as its run stands: each one
     * replaces the last, so a dropped one costs nothing. Its own events are
     * not forwarded; this is them, summed up, a few times a second at most.
     * Posted like [ToolOutput], through [ToolContext.onSubagent].
     */
    data class SubagentProgress(val callId: String, val run: dev.apkcli.core.model.SubagentRun) : AgentEvent

    /** A subagent was dispatched; its own events are not forwarded. */
    data class SubagentStarted(val agentId: String, val prompt: String) : AgentEvent

    data class SubagentFinished(val agentId: String, val summary: String) : AgentEvent

    data class Failed(val error: LlmError, val recoverable: Boolean) : AgentEvent

    /**
     * The turn is over.
     *
     * @param iterations how many model round trips it took.
     * @param stoppedBy why it ended — useful when it hit the iteration cap
     *   rather than finishing, which usually means the agent was looping.
     */
    data class TurnFinished(
        val iterations: Int,
        val stoppedBy: StopReason,
    ) : AgentEvent

    enum class StopReason {
        /** The model answered with no further tool calls. */
        COMPLETED,

        /** Hit [dev.apkcli.core.model.AgentDefinition.maxIterations]. */
        ITERATION_LIMIT,

        /** The user pressed stop. */
        CANCELLED,

        /** A request failed in a way retrying would not fix. */
        ERROR,

        /** A supervised agent is waiting for the user before continuing. */
        AWAITING_USER,
    }
}
