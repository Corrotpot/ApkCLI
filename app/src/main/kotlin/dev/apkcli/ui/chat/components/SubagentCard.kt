package dev.apkcli.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.apkcli.R
import dev.apkcli.core.AgentRuntime
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.core.model.SubagentRun
import dev.apkcli.core.model.SubagentRun.Step.Kind
import dev.apkcli.core.model.SubagentRun.Step.Status
import dev.apkcli.core.model.ToolCallStatus
import dev.apkcli.core.store.BuiltInAgents
import dev.apkcli.ui.chat.ChatViewModel
import dev.apkcli.ui.chat.find.LocalFindItem
import dev.apkcli.ui.chat.find.LocalFindMarks
import dev.apkcli.ui.chat.find.rememberFindable
import dev.apkcli.ui.chat.markdown.MarkdownText
import dev.apkcli.ui.chat.markdown.markdownFindSegments
import dev.apkcli.ui.components.Spinner
import dev.apkcli.ui.components.rememberFollowingScrollState
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.theme.Design
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/*
 * A subagent's card: design/prototype/app.js subHtml(). A window onto the
 * subagent's own run inside the chat, as a desktop agent shows one.
 *
 * The head is a tool card's: "Subagent", what kind it is (an Explorer only
 * reads; a worker changes files under the chat's mode), the task's first
 * line, the clock, a chevron. Under it:
 *
 *  - while the main model writes the task, the task as it comes;
 *  - while the subagent runs, its last three steps, newest at the bottom:
 *    what it is doing, without opening anything;
 *  - open, the whole window: the task the main model wrote, every step (a
 *    tap shows its output), its answer. The window scrolls inside itself and
 *    follows a running subagent's newest step.
 *
 * Done, it folds to its head. What the record holds is bounded
 * (SubagentRun), and where it holds less than there was the card says so.
 */

