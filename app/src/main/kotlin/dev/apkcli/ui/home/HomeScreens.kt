package dev.apkcli.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.apkcli.R
import dev.apkcli.core.projects.ProjectImport
import dev.apkcli.ui.chat.ChatViewModel
import dev.apkcli.ui.components.Avatar
import dev.apkcli.ui.components.Btn
import dev.apkcli.ui.components.BtnKind
import dev.apkcli.ui.components.Card
import dev.apkcli.ui.components.CardDivider
import dev.apkcli.ui.components.ConfirmSheet
import dev.apkcli.ui.components.EmptyText
import dev.apkcli.ui.components.Field
import dev.apkcli.ui.components.IconBtn
import dev.apkcli.ui.components.IconBtnSize
import dev.apkcli.ui.components.ListRow
import dev.apkcli.ui.components.LocalToaster
import dev.apkcli.ui.components.Menu
import dev.apkcli.ui.components.MenuItem
import dev.apkcli.ui.components.MenuSeparator
import dev.apkcli.ui.components.Note
import dev.apkcli.ui.components.ProgressLine
import dev.apkcli.ui.components.SearchField
import dev.apkcli.ui.components.Section
import dev.apkcli.ui.components.Sheet
import dev.apkcli.ui.components.SheetActions
import dev.apkcli.ui.components.SheetHead
import dev.apkcli.ui.components.Tile
import dev.apkcli.ui.components.TileTone
import dev.apkcli.ui.components.TopBar
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.text.agoText
import dev.apkcli.ui.text.bytesText
import dev.apkcli.ui.text.resolve
import dev.apkcli.ui.theme.Design
import kotlinx.coroutines.launch
import java.io.File

/*
 * The main menu and the project list: design/prototype/app.js homeScreen()
 * and openScreen(), with the sheets they open — create, clone, rename —
 * and the import's progress.
 */

/** What the main menu and the project list do; the screens own no state of their own. */
class ProjectActions(
    val open: (File) -> Unit,
    val create: (String) -> Unit,
    val clone: (String) -> Unit,
    val importFolder: (android.net.Uri) -> Unit,
    val cancelImport: () -> Unit,
    val dismissImport: () -> Unit,
    val openSettings: () -> Unit,
)

private sealed interface HomeSheet {
    data object Create : HomeSheet
    data object Clone : HomeSheet
    data class Rename(val project: ProjectsViewModel.Project) : HomeSheet
    data class Delete(val project: ProjectsViewModel.Project) : HomeSheet
}

