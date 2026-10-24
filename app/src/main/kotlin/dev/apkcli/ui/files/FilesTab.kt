package dev.apkcli.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.apkcli.R
import dev.apkcli.core.store.ChangeLedger
import dev.apkcli.files.ProjectDocuments
import dev.apkcli.ui.components.Btn
import dev.apkcli.ui.components.BtnKind
import dev.apkcli.ui.components.ConfirmSheet
import dev.apkcli.ui.components.Field
import dev.apkcli.ui.components.IconBtn
import dev.apkcli.ui.components.IconBtnSize
import dev.apkcli.ui.components.LocalToaster
import dev.apkcli.ui.components.Menu
import dev.apkcli.ui.components.MenuItem
import dev.apkcli.ui.components.MenuSeparator
import dev.apkcli.ui.components.Sheet
import dev.apkcli.ui.components.SheetActions
import dev.apkcli.ui.components.SheetHead
import dev.apkcli.ui.components.Spinner
import dev.apkcli.ui.editor.EditorView
import dev.apkcli.ui.editor.EditorViewModel
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.text.UiText
import dev.apkcli.ui.text.resolve
import dev.apkcli.ui.theme.Design
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** What a sheet on the tree is asking for. */
private sealed interface TreeSheet {
    data class NewItem(val parentDir: String, val directory: Boolean) : TreeSheet
    data class Rename(val path: String) : TreeSheet
    data class Delete(val path: String, val isDir: Boolean) : TreeSheet
}

/**
 * The Files tab: design/prototype/app.js filesTab() and editorView().
 *
 * One of the two at a time, as on the prototype's phone: the tree, and the
 * editor once a file is open — its tab row starts with the button back to
 * the tree. Both stay composed, so the tree keeps its scroll position and the
 * editor its native view while the other is on screen.
 *
 * [changes] are the agent's edits not yet kept, drawn as the tree's M and A.
 */
@Composable
fun FilesTab(
    explorer: ExplorerViewModel,
    editor: EditorViewModel,
    changes: List<ChangeLedger.FileChange>,
    modifier: Modifier = Modifier,
) {
    val tree by explorer.state.collectAsStateWithLifecycle()
    val editorState by editor.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    // A failed create, rename or delete is one line and needs no answer.
    val treeMessage = tree.message?.resolve()
    LaunchedEffect(treeMessage) {
        if (treeMessage != null) {
            toaster.show(treeMessage, Codicons.Warning)
            explorer.dismissMessage()
        }
    }

    // Shown once the file is actually open: a binary or oversized one is
    // refused with a toast, and the tree stays where it was.
    val openFile: (String) -> Unit = { path ->
        val outcome = CompletableDeferred<String?>()
        editor.open(path, outcome = outcome)
        scope.launch { if (outcome.await() == null) explorer.showTree(false) }
    }

    val showEditor = !tree.treeShown && editorState.active != null
    val decorations = remember(changes) { changes.associate { it.path to it.kind } }

    OneOf(
        showFirst = !showEditor,
        modifier = modifier.fillMaxSize().background(Design.Colors.Bg),
        first = {
            TreeView(
                explorer = explorer,
                state = tree,
                decorations = decorations,
                hasTabs = editorState.tabs.isNotEmpty(),
                onOpenFile = openFile,
                onShowEditor = { explorer.showTree(false) },
            )
        },
        second = {
            EditorView(
                viewModel = editor,
                state = editorState,
                onShowTree = { explorer.showTree(true) },
            )
        },
    )
}

/**
 * Both children composed, one laid out: the other is measured at zero and
 * draws nothing, but keeps its state.
 */