/** The dispatch call's arguments: the task as the main model wrote it, whole. */
internal class SubagentArgs(val agent: String?, val prompt: String?, val files: List<String>) {
    companion object {
        fun parse(json: String): SubagentArgs {
            val args = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
                ?: return SubagentArgs(null, null, emptyList())
            fun str(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull
            val files = (args["contextFiles"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            return SubagentArgs(str("agent")?.trim(), str("prompt"), files)
        }
    }
}

/**
 * Find in the chat's keys for a subagent card's pieces. All start with
 * [prefix]: a match under it opens the card.
 */
internal object SubagentFind {
    fun prefix(callId: String) = "tool:$callId:sub"
    fun prompt(callId: String) = "${prefix(callId)}:p"
    fun files(callId: String) = "${prefix(callId)}:f"
    fun step(callId: String, index: Int) = "${prefix(callId)}:s$index"
    fun said(callId: String, index: Int) = "${prefix(callId)}:x$index"
    fun answer(callId: String) = "${prefix(callId)}:a"

    /** What the card shows, piece by piece, under the keys it draws them with. */
    fun segments(callId: String, args: SubagentArgs, run: SubagentRun?): List<Pair<String, String>> = buildList {
        args.prompt?.takeIf { it.isNotBlank() }?.let { add(prompt(callId) to it) }
        if (args.files.isNotEmpty()) add(files(callId) to filesLine(args.files))
        run?.steps?.forEachIndexed { i, step ->
            when (step.kind) {
                Kind.TOOL -> step.target()?.let { add(step(callId, i) to it) }
                Kind.TEXT -> addAll(markdownFindSegments(step.text, said(callId, i)))
                Kind.THINKING -> Unit
            }
        }
        run?.answer?.takeIf { it.isNotBlank() && run.state != SubagentRun.State.RUNNING }?.let {
            addAll(markdownFindSegments(it, answer(callId)))
        }
    }
}

private fun filesLine(files: List<String>) = files.joinToString(" · ")

/** What a step acted on, read from the beginning of its arguments as a tool card reads the whole. */
internal fun SubagentRun.Step.target(): String? =
    tool?.let { name -> toolTarget(name, argsHead) ?: toolTargetPartial(name, argsHead) }

/** It only reads; true for an Explorer before its run has said so. */
private fun readOnly(args: SubagentArgs, run: SubagentRun?): Boolean =
    run?.readOnly ?: (args.agent == BuiltInAgents.EXPLORER)

/** An agent of the user's own, named as such; null for the built-in kinds. */
private fun customName(args: SubagentArgs, run: SubagentRun?): String? {
    val id = run?.agentId ?: args.agent ?: return null
    if (id == BuiltInAgents.EXPLORER || id == AgentRuntime.WORKER_ID || id.startsWith("builtin-")) return null
    return run?.agentName ?: id
}

@Composable
internal fun SubagentCard(
    call: ChatViewModel.LiveToolCall,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    val args = remember(call.argumentsJson) { SubagentArgs.parse(call.argumentsJson) }
    val run = (call.display as? ToolDisplay.Subagent)?.run ?: call.subagent
    val prompt = args.prompt ?: call.partialPrompt.orEmpty()

    val preparing = call.preparing
    val askingToStart = call.status == ToolCallStatus.PENDING
    val running = !preparing && (call.status == ToolCallStatus.RUNNING || call.status == ToolCallStatus.APPROVED)
    val stepAsks = running && run?.steps?.any { it.status == Status.ASKING } == true
    val denied = call.status == ToolCallStatus.DENIED
    val stopped = call.status == ToolCallStatus.ABORTED
    val failed = !running && (call.isError || call.status == ToolCallStatus.ERROR || run?.state == SubagentRun.State.FAILED)
    val live = preparing || running || askingToStart

    var expanded by remember(call.callId) { mutableStateOf(initiallyExpanded) }
    // One that fails on screen opens once, as a failed call does.
    LaunchedEffect(call.callId, failed) {
        if (failed) expanded = true
    }
    val opensForFind = LocalFindMarks.current?.opens(LocalFindItem.current, SubagentFind.prefix(call.callId)) == true
    val open = expanded || opensForFind

    // The writing clock counts the main model writing the task; then the
    // subagent's own time.
    val clockFrom = if (preparing) call.writingSince else call.runningSince
    val elapsed by produceState<Long?>(null, call.callId, preparing, running, clockFrom) {
        value = null
        if (!(preparing || running) || clockFrom == null) return@produceState
        while (true) {
            value = System.currentTimeMillis() - clockFrom
            delay(1_000)
        }
    }
    val ticking = elapsed?.takeIf { it >= 1_000 }?.let(::elapsedClock)

    val title = stringResource(R.string.tool_name_subagent)
    val statusText = when {
        preparing -> ticking?.let { stringResource(R.string.tool_status_writing_for, it) } ?: stringResource(R.string.tool_status_writing)
        askingToStart || stepAsks -> stringResource(R.string.tool_status_asking)
        running -> ticking ?: stringResource(R.string.tool_status_running)
        denied -> stringResource(R.string.tool_status_denied)
        stopped -> stringResource(R.string.tool_status_stopped)
        else -> call.durationMs?.let { durationText(it) }
            ?: stringResource(if (failed) R.string.tool_result_failed else R.string.tool_result_ok)
    }
    val spoken = when {
        live -> stringResource(R.string.a11y_tool_running, title)
        failed -> stringResource(R.string.a11y_tool_failed, title)
        else -> stringResource(R.string.a11y_tool_succeeded, title, statusText)
    }
    val kind = customName(args, run) ?: stringResource(
        if (readOnly(args, run)) R.string.subagent_kind_read else R.string.subagent_kind_write,
    )

    val shape = RoundedCornerShape(Design.Radius.ToolCard)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Design.Colors.Surface1)
            .border(
                1.dp,
                when {
                    failed -> Design.Colors.Error.copy(alpha = 0.35f)
                    live -> Design.Colors.BorderStrong
                    else -> Design.Colors.Border
                },
                shape,
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !open }
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
                .clearAndSetSemantics { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Codicons.Hubot, contentDescription = null, tint = Design.Colors.Accent, modifier = Modifier.size(Design.Size.IconSmall))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Never cut: where the row is tight the kind gives way.
                    Text(title, style = Design.Type.LabelMedium, color = Design.Colors.Text, maxLines = 1, softWrap = false)
                    KindChip(kind, Modifier.weight(1f, fill = false))
                }
                firstLine(prompt)?.let { task ->
                    Text(
                        task,
                        style = Design.Type.Caption,
                        color = Design.Colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HeadGlyph(
                    working = (preparing || running) && !stepAsks,
                    asking = askingToStart || stepAsks,
                    denied = denied,
                    stopped = stopped,
                    failed = failed,
                    limited = run?.state == SubagentRun.State.LIMIT,
                )
                Text(statusText, style = Design.Type.LabelTiny, color = Design.Colors.TextTertiary, maxLines = 1)
            }
            Icon(
                if (open) Codicons.ChevronUp else Codicons.ChevronDown,
                contentDescription = null,
                tint = Design.Colors.TextTertiary,
                modifier = Modifier.size(Design.Size.IconSmall),
            )
        }

        when {
            open -> FullWindow(
                callId = call.callId,
                args = args,
                prompt = prompt,
                run = run,
                running = running,
                writing = preparing,
                stopped = stopped,
                denied = denied,
                failed = failed,
            )
            preparing || askingToStart -> MiniWindow(onClick = { expanded = true }) { TaskBeingWritten(prompt) }
            running -> MiniWindow(onClick = { expanded = true }) { LastSteps(run) }
        }
    }
}

