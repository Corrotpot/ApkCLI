package dev.apkcli.ui.chat.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.apkcli.R
import dev.apkcli.core.agent.ProjectInstructions
import dev.apkcli.ui.chat.ChatViewModel
import dev.apkcli.ui.components.Btn
import dev.apkcli.ui.components.BtnKind
import dev.apkcli.ui.components.Card
import dev.apkcli.ui.components.CardDivider
import dev.apkcli.ui.components.EmptyText
import dev.apkcli.ui.components.Field
import dev.apkcli.ui.components.IconBtn
import dev.apkcli.ui.components.IconBtnSize
import dev.apkcli.ui.components.ListRow
import dev.apkcli.ui.components.Note
import dev.apkcli.ui.components.Section
import dev.apkcli.ui.components.Segmented
import dev.apkcli.ui.components.Sheet
import dev.apkcli.ui.components.SheetActions
import dev.apkcli.ui.components.SheetBody
import dev.apkcli.ui.components.SheetHead
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.text.bytesText
import dev.apkcli.ui.theme.Design

/*
 * What the agent remembers, and one note of it: design/prototype/app.js
 * memorySheet() and noteSheet(). Everything here is in the agent's prompt
 * on every request, as "- key: value".
 */

/** A note being written: which scope, and the key it had (null for a new one). */
private data class NoteDraft(
    val global: Boolean,
    val previousKey: String?,
    val previousGlobal: Boolean,
    val key: String,
    val value: String,
)

/** Both sheets, driven by [ChatViewModel.memory]: non-null while memory is open. */
@Composable
fun MemorySheets(viewModel: ChatViewModel) {
    val memory by viewModel.memory.collectAsStateWithLifecycle()
    val view = memory ?: return
    var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf<NoteDraft?>(null) }

    val editing = draft
    if (editing == null) {
        MemorySheet(
            memory = view,
            onEdit = { global, key, value -> draft = NoteDraft(global, key, global, key, value) },
            onDelete = viewModel::deleteMemory,
            onAdd = { draft = NoteDraft(global = false, previousKey = null, previousGlobal = false, key = "", value = "") },
            onOpenNotes = viewModel::openProjectNotes,
            onDismiss = viewModel::closeMemory,
        )
    } else {
        NoteSheet(
            draft = editing,
            projectName = view.projectName,
            taken = { global ->
                (if (global) view.global else view.project).map { it.first }.toSet() -
                    setOfNotNull(editing.previousKey?.takeIf { global == editing.previousGlobal })
            },
            onChange = { draft = it },
            onSave = { done ->
                val previous = done.previousKey
                if (previous != null && done.global != done.previousGlobal) {
                    // Moving between scopes is a delete there and a write here.
                    viewModel.deleteMemory(done.previousGlobal, previous)
                    viewModel.saveMemory(done.global, done.key, done.value, null)
                } else {
                    viewModel.saveMemory(done.global, done.key, done.value, previous)
                }
                draft = null
            },
            onDelete = { done ->
                done.previousKey?.let { viewModel.deleteMemory(done.previousGlobal, it) }
                draft = null
            },
            onBack = { draft = null },
        )
    }
}

private val draftSaver = androidx.compose.runtime.saveable.listSaver<NoteDraft?, Any?>(
    save = { d -> if (d == null) emptyList() else listOf(d.global, d.previousKey, d.previousGlobal, d.key, d.value) },
    restore = { list ->
        if (list.isEmpty()) {
            null
        } else {
            NoteDraft(list[0] as Boolean, list[1] as String?, list[2] as Boolean, list[3] as String, list[4] as String)
        }
    },
)

