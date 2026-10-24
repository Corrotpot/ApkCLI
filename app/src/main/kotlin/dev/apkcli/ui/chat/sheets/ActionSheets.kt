package dev.apkcli.ui.chat.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.apkcli.R
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.core.store.BuiltInAgents
import dev.apkcli.core.store.ChangeLedger
import dev.apkcli.ui.chat.ChatViewModel
import dev.apkcli.ui.chat.components.DiffView
import dev.apkcli.ui.chat.components.SubagentArgs
import dev.apkcli.ui.chat.components.toolTitle
import dev.apkcli.ui.components.Btn
import dev.apkcli.ui.components.BtnKind
import dev.apkcli.ui.components.Card
import dev.apkcli.ui.components.CardDivider
import dev.apkcli.ui.components.Collapse
import dev.apkcli.ui.components.ConfirmSheet
import dev.apkcli.ui.components.EmptyText
import dev.apkcli.ui.components.IconBtn
import dev.apkcli.ui.components.IconBtnSize
import dev.apkcli.ui.components.ListRow
import dev.apkcli.ui.components.Note
import dev.apkcli.ui.components.Sheet
import dev.apkcli.ui.components.SheetActions
import dev.apkcli.ui.components.SheetBody
import dev.apkcli.ui.components.SheetHead
import dev.apkcli.ui.components.Spinner
import dev.apkcli.ui.components.TileTone
import dev.apkcli.ui.components.pressable
import dev.apkcli.ui.files.FileTypeIcon
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.theme.Design
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/*
 * The sheets that ask the user something about what the agent did or wants
 * to do: approval, the change list, a checkpoint. design/prototype/app.js
 * approvalSheet(), changesSheet(), and the checkpoint confirmation.
 */

/**
 * A call the agent may not make without the user. The question names what
 * it does in the user's language — "Run a command?", "Write main.py?" — and
 * shows the command or the path itself, which is what is being approved.
 */