private fun firstLine(text: String): String? = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

@Composable
private fun KindChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Design.Type.LabelTiny,
        color = Design.Colors.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Design.Colors.Surface3)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The head's glyph: a shape per state, not a colour alone. */
@Composable
private fun HeadGlyph(working: Boolean, asking: Boolean, denied: Boolean, stopped: Boolean, failed: Boolean, limited: Boolean) {
    val size = Modifier.size(Design.Size.IconSmall)
    when {
        asking -> Icon(Codicons.Question, contentDescription = null, tint = Design.Colors.Warning, modifier = size)
        working -> Spinner(color = Design.Colors.Accent)
        denied -> Icon(Codicons.CircleSlash, contentDescription = null, tint = Design.Colors.Warning, modifier = size)
        stopped -> Icon(Codicons.DebugStop, contentDescription = null, tint = Design.Colors.TextTertiary, modifier = size)
        failed -> Icon(Codicons.Error, contentDescription = null, tint = Design.Colors.Error, modifier = size)
        limited -> Icon(Codicons.Warning, contentDescription = null, tint = Design.Colors.Warning, modifier = size)
        else -> Icon(Codicons.Pass, contentDescription = null, tint = Design.Colors.Success, modifier = size)
    }
}

/** `.sub-win`: black like the terminal, inside the card's surface. */
@Composable
private fun MiniWindow(onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Design.Radius.Box)
    Column(
        Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Design.Colors.Bg)
            .border(1.dp, Design.Colors.Border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        content = content,
    )
}