@Composable
private fun MemorySheet(
    memory: ChatViewModel.MemoryView,
    onEdit: (global: Boolean, key: String, value: String) -> Unit,
    onDelete: (global: Boolean, key: String) -> Unit,
    onAdd: () -> Unit,
    onOpenNotes: () -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet(onDismiss = onDismiss) {
        SheetHead(
            title = stringResource(R.string.memory_title),
            subtitle = stringResource(R.string.memory_sheet_body),
            icon = Codicons.Book,
        )
        SheetBody {
            NoteGroup(stringResource(R.string.memory_scope_project), memory.project, global = false, onEdit, onDelete)
            NoteGroup(stringResource(R.string.memory_scope_global), memory.global, global = true, onEdit, onDelete)
            if (memory.project.isEmpty() && memory.global.isEmpty()) EmptyText(stringResource(R.string.memory_empty))
            Note(stringResource(R.string.memory_notes_hint), icon = Codicons.Markdown)
            // Longer than a request carries: the agent gets its beginning and
            // is told where the rest is (ProjectInstructions).
            memory.longInstructions?.let { (name, bytes) ->
                Note(
                    stringResource(
                        R.string.memory_notes_cut,
                        name,
                        bytesText(bytes),
                        bytesText(ProjectInstructions.MAX_BYTES.toLong()),
                    ),
                    icon = Codicons.Warning,
                    top = 8.dp,
                    color = Design.Colors.Warning,
                )
            }
            Btn(
                text = stringResource(if (memory.notesFile != null) R.string.memory_notes_open else R.string.memory_notes_create),
                onClick = onOpenNotes,
                kind = BtnKind.Ghost,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        SheetActions {
            Btn(
                stringResource(R.string.memory_add),
                onClick = onAdd,
                icon = Codicons.Add,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NoteGroup(
    title: String,
    entries: List<Pair<String, String>>,
    global: Boolean,
    onEdit: (Boolean, String, String) -> Unit,
    onDelete: (Boolean, String) -> Unit,
) {
    if (entries.isEmpty()) return
    Section(title)
    Card {
        entries.forEachIndexed { index, (key, value) ->
            if (index > 0) CardDivider(inset = 14.dp)
            ListRow(
                title = key,
                subtitle = value,
                subtitleMaxLines = 2,
                onClick = { onEdit(global, key, value) },
                trailing = {
                    IconBtn(
                        Codicons.Trash,
                        contentDescription = stringResource(R.string.memory_delete, key),
                        onClick = { onDelete(global, key) },
                        size = IconBtnSize.Small,
                    )
                },
            )
        }
    }
}

/**
 * One note. The key is a field of its own, and required: the prompt shows
 * every note as "- key: value", so the model reads the key as the note's
 * topic, and it is the address memory_write replaces and memory_forget
 * drops.
 */
@Composable
private fun NoteSheet(
    draft: NoteDraft,
    projectName: String,
    taken: (Boolean) -> Set<String>,
    onChange: (NoteDraft) -> Unit,
    onSave: (NoteDraft) -> Unit,
    onDelete: (NoteDraft) -> Unit,
    onBack: () -> Unit,
) {
    val key = draft.key.trim()
    val value = draft.value.trim()
    val keyTaken = key.isNotEmpty() && key in taken(draft.global)
    val tooLong = value.length > MAX_VALUE_CHARS
    val valid = key.isNotEmpty() && value.isNotEmpty() && !keyTaken && !tooLong

    Sheet(onDismiss = onBack) {
        SheetHead(
            title = stringResource(if (draft.previousKey == null) R.string.note_new else R.string.note_edit),
            subtitle = stringResource(R.string.note_body),
            icon = Codicons.Book,
        )
        SheetBody {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.note_scope), style = Design.Type.LabelSmall, color = Design.Colors.TextSecondary)
                Segmented(
                    options = listOf(false, true),
                    selected = draft.global,
                    onSelect = { onChange(draft.copy(global = it)) },
                    label = { global -> stringResource(if (global) R.string.memory_scope_global else R.string.memory_scope_project) },
                )
                Text(
                    if (draft.global) stringResource(R.string.note_scope_global_hint) else stringResource(R.string.note_scope_project_hint, projectName),
                    style = Design.Type.Caption,
                    color = Design.Colors.TextTertiary,
                )
            }
            Field(
                value = draft.key,
                onValueChange = { onChange(draft.copy(key = it)) },
                label = stringResource(R.string.memory_key),
                placeholder = stringResource(R.string.note_key_placeholder),
                error = keyTaken,
                helper = if (keyTaken) stringResource(R.string.memory_key_taken) else stringResource(R.string.note_key_hint),
                autoCorrect = false,
            )
            Field(
                value = draft.value,
                onValueChange = { onChange(draft.copy(value = it)) },
                label = stringResource(R.string.memory_value),
                placeholder = stringResource(R.string.note_value_placeholder),
                singleLine = false,
                minLines = 3,
                error = tooLong,
                helper = if (tooLong) {
                    stringResource(R.string.memory_value_long, MAX_VALUE_CHARS)
                } else {
                    "${value.length} / $MAX_VALUE_CHARS"
                },
            )
        }
        SheetActions {
            if (draft.previousKey != null) {
                Btn(stringResource(R.string.common_delete), onClick = { onDelete(draft) }, kind = BtnKind.Danger, modifier = Modifier.weight(1f))
            } else {
                Btn(stringResource(R.string.common_cancel), onClick = onBack, modifier = Modifier.weight(1f))
            }
            Btn(
                stringResource(R.string.common_save),
                onClick = { onSave(draft.copy(key = key, value = value)) },
                kind = BtnKind.Primary,
                enabled = valid,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The same cap memory_write applies, so the agent can always rewrite what the user wrote. */
private const val MAX_VALUE_CHARS = 500