@Composable
fun HomeScreen(
    projects: ProjectsViewModel.UiState,
    needsKey: Boolean,
    importing: ChatViewModel.ProjectImportState?,
    actions: ProjectActions,
    onShowAll: () -> Unit,
) {
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    val count = projects.projects.size

    Column(Modifier.fillMaxSize().background(Design.Colors.Bg).statusBarsPadding()) {
        TopBar(
            actions = {
                IconBtn(Codicons.SettingsGear, stringResource(R.string.a11y_settings), onClick = actions.openSettings)
            },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        ) {
            Column(Modifier.padding(horizontal = 4.dp)) {
                LogoTile()
                Text(
                    stringResource(R.string.app_name),
                    style = Design.Type.Title,
                    color = Design.Colors.Text,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    stringResource(R.string.home_tagline),
                    style = Design.Type.Body,
                    color = Design.Colors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (needsKey) KeyNotice(onAdd = actions.openSettings)

            Section(stringResource(R.string.home_start))
            Card {
                ListRow(
                    title = stringResource(R.string.home_open),
                    subtitle = pluralStringResource(R.plurals.home_projects_on_device, count, count),
                    lead = { Tile(Codicons.FolderOpened, TileTone.Accent) },
                    onClick = onShowAll,
                )
                CardDivider()
                ListRow(
                    title = stringResource(R.string.home_create),
                    subtitle = stringResource(R.string.home_create_sub),
                    lead = { Tile(Codicons.NewFolder) },
                    onClick = { sheet = HomeSheet.Create },
                )
                CardDivider()
                ListRow(
                    title = stringResource(R.string.home_clone),
                    subtitle = stringResource(R.string.home_clone_sub),
                    lead = { Tile(Codicons.RepoClone) },
                    onClick = { sheet = HomeSheet.Clone },
                )
            }

            Section(
                stringResource(R.string.home_recent),
                linkText = stringResource(R.string.home_all).takeIf { count > RECENT },
                onLink = onShowAll,
            )
            Card {
                val recent = projects.projects.take(RECENT)
                if (projects.loaded && recent.isEmpty()) {
                    EmptyText(stringResource(R.string.home_no_projects))
                }
                recent.forEachIndexed { index, project ->
                    if (index > 0) CardDivider()
                    ProjectRow(project, onClick = { actions.open(project.dir) })
                }
            }
        }
    }

    HomeSheets(sheet, onSheet = { sheet = it }, importing = importing, actions = actions)
}

@Composable
fun ProjectsScreen(
    projects: ProjectsViewModel.UiState,
    viewModel: ProjectsViewModel,
    importing: ChatViewModel.ProjectImportState?,
    actions: ProjectActions,
    onBack: () -> Unit,
    /** The project the agent is working in right now: it cannot be renamed or deleted under it. */
    busyProject: File?,
    onProjectDeleted: (File) -> Unit,
    onProjectRenamed: (from: File, to: File) -> Unit,
) {
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val toaster = LocalToaster.current
    val savedText = stringResource(R.string.projects_exported)
    val failedText = stringResource(R.string.projects_export_failed)
    var exporting by remember { mutableStateOf<ProjectsViewModel.Project?>(null) }
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val project = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri != null) {
            viewModel.export(project, uri) { ok -> toaster.show(if (ok) savedText else failedText, if (ok) Codicons.Pass else Codicons.Error) }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) actions.importFolder(uri)
    }

    Column(Modifier.fillMaxSize().background(Design.Colors.Bg).statusBarsPadding()) {
        TopBar(
            title = stringResource(R.string.home_open),
            onBack = onBack,
            backDescription = stringResource(R.string.a11y_back),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        ) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.projects_search),
                clearDescription = stringResource(R.string.a11y_clear),
            )
            Section(stringResource(R.string.projects_on_device))
            val shown = projects.projects.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            Card {
                when {
                    shown.isEmpty() && query.isNotBlank() -> EmptyText(stringResource(R.string.projects_nothing_found, query.trim()))
                    shown.isEmpty() && projects.loaded -> EmptyText(stringResource(R.string.home_no_projects))
                }
                shown.forEachIndexed { index, project ->
                    if (index > 0) CardDivider()
                    ProjectRow(
                        project = project,
                        onClick = { actions.open(project.dir) },
                        menu = {
                            ProjectMenuButton(
                                onOpen = { actions.open(project.dir) },
                                onRename = { sheet = HomeSheet.Rename(project) },
                                onExport = {
                                    exporting = project
                                    exportTo.launch("${project.name}.zip")
                                },
                                onDelete = { sheet = HomeSheet.Delete(project) },
                                editable = project.dir.absolutePath != busyProject?.absolutePath,
                            )
                        },
                    )
                }
            }
            Section(stringResource(R.string.projects_add))
            Card {
                ListRow(
                    title = stringResource(R.string.projects_from_folder),
                    subtitle = stringResource(R.string.projects_from_folder_sub),
                    lead = { Tile(Codicons.RootFolder) },
                    onClick = { pickFolder.launch(null) },
                )
                CardDivider()
                ListRow(
                    title = stringResource(R.string.projects_from_github),
                    subtitle = stringResource(R.string.projects_from_github_sub),
                    lead = { Tile(Codicons.Github) },
                    onClick = { sheet = HomeSheet.Clone },
                )
            }
        }
    }

    HomeSheets(
        sheet = sheet,
        onSheet = { sheet = it },
        importing = importing,
        actions = actions,
        viewModel = viewModel,
        onProjectDeleted = onProjectDeleted,
        onProjectRenamed = onProjectRenamed,
    )
}