/** The task as the main model writes it: its end, where the words are coming. */
@Composable
private fun TaskBeingWritten(prompt: String) {
    Label(stringResource(R.string.subagent_task), Modifier.padding(top = 6.dp))
    val tail = if (prompt.length > TAIL_CHARS) "…" + prompt.takeLast(TAIL_CHARS) else prompt
    Text(
        tail,
        style = Design.Type.BodySmall,
        color = Design.Colors.TextBody,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
}

/** Its last steps, newest at the bottom, and what it waits on when no step is running. */
@Composable
private fun LastSteps(run: SubagentRun?) {
    val steps = run?.steps.orEmpty().filter { it.kind != Kind.TEXT }
    val busy = steps.any { it.status == Status.RUNNING || it.status == Status.WRITING || it.status == Status.ASKING }
    val speaking = run != null && run.answer.isNotBlank() && !busy
    val idle = !busy
    // Three rows at most, so the strip does not jump.
    steps.takeLast(if (idle) LIVE_ROWS - 1 else LIVE_ROWS).forEach { step -> StepLine(step) }
    when {
        speaking -> PendingLine(Codicons.CommentDiscussion, stringResource(R.string.subagent_writing))
        idle -> PendingLine(null, stringResource(R.string.subagent_waiting_model))
    }
}

@Composable
private fun StepLine(step: SubagentRun.Step, onClick: (() -> Unit)? = null, findKey: String? = null) {
    val thinking = step.kind == Kind.THINKING
    StepRow(
        icon = if (thinking) Codicons.Thinking else toolIcon(step.tool.orEmpty()),
        accent = thinking,
        name = if (thinking) {
            stringResource(if (step.status == Status.RUNNING) R.string.chat_thinking else R.string.chat_reasoning)
        } else {
            toolTitle(step.tool.orEmpty())
        },
        target = if (thinking) null else step.target(),
        status = step.status,
        durationMs = step.durationMs,
        added = step.added,
        removed = step.removed,
        onClick = onClick,
        findKey = findKey,
    )
}

/** One row: the tool's icon, its name and target, and how it went. Primitive arguments, so unchanged rows skip. */
@Composable
private fun StepRow(
    icon: ImageVector,
    accent: Boolean,
    name: String,
    target: String?,
    status: Status,
    durationMs: Long,
    added: Int,
    removed: Int,
    onClick: (() -> Unit)?,
    findKey: String?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (accent) Design.Colors.Accent else Design.Colors.TextTertiary,
            modifier = Modifier.size(Design.Size.IconTiny),
        )
        Text(name, style = Design.Type.LabelSmall, color = Design.Colors.TextSecondary, maxLines = 1, softWrap = false)
        Box(Modifier.weight(1f)) {
            if (target != null) {
                val findable = rememberFindable(findKey ?: "", target)
                Text(
                    findable.text,
                    style = Design.Type.MonoCaption,
                    color = Design.Colors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = findable.onTextLayout,
                    modifier = findable.modifier,
                )
            }
        }
        if (added > 0 || removed > 0) DiffStat(added, removed)
        StepStatus(status, durationMs)
    }
}

@Composable
private fun StepStatus(status: Status, durationMs: Long) {
    val size = Modifier.size(Design.Size.IconSmall)
    when (status) {
        Status.WRITING, Status.RUNNING -> Spinner(color = Design.Colors.Accent)
        Status.ASKING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Codicons.Question, contentDescription = null, tint = Design.Colors.Warning, modifier = size)
            Text(stringResource(R.string.subagent_step_waits), style = Design.Type.LabelTiny, color = Design.Colors.Warning, maxLines = 1)
        }
        Status.DENIED -> Icon(Codicons.CircleSlash, contentDescription = stringResource(R.string.tool_status_denied), tint = Design.Colors.Warning, modifier = size)
        Status.ERROR -> Icon(Codicons.Error, contentDescription = stringResource(R.string.tool_result_failed), tint = Design.Colors.Error, modifier = size)
        Status.OK -> Text(durationText(durationMs), style = Design.Type.LabelTiny, color = Design.Colors.TextTertiary, maxLines = 1)
    }
}

/** A row for what it is waiting on: its model, or the words it is writing. */
@Composable
private fun PendingLine(icon: ImageVector?, text: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(Design.Size.IconTiny), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Design.Colors.Accent, modifier = Modifier.size(Design.Size.IconTiny))
            } else {
                Spinner(color = Design.Colors.Accent)
            }
        }
        Text(text, style = Design.Type.LabelSmall, color = Design.Colors.TextSecondary, maxLines = 1, modifier = Modifier.weight(1f))
        if (icon != null) Spinner(color = Design.Colors.Accent)
    }
}