@Composable
private fun OneOf(
    showFirst: Boolean,
    modifier: Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    Layout(contents = listOf(first, second), modifier = modifier) { (firsts, seconds), constraints ->
        val full = Constraints.fixed(constraints.maxWidth, constraints.maxHeight)
        val zero = Constraints.fixed(0, 0)
        val a = firsts.map { it.measure(if (showFirst) full else zero) }
        val b = seconds.map { it.measure(if (showFirst) zero else full) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            a.forEach { it.place(0, 0) }
            b.forEach { it.place(0, 0) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeView(
    explorer: ExplorerViewModel,
    state: ExplorerViewModel.UiState,
    decorations: Map<String, ChangeLedger.FileChange.Kind>,
    hasTabs: Boolean,
    onOpenFile: (String) -> Unit,
    onShowEditor: () -> Unit,
) {
    var sheet by remember { mutableStateOf<TreeSheet?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    // The project's own menu, and where the empty space below the rows was
    // held for it, which is where it opens.
    var rootMenuOpen by remember { mutableStateOf(false) }
    var rootMenuAt by remember { mutableStateOf(Offset.Zero) }
    val listState = rememberLazyListState()
    val root = state.root
    // Read by the hold detector, which is not restarted when they change.
    val holdable by rememberUpdatedState(root != null && !state.loading)
    val noRows by rememberUpdatedState(state.rows.isEmpty())

    // Out to the system file manager: a whole project through the
    // DocumentsProvider, one file to whatever app handles its type.
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val noHandler = stringResource(R.string.explorer_no_file_manager)
    val openExternally: (File?, Boolean) -> Unit = { file, directory ->
        val candidates = when {
            file == null -> ProjectDocuments.browseIntents(context)
            directory -> ProjectDocuments.browseIntents(context, file)
            else -> listOfNotNull(ProjectDocuments.viewIntent(context, file))
        }
        // Each in turn: a file manager that will not answer for a folder
        // throws, and the project's root is the next thing to try.
        val started = candidates.any { intent -> runCatching { context.startActivity(intent) }.isSuccess }
        if (!started) toaster.show(noHandler, Codicons.Warning)
    }

    Column(Modifier.fillMaxSize()) {
        // `.tree-head`
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.explorer_title).uppercase(),
                style = Design.Type.Overline,
                color = Design.Colors.TextTertiary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (hasTabs) {
                IconBtn(Codicons.GoToFile, stringResource(R.string.explorer_show_editor), onClick = onShowEditor, size = IconBtnSize.Small)
            }
            IconBtn(
                Codicons.NewFile,
                stringResource(R.string.explorer_new_file),
                onClick = { root?.let { sheet = TreeSheet.NewItem(targetDir(state, it), directory = false) } },
                size = IconBtnSize.Small,
            )
            IconBtn(
                Codicons.NewFolder,
                stringResource(R.string.explorer_new_folder),
                onClick = { root?.let { sheet = TreeSheet.NewItem(targetDir(state, it), directory = true) } },
                size = IconBtnSize.Small,
            )
            IconBtn(Codicons.Refresh, stringResource(R.string.explorer_refresh), onClick = explorer::refresh, size = IconBtnSize.Small)
            IconBtn(Codicons.CollapseAll, stringResource(R.string.explorer_collapse_all), onClick = explorer::collapseAll, size = IconBtnSize.Small)
            IconBtn(
                Codicons.LinkExternal,
                stringResource(R.string.explorer_open_externally),
                // This project, not the list of them all.
                onClick = { openExternally(root, true) },
                size = IconBtnSize.Small,
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // Holding the empty space below the rows is holding the
                // project itself. A row takes its own hold first, so only a
                // press on no row gets here; the position check keeps it so.
                // The header's buttons do the same for TalkBack.
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { at ->
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                        val belowRows = noRows || last == null || at.y > last.offset + last.size
                        if (holdable && belowRows) {
                            rootMenuAt = at
                            rootMenuOpen = true
                        }
                    })
                },
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }

                state.rows.isEmpty() -> ExplorerEmpty()

                // Room below the last row, so there is always empty space to
                // hold, however long the tree.
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(state.rows, key = { it.path }) { row ->
                        Box {
                            TreeRow(
                                row = row,
                                selected = row.path == state.selected,
                                decoration = if (row.isDir) null else decorations[row.path],
                                onClick = {
                                    explorer.toggle(row)
                                    if (!row.isDir) onOpenFile(row.path)
                                },
                                onLongClick = {
                                    explorer.select(row.path)
                                    menuFor = row.path
                                },
                            )
                            Menu(expanded = menuFor == row.path, onDismiss = { menuFor = null }) {
                                // A folder's menu creates inside it. A file has
                                // nothing to create in: the project's root is the
                                // empty space below the rows.
                                if (row.isDir) {
                                    MenuItem(stringResource(R.string.explorer_new_file), icon = Codicons.NewFile, onClick = {
                                        menuFor = null
                                        sheet = TreeSheet.NewItem(row.path, directory = false)
                                    })
                                    MenuItem(stringResource(R.string.explorer_new_folder), icon = Codicons.NewFolder, onClick = {
                                        menuFor = null
                                        sheet = TreeSheet.NewItem(row.path, directory = true)
                                    })
                                }
                                MenuItem(stringResource(R.string.explorer_rename), icon = Codicons.Edit, onClick = {
                                    menuFor = null
                                    sheet = TreeSheet.Rename(row.path)
                                })
                                MenuSeparator()
                                MenuItem(
                                    stringResource(
                                        if (row.isDir) R.string.explorer_open_externally else R.string.explorer_open_file_externally,
                                    ),
                                    icon = Codicons.LinkExternal,
                                    onClick = {
                                        menuFor = null
                                        openExternally(File(row.path), row.isDir)
                                    },
                                )
                                MenuSeparator()
                                MenuItem(stringResource(R.string.explorer_delete), icon = Codicons.Trash, danger = true, onClick = {
                                    menuFor = null
                                    sheet = TreeSheet.Delete(row.path, row.isDir)
                                })
                            }
                        }
                    }
                }
            }

            // The project's menu, from where the finger was: what a folder's
            // has, without renaming or deleting the project from inside it.
            if (root != null) {
                Box(Modifier.offset { IntOffset(rootMenuAt.x.roundToInt(), rootMenuAt.y.roundToInt()) }) {
                    Menu(expanded = rootMenuOpen, onDismiss = { rootMenuOpen = false }) {
                        MenuItem(stringResource(R.string.explorer_new_file), icon = Codicons.NewFile, onClick = {
                            rootMenuOpen = false
                            sheet = TreeSheet.NewItem(root.absolutePath, directory = false)
                        })
                        MenuItem(stringResource(R.string.explorer_new_folder), icon = Codicons.NewFolder, onClick = {
                            rootMenuOpen = false
                            sheet = TreeSheet.NewItem(root.absolutePath, directory = true)
                        })
                        MenuSeparator()
                        MenuItem(stringResource(R.string.explorer_open_externally), icon = Codicons.LinkExternal, onClick = {
                            rootMenuOpen = false
                            openExternally(root, true)
                        })
                    }
                }
            }
        }
    }

    when (val current = sheet) {
        null -> Unit

        is TreeSheet.NewItem -> NameSheet(
            title = stringResource(if (current.directory) R.string.explorer_new_folder else R.string.explorer_new_file),
            subtitle = root?.let { relativeDir(current.parentDir, it) },
            icon = if (current.directory) Codicons.NewFolder else Codicons.NewFile,
            initial = "",
            placeholder = stringResource(if (current.directory) R.string.explorer_folder_hint else R.string.explorer_file_hint),
            action = stringResource(R.string.create_action),
            validate = explorer::validateName,
            onDismiss = { sheet = null },
            onConfirm = { name ->
                sheet = null
                explorer.create(current.parentDir, name, current.directory) { file ->
                    if (!current.directory) onOpenFile(file.absolutePath)
                }
            },
        )

        is TreeSheet.Rename -> NameSheet(
            title = stringResource(R.string.explorer_rename),
            subtitle = File(current.path).name,
            icon = Codicons.Edit,
            initial = File(current.path).name,
            placeholder = "",
            action = stringResource(R.string.common_save),
            validate = { name ->
                if (name.contains('/')) UiText.res(R.string.explorer_name_invalid) else explorer.validateName(name)
            },
            onDismiss = { sheet = null },
            onConfirm = { name ->
                sheet = null
                explorer.rename(current.path, name)
            },
        )

        is TreeSheet.Delete -> ConfirmSheet(
            title = stringResource(R.string.explorer_delete_title, File(current.path).name),
            body = stringResource(if (current.isDir) R.string.explorer_delete_dir_body else R.string.explorer_delete_file_body),
            confirm = stringResource(R.string.explorer_delete),
            dismiss = stringResource(R.string.common_cancel),
            onConfirm = {
                sheet = null
                explorer.delete(current.path)
            },
            onDismiss = { sheet = null },
            icon = Codicons.Trash,
        )
    }
}