/** `.row` for a project: its letter, its name, when and how big. */
@Composable
private fun ProjectRow(
    project: ProjectsViewModel.Project,
    onClick: () -> Unit,
    menu: (@Composable () -> Unit)? = null,
) {
    val files = pluralStringResource(R.plurals.files_count, project.files, project.files)
    ListRow(
        title = project.name,
        subtitle = "${agoText(project.lastActive)} · $files",
        lead = { Avatar(project.name) },
        onClick = onClick,
        trailing = if (menu != null) {
            { menu() }
        } else {
            { dev.apkcli.ui.components.Chevron() }
        },
    )
}

@Composable
private fun ProjectMenuButton(
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    editable: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn(
            Codicons.KebabVertical,
            contentDescription = stringResource(R.string.a11y_actions),
            onClick = { open = true },
            size = IconBtnSize.Small,
        )
        Menu(expanded = open, onDismiss = { open = false }) {
            MenuItem(stringResource(R.string.projects_menu_open), onClick = { open = false; onOpen() }, icon = Codicons.GoToFile)
            MenuItem(
                stringResource(R.string.projects_menu_rename),
                onClick = { open = false; onRename() },
                icon = Codicons.Edit,
                enabled = editable,
            )
            MenuItem(stringResource(R.string.projects_menu_export), onClick = { open = false; onExport() }, icon = Codicons.Archive)
            MenuSeparator()
            MenuItem(
                stringResource(R.string.projects_menu_delete),
                onClick = { open = false; onDelete() },
                icon = Codicons.Trash,
                danger = true,
                enabled = editable,
            )
        }
    }
}