/** The whole window: the task, every step, the answer, and how it ended. */
@Composable
private fun FullWindow(
    callId: String,
    args: SubagentArgs,
    prompt: String,
    run: SubagentRun?,
    running: Boolean,
    writing: Boolean,
    stopped: Boolean,
    denied: Boolean,
    failed: Boolean,
) {
    val shape = RoundedCornerShape(Design.Radius.Box)
    val scroll = rememberFollowingScrollState(following = running || writing)
    val marks = LocalFindMarks.current
    val item = LocalFindItem.current
    Column(
        Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Design.Colors.Bg)
            .border(1.dp, Design.Colors.Border, shape)
            .heightIn(max = WINDOW_MAX)
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The task, as the main model wrote it. A long one shows four lines
        // until asked, or while a search has a match in it.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label(stringResource(R.string.subagent_task))
            val long = prompt.length > LONG_TASK_CHARS || prompt.count { it == '\n' } >= LONG_TASK_LINES
            var whole by remember(callId) { mutableStateOf(false) }
            val matched = marks?.opens(item, SubagentFind.prompt(callId)) == true
            val all = !long || whole || matched
            val findable = rememberFindable(SubagentFind.prompt(callId), prompt)
            Text(
                findable.text,
                style = Design.Type.BodySmall,
                color = Design.Colors.TextBody,
                maxLines = if (all) Int.MAX_VALUE else LONG_TASK_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = findable.onTextLayout,
                modifier = findable.modifier,
            )
            if (long && !writing) {
                Text(
                    stringResource(if (all) R.string.subagent_show_less else R.string.subagent_show_all),
                    style = Design.Type.LabelSmall,
                    color = Design.Colors.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button) { whole = !all }
                        .padding(vertical = 2.dp),
                )
            }
            if (args.files.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.subagent_start_from), style = Design.Type.MonoCaption, color = Design.Colors.TextTertiary)
                    val files = rememberFindable(SubagentFind.files(callId), filesLine(args.files))
                    Text(
                        files.text,
                        style = Design.Type.MonoCaption,
                        color = Design.Colors.TextTertiary,
                        onTextLayout = files.onTextLayout,
                        modifier = files.modifier,
                    )
                }
            }
        }

        val steps = run?.steps.orEmpty()
        if (steps.isNotEmpty() || running) {
            Column {
                Label(
                    if (steps.isEmpty()) stringResource(R.string.subagent_steps_none) else stringResource(R.string.subagent_steps, steps.size),
                    Modifier.padding(bottom = 6.dp),
                )
                val dropped = run?.stepsDropped ?: 0
                if (dropped > 0) {
                    Text(
                        pluralStringResource(R.plurals.subagent_steps_dropped, dropped, dropped),
                        style = Design.Type.Caption,
                        color = Design.Colors.TextTertiary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                steps.forEachIndexed { i, step ->
                    // Keyed by where the step is in the whole run, so a row
                    // keeps its own state as the oldest steps are dropped.
                    key(i + dropped) {
                        when (step.kind) {
                            Kind.TEXT -> Column(Modifier.padding(vertical = 4.dp)) {
                                MarkdownText(
                                    text = step.text,
                                    style = Design.Type.BodySmall,
                                    findPrefix = SubagentFind.said(callId, i),
                                )
                                if (step.textCut) CutNote(step.text.length, step.textLength)
                            }
                            else -> {
                                var shown by remember { mutableStateOf(false) }
                                val output = step.text.isNotBlank()
                                StepLine(
                                    step,
                                    onClick = if (output) ({ shown = !shown }) else null,
                                    findKey = SubagentFind.step(callId, i),
                                )
                                if (shown && output) StepOutput(step)
                            }
                        }
                    }
                }
                val busy = steps.any { it.status == Status.RUNNING || it.status == Status.WRITING || it.status == Status.ASKING }
                if (running && !busy) {
                    val words = run?.answer.orEmpty()
                    if (words.isBlank()) {
                        PendingLine(null, stringResource(R.string.subagent_waiting_model))
                    } else {
                        // What it is writing now: on the way, or its answer.
                        Text(
                            if (words.length > TAIL_CHARS * 3) "…" + words.takeLast(TAIL_CHARS * 3) else words,
                            style = Design.Type.BodySmall,
                            color = Design.Colors.TextSecondary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }

        if (run != null && run.state != SubagentRun.State.RUNNING && run.answer.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label(stringResource(R.string.subagent_answer))
                MarkdownText(text = run.answer, style = Design.Type.BodySmall, findPrefix = SubagentFind.answer(callId))
                if (run.answerCut) CutNote(run.answer.length, run.answerLength)
            }
        }

        val note = when {
            denied -> stringResource(R.string.subagent_denied) to Design.Colors.TextTertiary
            stopped -> stringResource(R.string.subagent_stopped) to Design.Colors.TextTertiary
            run?.state == SubagentRun.State.LIMIT ->
                stringResource(R.string.subagent_limit, run.maxIterations) to Design.Colors.Warning
            failed -> stringResource(R.string.subagent_failed, run?.error ?: stringResource(R.string.tool_result_failed)) to
                Design.Colors.Error
            // Finished, but its last reply stopped at the output limit.
            run?.outputLimited == true && run.state != SubagentRun.State.RUNNING ->
                stringResource(R.string.subagent_output_limit) to Design.Colors.Warning
            else -> null
        }
        note?.let { (text, color) -> Text(text, style = Design.Type.Caption, color = color) }
    }
}

/** A step's output as its model received it, or its reasoning; with what was left out said. */
@Composable
private fun StepOutput(step: SubagentRun.Step) {
    Column(Modifier.padding(start = 22.dp, top = 2.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (step.kind == Kind.THINKING) {
            val rule = Design.Colors.BorderStrong
            Text(
                step.text,
                style = Design.Type.BodySmall,
                color = Design.Colors.TextTertiary,
                modifier = Modifier
                    .drawBehind { drawRect(rule, size = Size(2.dp.toPx(), size.height)) }
                    .padding(start = 12.dp),
            )
        } else {
            val shape = RoundedCornerShape(Design.Radius.Box)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Design.Colors.TerminalBg)
                    .border(1.dp, Design.Colors.Border, shape)
                    .heightIn(max = OUTPUT_MAX),
            ) {
                Text(
                    step.text,
                    style = Design.Type.CodeSmall,
                    color = Design.Colors.Text,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        if (step.textCut) CutNote(step.text.length, step.textLength)
    }
}

/** "Showing 2,000 of 9,400 characters": the record holds less than there was; the subagent's session has it all. */
@Composable
private fun CutNote(shown: Int, total: Int) {
    val locale = LocalConfiguration.current.locales[0]
    Text(
        stringResource(R.string.subagent_text_cut, String.format(locale, "%,d", shown), String.format(locale, "%,d", total)),
        style = Design.Type.Caption,
        color = Design.Colors.TextTertiary,
    )
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Design.Type.Overline, color = Design.Colors.TextTertiary, modifier = modifier)
}

/** Rows a running subagent shows under its head. */
private const val LIVE_ROWS = 3

/** Characters of the task's end shown while it is written. */
private const val TAIL_CHARS = 220

/** A task this long shows four lines until asked. */
private const val LONG_TASK_CHARS = 200
private const val LONG_TASK_LINES = 4

/** The open window, at most; it scrolls inside. */
private val WINDOW_MAX = 420.dp

/** A step's output box, at most. */
private val OUTPUT_MAX = 160.dp