/**
 * `.tr`: 40 dp. A folder: its chevron, then the folder glyph; a file: its
 * Seti icon lined up under the folder glyph of its level, and the agent's
 * M or A at the end.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    row: ExplorerViewModel.Row,
    selected: Boolean,
    decoration: ChangeLedger.FileChange.Kind?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val expandedLabel = stringResource(R.string.explorer_expanded)
    val collapsedLabel = stringResource(R.string.explorer_collapsed)
    val decorationLabel = when (decoration) {
        ChangeLedger.FileChange.Kind.ADDED -> stringResource(R.string.explorer_deco_added)
        ChangeLedger.FileChange.Kind.MODIFIED -> stringResource(R.string.explorer_deco_modified)
        else -> null
    }
    val start = 16 + row.depth * 16 + if (row.isDir) 0 else 22

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Design.Size.TreeRow)
            .background(if (selected) Design.Colors.Surface3 else Color.Transparent)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.explorer_actions),
            )
            .padding(start = start.dp, end = 16.dp)
            .semantics(mergeDescendants = true) {
                when {
                    row.isDir -> stateDescription = if (row.expanded) expandedLabel else collapsedLabel
                    decorationLabel != null -> stateDescription = decorationLabel
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.isDir) {
            Icon(
                if (row.expanded) Codicons.ChevronDown else Codicons.ChevronRight,
                contentDescription = null,
                tint = Design.Colors.TextTertiary,
                modifier = Modifier.size(Design.Size.IconSmall),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                if (row.expanded) Codicons.FolderOpened else Codicons.Folder,
                contentDescription = null,
                tint = Design.Colors.Text,
                modifier = Modifier.size(Design.Size.IconSmall),
            )
        } else {
            FileTypeIcon(row.name, size = Design.Size.IconSmall)
        }
        Text(
            text = row.name,
            style = Design.Type.Body,
            color = Design.Colors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        if (decoration == ChangeLedger.FileChange.Kind.ADDED || decoration == ChangeLedger.FileChange.Kind.MODIFIED) {
            Text(
                text = if (decoration == ChangeLedger.FileChange.Kind.ADDED) "A" else "M",
                style = Design.Type.MonoLabel,
                color = if (decoration == ChangeLedger.FileChange.Kind.ADDED) Design.Colors.Success else Design.Colors.Warning,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** `.explorer-empty` */
