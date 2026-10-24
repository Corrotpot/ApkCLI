package dev.apkcli.core.agent

import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.SubagentRun
import dev.apkcli.core.model.SubagentRun.Companion.MAX_ANSWER
import dev.apkcli.core.model.SubagentRun.Companion.MAX_ARGS_HEAD
import dev.apkcli.core.model.SubagentRun.Companion.MAX_STEPS
import dev.apkcli.core.model.SubagentRun.Companion.MAX_STEP_TEXT
import dev.apkcli.core.model.SubagentRun.Step
import dev.apkcli.core.model.SubagentRun.Step.Kind
import dev.apkcli.core.model.SubagentRun.Step.Status

/**
 * A subagent's [SubagentRun], kept up to date from its events: the card in
 * the chat that dispatched it reads snapshots of it while it runs, and the
 * last one is stored with the dispatch call's result.
 *
 * A round's text goes into the steps when a call follows it — it was said on
 * the way — and is the answer when none does. Reasoning is a step of its own.
 *
 * Events arrive from the subagent's loop, questions from its tools on other
 * threads, and snapshots are taken by the chat's ticker; every method holds
 * the lock, and a snapshot shares nothing that changes afterwards.
 */
internal class SubagentTracker(
    private val agentId: String,
    private val agentName: String,
    private val readOnly: Boolean,
    private val sessionId: String?,
    private val maxIterations: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val steps = ArrayList<Step>()
    private var dropped = 0

    /** The current round's text, at most [MAX_ANSWER] of it, and its full length. */
    private val text = StringBuilder()
    private var textLength = 0

    /** The last step is reasoning still being written, since [thinkingSince]. */
    private var thinking = false
    private var thinkingSince = 0L

    private var iterations = 0
    private var state = SubagentRun.State.RUNNING
    private var error: String? = null
    private var outputLimited = false
    private var changed = true

    fun on(event: AgentEvent): Unit = synchronized(lock) {
        when (event) {
            is AgentEvent.AssistantStarted -> {
                // A new round: what the last one said led to its calls.
                endThinking()
                flushText()
                iterations = maxOf(iterations, event.iteration)
            }
            is AgentEvent.TextDelta -> appendText(event.text)
            is AgentEvent.ThinkingDelta -> appendThinking(event.text)
            is AgentEvent.TextWasThinking -> {
                val moved = text.toString()
                text.setLength(0)
                textLength = 0
                appendThinking(moved)
            }
            is AgentEvent.ThinkingFinished -> endThinking()
            is AgentEvent.ToolCallStarted -> {
                endThinking()
                flushText()
                add(
                    Step(
                        kind = Kind.TOOL,
                        callId = event.callId,
                        tool = event.name,
                        argsHead = event.argumentsJson.take(MAX_ARGS_HEAD),
                        status = Status.WRITING,
                    ),
                )
            }
            is AgentEvent.ToolCallProgress -> update(event.callId) { step ->
                if (step.argsHead.length >= MAX_ARGS_HEAD) step else step.copy(argsHead = event.head.take(MAX_ARGS_HEAD))
            }
            is AgentEvent.ToolCallRunning -> update(event.callId) { step ->
                if (step.status == Status.WRITING) step.copy(status = Status.RUNNING) else step
            }
            is AgentEvent.ToolCallFinished -> update(event.callId) { step ->
                val diff = event.result.display as? ToolDisplay.Diff
                val output = event.result.content
                step.copy(
                    status = when {
                        step.status == Status.DENIED -> Status.DENIED
                        event.result.isError -> Status.ERROR
                        else -> Status.OK
                    },
                    text = output.take(MAX_STEP_TEXT),
                    textLength = output.length,
                    durationMs = event.durationMs,
                    added = diff?.addedLines ?: 0,
                    removed = diff?.removedLines ?: 0,
                )
            }
            // The arguments whole, once the round is written: the head that
            // came while they streamed may have stopped short of the path.
            is AgentEvent.MessageCompleted -> if (event.message.role == Role.ASSISTANT) {
                event.message.toolUses.forEach { use ->
                    update(use.callId) { it.copy(argsHead = use.argumentsJson.take(MAX_ARGS_HEAD)) }
                }
                // Whether this round, the last so far, stopped at its output limit.
                outputLimited = event.message.notices.any { it.kind == Block.Notice.OUTPUT_LIMIT }
            }
            is AgentEvent.Failed -> error = event.error.message
            is AgentEvent.TurnFinished -> {
                iterations = event.iterations
                finish(event.stoppedBy)
            }
            else -> return@synchronized
        }
        changed = true
    }

    /** Call [callId] waits in the chat's approval sheet. */
    fun asking(callId: String): Unit = synchronized(lock) {
        update(callId) { it.copy(status = Status.ASKING) }
        changed = true
    }

    /** The user answered about call [callId]. */
    fun answered(callId: String, allowed: Boolean): Unit = synchronized(lock) {
        update(callId) { it.copy(status = if (allowed) Status.RUNNING else Status.DENIED) }
        changed = true
    }

    /** It could not run at all, or broke off: [message] says why. */
    fun failed(message: String): Unit = synchronized(lock) {
        error = error ?: message
        finish(AgentEvent.StopReason.ERROR)
        changed = true
    }

    /** The run as it stands, or null when nothing changed since the last one taken. */
    fun takeIfChanged(): SubagentRun? = synchronized(lock) {
        if (!changed) return null
        changed = false
        snapshot()
    }

    fun snapshot(): SubagentRun = synchronized(lock) {
        SubagentRun(
            agentId = agentId,
            agentName = agentName,
            readOnly = readOnly,
            steps = steps.toList(),
            stepsDropped = dropped,
            answer = text.toString(),
            answerLength = textLength,
            state = state,
            error = error,
            sessionId = sessionId,
            iterations = iterations,
            maxIterations = maxIterations,
            outputLimited = outputLimited,
        )
    }

    private fun finish(stoppedBy: AgentEvent.StopReason) {
        endThinking()
        state = when {
            error != null || stoppedBy == AgentEvent.StopReason.ERROR -> SubagentRun.State.FAILED
            stoppedBy == AgentEvent.StopReason.ITERATION_LIMIT -> SubagentRun.State.LIMIT
            else -> SubagentRun.State.DONE
        }
        // Whatever was still under way will not finish now.
        for (i in steps.indices) {
            val step = steps[i]
            if (step.status == Status.WRITING || step.status == Status.RUNNING || step.status == Status.ASKING) {
                steps[i] = step.copy(status = Status.ERROR)
            }
        }
    }

    private fun appendText(delta: String) {
        val room = MAX_ANSWER - text.length
        if (room > 0) text.append(delta, 0, minOf(room, delta.length))
        textLength += delta.length
    }

    private fun appendThinking(delta: String) {
        if (delta.isEmpty()) return
        if (!thinking) {
            add(Step(kind = Kind.THINKING, status = Status.RUNNING))
            thinking = true
            thinkingSince = now()
        }
        val last = steps.lastIndex
        val step = steps[last]
        val room = MAX_STEP_TEXT - step.text.length
        steps[last] = step.copy(
            text = if (room > 0) step.text + delta.take(room) else step.text,
            textLength = step.textLength + delta.length,
        )
    }

    private fun endThinking() {
        if (!thinking) return
        thinking = false
        val last = steps.lastIndex
        if (last >= 0 && steps[last].kind == Kind.THINKING) {
            steps[last] = steps[last].copy(status = Status.OK, durationMs = now() - thinkingSince)
        }
    }

    /** The current round's text, as a step: it was followed by a call. */
    private fun flushText() {
        if (text.isNotBlank()) {
            // Past MAX_ANSWER only the length was counted; otherwise the
            // length is the trimmed text's own.
            val capped = textLength > text.length
            val said = text.toString().trim()
            add(
                Step(
                    kind = Kind.TEXT,
                    text = said.take(MAX_STEP_TEXT),
                    textLength = if (capped) textLength else said.length,
                    status = Status.OK,
                ),
            )
        }
        text.setLength(0)
        textLength = 0
    }

    private fun add(step: Step) {
        steps += step
        if (steps.size > MAX_STEPS) {
            steps.removeAt(0)
            dropped++
        }
    }

    private fun update(callId: String, change: (Step) -> Step) {
        val i = steps.indexOfLast { it.callId == callId }
        if (i >= 0) steps[i] = change(steps[i])
    }
}
