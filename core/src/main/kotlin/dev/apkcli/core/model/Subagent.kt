package dev.apkcli.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a subagent did, for its card in the chat that dispatched it: who ran,
 * each step it took, what it answered and how it ended. Built from the
 * subagent's own events while it runs, posted to the chat as it changes, and
 * kept with the dispatch call's result, so a reopened chat shows the same card.
 *
 * Not the task: that is in the dispatch call's arguments, whole. Not sent to
 * the model either: the adapters build tool messages from the result's text.
 *
 * Bounded, and never silently: a step's text is kept up to [MAX_STEP_TEXT]
 * characters with its full length beside it, the answer up to [MAX_ANSWER],
 * and past [MAX_STEPS] the oldest steps go and are counted in [stepsDropped].
 * The subagent's own session, [sessionId], keeps everything whole.
 */
@Serializable
data class SubagentRun(
    /** The id it was dispatched as: an Explorer, a worker, or an agent of the user's own. */
    val agentId: String,
    /** Its name, as its questions in the approval sheet carry it. */
    val agentName: String,
    /** It only reads: an Explorer, or a read-only agent of the user's. */
    val readOnly: Boolean,
    val steps: List<Step> = emptyList(),
    /** Steps dropped from the front to keep the record bounded. */
    val stepsDropped: Int = 0,
    /** The text of its last message so far: its answer once it is done. */
    val answer: String = "",
    /** Full length of the answer, when [answer] holds only its beginning. */
    val answerLength: Int = 0,
    val state: State = State.RUNNING,
    /** Why it failed, in English, as the provider or the loop put it. */
    val error: String? = null,
    /** Its own session, archived: every message and every tool output whole. */
    val sessionId: String? = null,
    /** Model rounds so far, and the cap it was given. */
    val iterations: Int = 0,
    val maxIterations: Int = 0,
    /** Its last reply stopped at the output limit, so [answer] may end mid-sentence. */
    val outputLimited: Boolean = false,
) {
    /** The answer was cut to [MAX_ANSWER]. */
    val answerCut: Boolean get() = answerLength > answer.length

    @Serializable
    enum class State {
        @SerialName("running") RUNNING,
        @SerialName("done") DONE,

        /** Stopped at its round cap; [answer] may be incomplete. */
        @SerialName("limit") LIMIT,
        @SerialName("failed") FAILED,
    }

    @Serializable
    data class Step(
        val kind: Kind,
        /** A tool step's call and tool. */
        val callId: String? = null,
        val tool: String? = null,
        /** The beginning of the call's arguments, from which the card reads its path or command. */
        val argsHead: String = "",
        /**
         * Reasoning, or what it said on the way, or — for a tool — its output
         * as the subagent's model received it; at most [MAX_STEP_TEXT].
         */
        val text: String = "",
        /** Full length of [text] before it was cut. */
        val textLength: Int = 0,
        val status: Status = Status.RUNNING,
        val durationMs: Long = 0,
        /** An edit's line counts. */
        val added: Int = 0,
        val removed: Int = 0,
    ) {
        /** [text] holds only the beginning. */
        val textCut: Boolean get() = textLength > text.length

        @Serializable
        enum class Kind {
            @SerialName("tool") TOOL,
            @SerialName("thinking") THINKING,
            @SerialName("text") TEXT,
        }

        @Serializable
        enum class Status {
            /** The model is still writing the call. */
            @SerialName("writing") WRITING,
            @SerialName("running") RUNNING,

            /** Waiting for the user in the chat's approval sheet. */
            @SerialName("asking") ASKING,
            @SerialName("ok") OK,
            @SerialName("error") ERROR,
            @SerialName("denied") DENIED,
        }
    }

    companion object {
        /** Steps kept; a subagent has at most 60 rounds of a few calls each. */
        const val MAX_STEPS = 150

        /** Characters of a step's text kept: enough to read, not a copy of every file it read. */
        const val MAX_STEP_TEXT = 2_000

        /** Characters of a call's arguments kept: the path or the command is at their start. */
        const val MAX_ARGS_HEAD = 400

        /** Characters of the answer kept for the card; the model gets it whole. */
        const val MAX_ANSWER = 20_000
    }
}