@Composable
private fun ExplorerEmpty() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Codicons.Folder, contentDescription = null, tint = Design.Colors.TextSecondary, modifier = Modifier.size(28.dp))
        Text(
            stringResource(R.string.explorer_empty_title),
            style = Design.Type.Label,
            color = Design.Colors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.explorer_empty_body),
            style = Design.Type.BodySmall,
            color = Design.Colors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** A name for a new file or folder, or a new name for one: validated as it is typed. */
@Composable
private fun NameSheet(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    initial: String,
    placeholder: String,
    action: String,
    validate: (String) -> UiText?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val problem = validate(value)
    val unchanged = initial.isNotEmpty() && value.trim() == initial
    val usable = problem == null && !unchanged
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Sheet(onDismiss = onDismiss) {
        SheetHead(title = title, subtitle = subtitle, icon = icon)
        Field(
            value = value,
            onValueChange = { value = it },
            label = stringResource(R.string.explorer_name_label),
            placeholder = placeholder,
            mono = true,
            error = problem != null && value.isNotEmpty(),
            helper = problem?.takeIf { value.isNotEmpty() }?.resolve(),
            onImeAction = { if (usable) onConfirm(value) },
            focusRequester = focus,
        )
        SheetActions {
            Btn(stringResource(R.string.common_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
            Btn(action, onClick = { onConfirm(value) }, kind = BtnKind.Primary, enabled = usable, modifier = Modifier.weight(1f))
        }
    }
}

/** New items go into the selected folder, or next to the selected file, or the root. */
private fun targetDir(state: ExplorerViewModel.UiState, root: File): String {
    val selected = state.rows.firstOrNull { it.path == state.selected } ?: return root.absolutePath
    return if (selected.isDir) selected.path else File(selected.path).parent ?: root.absolutePath
}

/** "app/src" for a folder inside the project, the project's name for its root. */
private fun relativeDir(dir: String, root: File): String {
    val relative = File(dir).relativeToOrNull(root)?.path.orEmpty()
    return if (relative.isEmpty()) root.name else "${root.name}/$relative"
}
