package dev.apkcli.ui.chat.sheets

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.apkcli.R
import dev.apkcli.ui.chat.ChatViewModel
import dev.apkcli.ui.components.Btn
import dev.apkcli.ui.components.BtnKind
import dev.apkcli.ui.components.Card
import dev.apkcli.ui.components.CardDivider
import dev.apkcli.ui.components.ConfirmSheet
import dev.apkcli.ui.components.Dot
import dev.apkcli.ui.components.EmptyText
import dev.apkcli.ui.components.Field
import dev.apkcli.ui.components.IconBtn
import dev.apkcli.ui.components.LocalToaster
import dev.apkcli.ui.components.Menu
import dev.apkcli.ui.components.MenuItem
import dev.apkcli.ui.components.MenuSeparator
import dev.apkcli.ui.components.Note
import dev.apkcli.ui.components.SearchField
import dev.apkcli.ui.components.Section
import dev.apkcli.ui.components.Sheet
import dev.apkcli.ui.components.SheetActions
import dev.apkcli.ui.components.SheetBody
import dev.apkcli.ui.components.SheetHead
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.theme.Design
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date

/*
 * The project's chats: design/prototype/app.js historySheet(),
 * chatRenameSheet() and chatDeleteSheet().
 *
 * Newest first, under Today, Yesterday and Earlier. A row is the chat's
 * title (its first request, or what the user renamed it to) over when it
 * was last used and its last request, when that is another one. The open
 * chat is a step lighter with its icon in accent. A tap opens a chat; its ⋮
 * renames or deletes it. While a turn runs, its chat cannot be left: the
 * other rows wait, and the open one pulses where its menu would be.
 */

/** The history sheet and the two it leads to, driven by [ChatViewModel.history]: non-null while it is open. */
@Composable
fun HistorySheets(
    viewModel: ChatViewModel,
    projectName: String,
    openId: String?,
    busy: Boolean,
    /** Nothing has been sent in the open chat: "New chat" would make another empty one. */
    openEmpty: Boolean,
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val chats = history ?: return
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    val toaster = LocalToaster.current
    val deleted = stringResource(R.string.history_deleted)
    val untitled = stringResource(R.string.history_untitled)

    val renameTarget = renaming?.let { id -> chats.firstOrNull { it.id == id } }
    val deleteTarget = deleting?.let { id -> chats.firstOrNull { it.id == id } }
    when {
        renameTarget != null -> RenameChatSheet(
            current = renameTarget.title,
            onSave = { name ->
                viewModel.renameChat(renameTarget.id, name)
                renaming = null
            },
            onBack = { renaming = null },
        )

        deleteTarget != null -> {
            val open = deleteTarget.id == openId
            val body = listOfNotNull(
                stringResource(R.string.history_delete_body, deleteTarget.title.ifBlank { untitled }),
                when {
                    !open -> null
                    chats.any { it.id != openId } -> stringResource(R.string.history_delete_opens_previous)
                    else -> stringResource(R.string.history_delete_opens_new)
                },
            ).joinToString(" ")
            ConfirmSheet(
                title = stringResource(R.string.history_delete_title),
                body = body,
                confirm = stringResource(R.string.common_delete),
                dismiss = stringResource(R.string.common_cancel),
                onConfirm = {
                    viewModel.deleteChat(deleteTarget.id)
                    deleting = null
                    toaster.show(deleted, Codicons.Trash)
                },
                onDismiss = { deleting = null },
                icon = Codicons.Trash,
            )
        }

        else -> HistorySheet(
            chats = chats,
            projectName = projectName,
            openId = openId,
            busy = busy,
            openEmpty = openEmpty,
            search = search,
            onSearch = { search = it },
            onOpen = viewModel::openChat,
            onRename = { renaming = it },
            onDelete = { deleting = it },
            onNewChat = viewModel::newSession,
            onDismiss = {
                search = ""
                viewModel.closeHistory()
            },
        )
    }
}