@Composable
fun ApprovalSheet(
    approval: ChatViewModel.PendingApproval,
    projectName: String,
    /** Why it asks, as the mode puts it. */
    why: String,
    onResolve: (allow: Boolean, alwaysAllow: Boolean) -> Unit,
    /**
     * The link under the buttons that lets this call through and stops the
     * asking: ticking its rule, or switching to Bypass Mode. Null for none.
     */
    alternative: Pair<String, () -> Unit>?,
) {
    val args = remember(approval.detail) {
        runCatching { Json.parseToJsonElement(approval.detail) as? JsonObject }.getOrNull()
    }
    fun arg(key: String) = (args?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val command = arg("command")
    val target = arg("path") ?: arg("url") ?: arg("from")?.let { from -> arg("to")?.let { "$from → $it" } ?: from }

    // Handing a task to a subagent: the task, in words, is what is approved.
    val subagent = approval.toolName == "subagent_dispatch"
    Sheet(onDismiss = { onResolve(false, false) }) {
        SheetHead(
            title = if (subagent) stringResource(R.string.approval_title_subagent) else approvalTitle(approval.toolName),
            subtitle = why,
            icon = when (approval.toolName) {
                "sh_exec", "sh_background", "build_run", "test_run" -> Codicons.Terminal
                "fs_delete", "pkg_remove" -> Codicons.Trash
                "web_fetch" -> Codicons.Globe
                "pkg_install" -> Codicons.Package
                "subagent_dispatch" -> Codicons.Hubot
                else -> Codicons.Edit
            },
            tone = if (approval.destructive) TileTone.Error else TileTone.Warning,
        )
        SheetBody {
            // A subagent's call is put to the user here, in the chat that
            // dispatched it; this says so.
            approval.requester?.let { name ->
                Row(
                    Modifier.padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Codicons.Hubot, contentDescription = null, tint = Design.Colors.Accent, modifier = Modifier.size(Design.Size.IconSmall))
                    Text(stringResource(R.string.approval_requested_by, name), style = Design.Type.LabelSmall, color = Design.Colors.TextSecondary)
                }
            }
            if (subagent) {
                val task = remember(approval.detail) { SubagentArgs.parse(approval.detail) }
                val readOnly = task.agent == BuiltInAgents.EXPLORER
                val kind = when {
                    readOnly -> stringResource(R.string.subagent_kind_read)
                    task.agent == null || task.agent.startsWith("builtin-") -> stringResource(R.string.subagent_kind_write)
                    else -> task.agent
                }
                Text(
                    stringResource(R.string.approval_subagent_task, kind).uppercase(),
                    style = Design.Type.Overline,
                    color = Design.Colors.TextTertiary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(Design.Radius.Box))
                        .background(Design.Colors.Surface2)
                        .border(1.dp, Design.Colors.Border, RoundedCornerShape(Design.Radius.Box))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(task.prompt.orEmpty(), style = Design.Type.BodySmall, color = Design.Colors.TextBody)
                }
                Note(
                    stringResource(if (readOnly) R.string.approval_subagent_note_read else R.string.approval_subagent_note_write),
                )
            } else {
                // The whole command or target, scrolled inside its box: what
                // is approved is what runs, however long.
                val shown = when {
                    command != null -> "~/projects/$projectName $ $command"
                    target != null -> target
                    else -> approval.summary
                }
                Box(
                    Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .clip(RoundedCornerShape(Design.Radius.Box))
                        .background(Design.Colors.TerminalBg)
                        .border(1.dp, Design.Colors.Border, RoundedCornerShape(Design.Radius.Box))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(shown, style = Design.Type.CodeSmall, color = Design.Colors.Text)
                }
                Note(
                    stringResource(
                        when {
                            approval.destructive -> R.string.approval_destructive_note
                            command != null -> R.string.approval_command_note
                            else -> R.string.approval_files_note
                        },
                    ),
                    color = if (approval.destructive) Design.Colors.Error else Design.Colors.TextTertiary,
                )
            }
        }
        SheetActions {
            Btn(stringResource(R.string.approval_deny), onClick = { onResolve(false, false) }, modifier = Modifier.weight(1f))
            Btn(
                stringResource(R.string.approval_allow),
                onClick = { onResolve(true, false) },
                kind = if (approval.destructive) BtnKind.Danger else BtnKind.Primary,
                modifier = Modifier.weight(1f),
            )
        }
        if (alternative != null) {
            val (label, onClick) = alternative
            Text(
                label,
                style = Design.Type.LabelSmall,
                color = Design.Colors.Accent,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 12.dp)
                    .pressable(onClick = onClick, shape = RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun approvalTitle(tool: String): String = when (tool) {
    "sh_exec", "sh_background" -> stringResource(R.string.approval_title_command)
    "fs_write" -> stringResource(R.string.approval_title_write)
    "fs_edit", "fs_multi_edit", "fs_apply_patch" -> stringResource(R.string.approval_title_edit)
    "fs_delete" -> stringResource(R.string.approval_title_delete)
    "fs_move" -> stringResource(R.string.approval_title_move)
    "pkg_install" -> stringResource(R.string.approval_title_install)
    "pkg_remove" -> stringResource(R.string.approval_title_uninstall)
    "web_fetch" -> stringResource(R.string.approval_title_fetch)
    "build_run" -> stringResource(R.string.approval_title_build)
    "test_run" -> stringResource(R.string.approval_title_test)
    else -> stringResource(R.string.approval_title_other, toolTitle(tool))
}

/**
 * The files the agent changed since the user last accepted, each with its
 * diff a tap away and a way back to how it was before the agent's first
 * edit.
 */
@Composable
fun ChangesSheet(
    changes: List<ChangeLedger.FileChange>,
    workspace: File?,
    busy: Boolean,
    canRevert: Boolean,
    loadDiff: suspend (ChangeLedger.FileChange) -> ChangeLedger.FileDiff,
    onOpen: (String) -> Unit,
    onRevert: (ChangeLedger.FileChange) -> Unit,
    onRevertAll: () -> Unit,
    onKeepAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val diffs = remember { mutableStateMapOf<String, ChangeLedger.FileDiff>() }
    var open by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<ChangeLedger.FileChange?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    LaunchedEffect(changes) {
        changes.forEach { change -> if (change.path !in diffs) runCatching { loadDiff(change) }.onSuccess { diffs[change.path] = it } }
    }

    Sheet(onDismiss = onDismiss) {
        SheetHead(
            title = stringResource(R.string.changes_title),
            subtitle = stringResource(R.string.changes_sheet_body),
            icon = Codicons.Diff,
        )
        SheetBody {
            if (changes.isEmpty()) {
                EmptyText(stringResource(R.string.changes_none))
            } else {
                Card(Modifier.padding(top = 16.dp)) {
                    changes.forEachIndexed { index, change ->
                        if (index > 0) CardDivider(inset = 48.dp)
                        val display = workspace?.let { File(change.path).relativeToOrNull(it)?.path } ?: File(change.path).name
                        val diff = diffs[change.path]
                        ListRow(
                            title = display,
                            subtitle = changeLine(change, diff),
                            lead = { FileTypeIcon(File(change.path).name, size = Design.Size.Icon) },
                            onClick = { open = if (open == change.path) null else change.path },
                            trailing = {
                                IconBtn(
                                    Codicons.Discard,
                                    contentDescription = stringResource(R.string.changes_revert_file, display),
                                    onClick = { confirm = change },
                                    size = IconBtnSize.Small,
                                    enabled = canRevert && !busy,
                                )
                            },
                        )
                        Collapse(open == change.path) {
                            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                                when {
                                    diff == null -> Spinner(modifier = Modifier.padding(vertical = 8.dp))
                                    diff.skipped -> Text(
                                        stringResource(R.string.changes_diff_skipped),
                                        style = Design.Type.BodySmall,
                                        color = Design.Colors.TextTertiary,
                                    )
                                    diff.unified.isBlank() -> Text(
                                        stringResource(R.string.changes_diff_empty),
                                        style = Design.Type.BodySmall,
                                        color = Design.Colors.TextTertiary,
                                    )
                                    else -> DiffView(
                                        ToolDisplay.Diff(
                                            path = display,
                                            unified = diff.unified,
                                            addedLines = diff.added,
                                            removedLines = diff.removed,
                                        ),
                                    )
                                }
                                if (File(change.path).isFile) {
                                    Btn(
                                        stringResource(R.string.changes_open_file),
                                        onClick = { onOpen(change.path) },
                                        kind = BtnKind.Ghost,
                                        icon = Codicons.GoToFile,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        SheetActions {
            Btn(
                stringResource(R.string.changes_revert_all),
                onClick = { confirmAll = true },
                kind = BtnKind.Danger,
                enabled = changes.isNotEmpty() && canRevert && !busy,
                busy = busy,
                modifier = Modifier.weight(1f),
            )
            Btn(
                stringResource(R.string.changes_keep_all_short),
                onClick = onKeepAll,
                kind = BtnKind.Primary,
                enabled = changes.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
        }
    }

    confirm?.let { change ->
        ConfirmSheet(
            title = stringResource(R.string.changes_revert_title_one, File(change.path).name),
            body = stringResource(R.string.changes_revert_body),
            confirm = stringResource(R.string.changes_revert),
            dismiss = stringResource(R.string.common_cancel),
            onConfirm = {
                confirm = null
                onRevert(change)
            },
            onDismiss = { confirm = null },
            icon = Codicons.Discard,
        )
    }
    if (confirmAll) {
        ConfirmSheet(
            title = androidx.compose.ui.res.pluralStringResource(R.plurals.changes_revert_title_all, changes.size, changes.size),
            body = stringResource(R.string.changes_revert_body),
            confirm = stringResource(R.string.changes_revert_all),
            dismiss = stringResource(R.string.common_cancel),
            onConfirm = {
                confirmAll = false
                onRevertAll()
            },
            onDismiss = { confirmAll = false },
            icon = Codicons.Discard,
        )
    }
}

/** "created · +15", "modified · +19 −1": what happened and how much. */
@Composable
private fun changeLine(change: ChangeLedger.FileChange, diff: ChangeLedger.FileDiff?): String {
    val kind = stringResource(
        when (change.kind) {
            ChangeLedger.FileChange.Kind.ADDED -> R.string.changes_kind_added
            ChangeLedger.FileChange.Kind.MODIFIED -> R.string.changes_kind_modified
            ChangeLedger.FileChange.Kind.DELETED -> R.string.changes_kind_deleted
        },
    )
    if (diff == null || diff.skipped) return kind
    val stats = listOfNotNull(
        "+${diff.added}".takeIf { diff.added > 0 },
        "\u2212${diff.removed}".takeIf { diff.removed > 0 },
    ).joinToString(" ")
    return if (stats.isEmpty()) kind else "$kind · $stats"
}

/**
 * "Restore" on a checkpoint: what goes back — the messages from this one on,
 * the files the agent changed since — and what does not: commands run in
 * the terminal.
 */
@Composable
fun CheckpointSheet(
    prompt: ChatViewModel.CheckpointPrompt,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet(onDismiss = onDismiss) {
        SheetHead(
            title = stringResource(R.string.checkpoint_title),
            subtitle = stringResource(R.string.checkpoint_body),
            icon = Codicons.Location,
            tone = TileTone.Accent,
        )
        SheetBody {
            Card(Modifier.padding(top = 16.dp)) {
                FactRow(stringResource(R.string.checkpoint_messages)) {
                    Text("${prompt.messages}", style = Design.Type.MonoCaption, color = Design.Colors.Text)
                }
                CardDivider(inset = 14.dp)
                FactRow(stringResource(R.string.checkpoint_files)) {
                    val files = prompt.files
                    if (files == null) Spinner() else Text("$files", style = Design.Type.MonoCaption, color = Design.Colors.Text)
                }
                // A summary written since goes too: the turns it folded are in the context again.
                if (prompt.undoesCompaction) {
                    CardDivider(inset = 14.dp)
                    FactRow(stringResource(R.string.checkpoint_compaction)) {
                        Text(stringResource(R.string.checkpoint_compaction_undone), style = Design.Type.MonoCaption, color = Design.Colors.Text)
                    }
                }
            }
            Note(stringResource(R.string.checkpoint_shell_note))
            if (busy) Note(stringResource(R.string.checkpoint_busy_note), icon = Codicons.Warning, color = Design.Colors.Warning)
        }
        SheetActions {
            Btn(stringResource(R.string.common_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
            Btn(
                stringResource(R.string.checkpoint_restore),
                onClick = onConfirm,
                kind = BtnKind.Primary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FactRow(label: String, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Design.Size.RowCompact).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Design.Type.Label, color = Design.Colors.Text, modifier = Modifier.weight(1f))
        value()
    }
}