/** `.notice`: no model is reachable yet, and the one thing to do about it. */
@Composable
private fun KeyNotice(onAdd: () -> Unit) {
    val shape = RoundedCornerShape(Design.Radius.Card)
    Row(
        Modifier
            .padding(top = 24.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Design.Colors.Surface1)
            .border(1.dp, Design.Colors.Border, shape)
            .padding(start = 14.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Tile(Codicons.Key, TileTone.Warning)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.home_key_title), style = Design.Type.Label, color = Design.Colors.Text)
            Text(
                stringResource(R.string.home_key_body),
                style = Design.Type.BodySmall,
                color = Design.Colors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Btn(
                stringResource(R.string.home_key_add),
                onClick = onAdd,
                kind = BtnKind.Primary,
                small = true,
                icon = Codicons.Key,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * `.logo-tile`: the launcher glyph on surface 1, cropped to its own bounds
 * plus a margin — the 64-unit square at (24.5, 17) of its 108-unit canvas,
 * as the prototype's SVG view box has it.
 */
@Composable
private fun LogoTile() {
    val painter = painterResource(R.drawable.ic_launcher_foreground)
    val shape = RoundedCornerShape(Design.Radius.Card)
    Box(
        Modifier
            .size(Design.Size.LogoTile)
            .clip(shape)
            .background(Design.Colors.Surface1)
            .border(1.dp, Design.Colors.BorderStrong, shape)
            .drawBehind {
                val unit = size.width / 64f
                translate(left = -24.5f * unit, top = -17f * unit) {
                    with(painter) { draw(Size(108f * unit, 108f * unit)) }
                }
            },
    )
}

/* ================================================================ sheets */

@Composable
private fun HomeSheets(
    sheet: HomeSheet?,
    onSheet: (HomeSheet?) -> Unit,
    importing: ChatViewModel.ProjectImportState?,
    actions: ProjectActions,
    viewModel: ProjectsViewModel? = null,
    onProjectDeleted: (File) -> Unit = {},
    onProjectRenamed: (File, File) -> Unit = { _, _ -> },
) {
    // An import under way has the screen, whichever sheet started it.
    if (importing != null) {
        ImportSheet(
            importing = importing,
            onCancel = actions.cancelImport,
            onDismiss = {
                actions.dismissImport()
                onSheet(null)
            },
            onRetry = {
                actions.dismissImport()
                onSheet(HomeSheet.Clone)
            },
            onOpen = { dir ->
                actions.dismissImport()
                onSheet(null)
                actions.open(dir)
            },
        )
        return
    }
    when (sheet) {
        null -> Unit
        HomeSheet.Create -> CreateSheet(
            onCreate = { name ->
                onSheet(null)
                actions.create(name)
            },
            onDismiss = { onSheet(null) },
        )
        HomeSheet.Clone -> CloneSheet(
            onClone = { input -> actions.clone(input) },
            onDismiss = { onSheet(null) },
        )
        is HomeSheet.Rename -> if (viewModel != null) {
            RenameSheet(sheet.project, viewModel, onDone = { renamed ->
                onSheet(null)
                renamed?.let { onProjectRenamed(sheet.project.dir, it) }
            })
        }
        is HomeSheet.Delete -> ConfirmSheet(
            title = stringResource(R.string.projects_delete_title, sheet.project.name),
            body = stringResource(R.string.projects_delete_body),
            confirm = stringResource(R.string.common_delete),
            dismiss = stringResource(R.string.common_cancel),
            onConfirm = {
                onSheet(null)
                onProjectDeleted(sheet.project.dir)
                viewModel?.delete(sheet.project)
            },
            onDismiss = { onSheet(null) },
            icon = Codicons.Trash,
        )
    }
}

@Composable
private fun CreateSheet(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val clean = name.trim()
    val valid = ProjectsViewModel.validName(clean)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Sheet(onDismiss = onDismiss) {
        SheetHead(
            title = stringResource(R.string.create_title),
            subtitle = stringResource(R.string.create_body),
            icon = Codicons.NewFolder,
        )
        Field(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.create_name),
            placeholder = "my-project",
            leading = Codicons.Folder,
            error = clean.isNotEmpty() && !valid,
            helper = when {
                clean.isEmpty() -> stringResource(R.string.create_name_hint)
                !valid -> stringResource(R.string.create_name_invalid)
                else -> "~/projects/$clean"
            },
            autoCorrect = false,
            onImeAction = { if (valid) onCreate(clean) },
            focusRequester = focus,
        )
        SheetActions {
            Btn(stringResource(R.string.common_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
            Btn(
                stringResource(R.string.create_action),
                onClick = { onCreate(clean) },
                kind = BtnKind.Primary,
                enabled = valid,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CloneSheet(onClone: (String) -> Unit, onDismiss: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val parsed = remember(input) { ProjectImport.parseGitHub(input) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Sheet(onDismiss = onDismiss) {
        SheetHead(
            title = stringResource(R.string.clone_title),
            subtitle = stringResource(R.string.clone_body),
            icon = Codicons.RepoClone,
        )
        Field(
            value = input,
            onValueChange = { input = it },
            label = stringResource(R.string.clone_repo),
            placeholder = stringResource(R.string.clone_placeholder),
            leading = Codicons.Github,
            error = input.isNotBlank() && parsed == null,
            helper = if (input.isNotBlank() && parsed == null) stringResource(R.string.import_github_invalid) else "",
            autoCorrect = false,
            onImeAction = { if (parsed != null) onClone(input) },
            focusRequester = focus,
        )
        Row(
            Modifier.padding(top = 10.dp).heightIn(min = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (parsed != null) {
                Icon(Codicons.Repo, null, tint = Design.Colors.Text, modifier = Modifier.size(Design.Size.IconSmall))
                Text(
                    "${parsed.owner}/${parsed.repo}",
                    style = Design.Type.LabelSmall,
                    color = Design.Colors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                parsed.ref?.let { ref ->
                    Icon(Codicons.GitBranch, null, tint = Design.Colors.Text, modifier = Modifier.size(Design.Size.IconTiny))
                    Text(ref, style = Design.Type.MonoCaption, color = Design.Colors.TextSecondary, maxLines = 1)
                }
            }
        }
        Note(stringResource(R.string.clone_note))
        SheetActions {
            Btn(stringResource(R.string.common_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
            Btn(
                stringResource(R.string.clone_action),
                onClick = { onClone(input) },
                kind = BtnKind.Primary,
                enabled = parsed != null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** An import's progress, its result, or why it failed: the clone sheet's second half. */
@Composable
private fun ImportSheet(
    importing: ChatViewModel.ProjectImportState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (File) -> Unit,
) {
    val done = importing.result
    val failure = importing.failure
    Sheet(onDismiss = { if (importing.running) onCancel() else onDismiss() }) {
        SheetHead(
            title = stringResource(
                when {
                    failure != null -> R.string.import_failed_heading
                    done != null -> R.string.import_done_title
                    importing.fromGitHub -> R.string.import_cloning
                    else -> R.string.import_copying
                },
            ),
            subtitle = importing.source,
            icon = when {
                failure != null -> Codicons.Error
                done != null -> Codicons.Pass
                importing.fromGitHub -> Codicons.CloudDownload
                else -> Codicons.Folder
            },
            tone = when {
                failure != null -> TileTone.Error
                done != null -> TileTone.Accent
                else -> TileTone.Neutral
            },
        )
        if (failure != null) {
            Note(failure.resolve())
        } else {
            ProgressLine(done = done != null, modifier = Modifier.padding(top = 18.dp))
            Row(
                Modifier.padding(top = 12.dp).heightIn(min = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (done != null) {
                    Icon(Codicons.Pass, null, tint = Design.Colors.Accent, modifier = Modifier.size(Design.Size.IconSmall))
                }
                Text(progressText(importing), style = Design.Type.BodySmall, color = Design.Colors.TextSecondary)
            }
        }
        SheetActions {
            when {
                done != null -> {
                    Btn(stringResource(R.string.import_later), onClick = onDismiss, modifier = Modifier.weight(1f))
                    Btn(stringResource(R.string.import_open), onClick = { onOpen(done) }, kind = BtnKind.Primary, modifier = Modifier.weight(1f))
                }
                failure != null -> {
                    if (importing.fromGitHub) {
                        Btn(stringResource(R.string.common_back), onClick = onRetry, modifier = Modifier.weight(1f))
                    }
                    Btn(stringResource(R.string.common_close), onClick = onDismiss, kind = BtnKind.Primary, modifier = Modifier.weight(1f))
                }
                else -> Btn(stringResource(R.string.common_cancel), onClick = onCancel, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun progressText(importing: ChatViewModel.ProjectImportState): String = when (val progress = importing.progress) {
    is ProjectImport.Progress.Writing -> if (importing.result != null) {
        stringResource(R.string.import_written, progress.files, bytesText(progress.bytes))
    } else {
        stringResource(R.string.import_unpacking, pluralStringResource(R.plurals.files_count, progress.files, progress.files))
    }
    is ProjectImport.Progress.Downloading -> stringResource(R.string.import_downloading, bytesText(progress.bytes))
    null -> if (importing.result != null) stringResource(R.string.import_done_title) else stringResource(R.string.import_starting)
}

@Composable
private fun RenameSheet(project: ProjectsViewModel.Project, viewModel: ProjectsViewModel, onDone: (File?) -> Unit) {
    var name by rememberSaveable(project.name) { mutableStateOf(project.name) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clean = name.trim()
    val valid = ProjectsViewModel.validName(clean)
    Sheet(onDismiss = { onDone(null) }) {
        SheetHead(title = stringResource(R.string.rename_title), subtitle = project.name, icon = Codicons.Edit)
        Field(
            value = name,
            onValueChange = {
                name = it
                failed = false
            },
            label = stringResource(R.string.create_name),
            leading = Codicons.Folder,
            error = failed || (clean.isNotEmpty() && !valid),
            helper = when {
                failed -> stringResource(R.string.rename_failed)
                clean.isNotEmpty() && !valid -> stringResource(R.string.create_name_invalid)
                else -> "~/projects/$clean"
            },
            autoCorrect = false,
        )
        SheetActions {
            Btn(stringResource(R.string.common_cancel), onClick = { onDone(null) }, modifier = Modifier.weight(1f))
            Btn(
                stringResource(R.string.common_save),
                onClick = {
                    scope.launch {
                        val moved = viewModel.rename(project, clean)
                        if (moved == null) failed = true else onDone(moved)
                    }
                },
                kind = BtnKind.Primary,
                enabled = valid && clean != project.name,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The main menu lists this many; the rest are a tap on "All" away. */
private const val RECENT = 4