@Composable
private fun HistorySheet(
    chats: List<ChatViewModel.ChatSummary>,
    projectName: String,
    openId: String?,
    busy: Boolean,
    openEmpty: Boolean,
    search: String,
    onSearch: (String) -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val untitled = stringResource(R.string.history_untitled)
    val query = search.trim()
    val found = if (query.isEmpty()) {
        chats
    } else {
        chats.filter { chat ->
            chat.title.ifBlank { untitled }.contains(query, ignoreCase = true) || chat.lastRequest.contains(query, ignoreCase = true)
        }
    }
    val now = remember(chats) { System.currentTimeMillis() }
    val groups = found.groupBy { daysBack(it.updatedAt, now).coerceIn(0, 2) }

    Sheet(onDismiss = onDismiss) {
        SheetHead(
            title = stringResource(R.string.history_title),
            subtitle = "$projectName · " + pluralStringResource(R.plurals.history_count, chats.size, chats.size),
            icon = Codicons.History,
        )
        // A list a glance takes in needs no search.
        if (chats.size > SEARCH_FROM) {
            SearchField(
                value = search,
                onValueChange = onSearch,
                placeholder = stringResource(R.string.history_search),
                clearDescription = stringResource(R.string.a11y_clear),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        SheetBody {
            if (busy) Note(stringResource(R.string.history_busy))
            var first = true
            GROUPS.forEach { (days, label) ->
                val items = groups[days].orEmpty()
                if (items.isEmpty()) return@forEach
                Section(stringResource(label), top = if (first) 16.dp else 20.dp)
                first = false
                Card {
                    items.forEachIndexed { index, chat ->
                        if (index > 0) CardDivider(inset = 48.dp)
                        val open = chat.id == openId
                        ChatRow(
                            chat = chat,
                            title = chat.title.ifBlank { untitled },
                            open = open,
                            working = open && busy,
                            locked = busy && !open,
                            whenText = when {
                                open && busy -> stringResource(R.string.history_working)
                                chat.empty -> stringResource(R.string.history_empty_chat)
                                else -> chatWhen(context, chat.updatedAt, now)
                            },
                            onOpen = { onOpen(chat.id) },
                            onRename = { onRename(chat.id) },
                            onDelete = { onDelete(chat.id) },
                        )
                    }
                }
            }
            if (query.isNotEmpty() && found.isEmpty()) EmptyText(stringResource(R.string.projects_nothing_found, query))
            // The first chat of a project: where the next ones will be.
            if (chats.size == 1 && openEmpty) Note(stringResource(R.string.history_fresh_note), icon = Codicons.History)
        }
        SheetActions {
            Btn(
                stringResource(R.string.history_new_chat),
                onClick = onNewChat,
                icon = Codicons.Add,
                kind = BtnKind.Secondary,
                enabled = !busy && !openEmpty,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * `.chats .row`: the chat's icon, title and "when · last request", and its
 * ⋮. [locked] rows wait for the turn — only their icon and words fade, the
 * ⋮ still renames or deletes them, which the turn does not touch.
 */
@Composable
private fun ChatRow(
    chat: ChatViewModel.ChatSummary,
    title: String,
    open: Boolean,
    working: Boolean,
    locked: Boolean,
    whenText: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val last = chat.lastRequest
    val subtitle = if (last.isNotBlank() && !isTitle(last, chat.title)) "$whenText · $last" else whenText
    val fade = if (locked) 0.45f else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Design.Size.Row)
            .then(if (open) Modifier.background(Design.Colors.Surface3) else Modifier)
            .semantics { selected = open }
            .clickable(enabled = !locked, role = Role.Button, onClick = onOpen)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            Codicons.CommentDiscussion,
            contentDescription = null,
            tint = if (open) Design.Colors.Accent else Design.Colors.TextTertiary,
            modifier = Modifier.size(Design.Size.Icon).alpha(fade),
        )
        Column(Modifier.weight(1f).alpha(fade), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Design.Type.Label, color = Design.Colors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Design.Type.BodySmall, color = Design.Colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            // The working chat says so instead of offering a menu.
            working -> {
                val spoken = stringResource(R.string.history_working)
                Dot(pulse = true, modifier = Modifier.padding(end = 14.dp).semantics { contentDescription = spoken })
            }
            // An empty chat has nothing to rename or delete.
            !chat.empty -> ChatMenu(title, onRename, onDelete)
        }
    }
}

@Composable
private fun ChatMenu(title: String, onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn(Codicons.KebabVertical, stringResource(R.string.history_actions, title), onClick = { open = true })
        Menu(expanded = open, onDismiss = { open = false }) {
            MenuItem(
                stringResource(R.string.explorer_rename),
                onClick = {
                    open = false
                    onRename()
                },
                icon = Codicons.Edit,
            )
            MenuSeparator()
            MenuItem(
                stringResource(R.string.common_delete),
                onClick = {
                    open = false
                    onDelete()
                },
                icon = Codicons.Trash,
                danger = true,
            )
        }
    }
}

/** A chat's name, by hand. Blank goes back to nothing: the field must hold something. */
@Composable
private fun RenameChatSheet(current: String, onSave: (String) -> Unit, onBack: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(current) }
    val name = value.trim()
    Sheet(onDismiss = onBack) {
        SheetHead(title = stringResource(R.string.history_rename_title), icon = Codicons.Edit)
        Field(
            value = value,
            onValueChange = { value = it.take(CHAT_TITLE_MAX) },
            label = stringResource(R.string.history_rename_label),
            helper = stringResource(R.string.history_rename_help),
            onImeAction = { if (name.isNotEmpty()) onSave(name) },
            top = 16.dp,
        )
        SheetActions {
            Btn(stringResource(R.string.common_cancel), onClick = onBack, modifier = Modifier.weight(1f))
            Btn(
                stringResource(R.string.common_save),
                onClick = { onSave(name) },
                kind = BtnKind.Primary,
                enabled = name.isNotEmpty() && name != current,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Whether the last request is the one the chat is named after: AgentLoop
 * names a chat after its first request's first line, cut to 57 characters
 * and "…" when longer, and a chat of one request would say it twice.
 */
private fun isTitle(request: String, title: String): Boolean =
    request == title || (title.endsWith("…") && request.startsWith(title.dropLast(1)))

/** Above this many chats the sheet has a search field. */
private const val SEARCH_FROM = 5

/** The same cap ChatViewModel.renameChat applies. */
private const val CHAT_TITLE_MAX = 80

private val GROUPS = listOf(0 to R.string.history_today, 1 to R.string.history_yesterday, 2 to R.string.history_earlier)

/** Calendar days from [at] to [now] where the phone is: 0 today, 1 yesterday. */
private fun daysBack(at: Long, now: Long): Int {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(day, today).toInt()
}

/**
 * The time under Today and Yesterday, in the phone's 12- or 24-hour
 * clock; the date under Earlier, with the year when it is another one.
 */
private fun chatWhen(context: Context, at: Long, now: Long): String {
    if (daysBack(at, now) <= 1) return DateFormat.getTimeFormat(context).format(Date(at))
    val sameYear = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).year ==
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).year
    val year = if (sameYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    return DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or year)
}
