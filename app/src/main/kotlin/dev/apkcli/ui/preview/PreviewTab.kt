package dev.apkcli.ui.preview

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.apkcli.R
import dev.apkcli.core.runtime.TerminalSessions
import dev.apkcli.ui.EditorBus
import dev.apkcli.ui.components.Btn
import dev.apkcli.ui.components.BtnKind
import dev.apkcli.ui.components.IconBtn
import dev.apkcli.ui.components.hairline
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.theme.Design
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The project's site, in the app: what a dev server started in a terminal
 * serves (`npm run dev`, `python -m http.server`), or the project's own
 * pages, served by the preview itself, with no server.
 *
 * Servers are found where they say where they are: the output of the
 * terminals still running, the user's and the agent's. With nothing shown
 * yet, the newest of them is, or else the project's `index.html`. A page of
 * the project's reloads when its files change, after an agent's edit and at
 * the end of each turn; a dev server reloads its own pages.
 */
@Composable
fun PreviewTab(
    state: PreviewState,
    workspace: File?,
    /** Read off the main thread: the first read starts the terminal registry. */
    terminals: () -> TerminalSessions?,
    /** The preview is the whole screen, so back steps through its pages; beside the chat it is the chat's. */
    alone: Boolean,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(workspace) { state.openProject(workspace) }

    val found by produceState(Found(), workspace) {
        while (true) {
            value = withContext(Dispatchers.Default) { find(workspace, terminals()) }
            delay(FIND_EVERY_MS)
        }
    }

    // Nothing shown yet: the newest server, or the project's own page.
    LaunchedEffect(state.url, found) {
        if (state.url != null) return@LaunchedEffect
        val first = found.servers.lastOrNull() ?: PreviewUrls.PROJECT_ROOT.takeIf { found.projectPage }
        first?.let(state::load)
    }

    // A page of the project's follows its files; back on screen, it shows what changed meanwhile.
    LaunchedEffect(Unit) {
        if (PreviewUrls.isProject(state.url)) state.reload()
        EditorBus.treeRefresh.collectLatest {
            delay(RELOAD_AFTER_MS)
            if (PreviewUrls.isProject(state.url)) state.reload()
        }
    }

    BackHandler(enabled = alone && state.canGoBack) { state.back() }

    Column(modifier.fillMaxSize().background(Design.Colors.Bg)) {
        AddressBar(state)
        LoadLine(state.progress)
        val others = found.servers.filter { it != state.url }.takeLast(MAX_SHOWN).asReversed()
        val projectPage = found.projectPage && !PreviewUrls.isProject(state.url)
        if (others.isNotEmpty() || projectPage) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                others.forEach { server ->
                    Btn(PreviewUrls.label(server), onClick = { state.load(server) }, small = true, icon = Codicons.Globe)
                }
                if (projectPage) {
                    Btn("index.html", onClick = { state.load(PreviewUrls.PROJECT_ROOT) }, small = true, icon = Codicons.File)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.url == null) {
                EmptyPreview()
            } else {
                AndroidView(factory = { context -> state.view(context) }, modifier = Modifier.fillMaxSize())
                state.failed?.let { address -> NoAnswer(address, onRetry = state::reload) }
            }
        }
    }
}

/** Where to go: the address as the page has it, editable; reload; the system's browser. */
@Composable
private fun AddressBar(state: PreviewState) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val shown = state.url?.let(PreviewUrls::label).orEmpty()
    var text by remember(shown) { mutableStateOf(shown) }
    var editing by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Design.Radius.Input)
    Row(
        Modifier
            .fillMaxWidth()
            .hairline(Design.Colors.Border)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(shape)
                .background(Design.Colors.Surface2)
                .border(1.dp, if (editing) Design.Colors.BorderFocus else Design.Colors.Border, shape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (PreviewUrls.isProject(state.url)) Codicons.File else Codicons.Globe,
                contentDescription = null,
                tint = Design.Colors.TextTertiary,
                modifier = Modifier.size(Design.Size.IconSmall),
            )
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    Text(stringResource(R.string.preview_address_hint), style = Design.Type.BodyMedium, color = Design.Colors.TextTertiary, maxLines = 1)
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = Design.Type.BodyMedium.copy(color = Design.Colors.Text),
                    cursorBrush = SolidColor(Design.Colors.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = {
                        PreviewUrls.fromInput(text)?.let(state::load)
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focus ->
                            editing = focus.isFocused
                            // Leaving the field without going anywhere puts the page's address back.
                            if (!focus.isFocused) text = shown
                        },
                )
            }
        }
        IconBtn(Codicons.Refresh, stringResource(R.string.preview_reload), onClick = state::reload, enabled = state.url != null)
        // The project's pages exist only in here; anything else opens where the user browses.
        val external = state.url?.takeIf { !PreviewUrls.isProject(it) }
        IconBtn(
            Codicons.LinkExternal,
            stringResource(R.string.preview_open_browser),
            onClick = {
                external?.let { address ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            },
            enabled = external != null,
        )
    }
}

/** How far the page has loaded: an accent line that fills, gone once it has. */
@Composable
private fun LoadLine(progress: Int) {
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        if (progress in 1..99) {
            Box(Modifier.fillMaxWidth(progress / 100f).height(2.dp).background(Design.Colors.Accent))
        }
    }
}

@Composable
private fun EmptyPreview() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(Codicons.Globe, contentDescription = null, tint = Design.Colors.TextTertiary, modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.preview_empty_title), style = Design.Type.LabelMedium, color = Design.Colors.Text, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.preview_empty_body),
            style = Design.Type.BodySmall,
            color = Design.Colors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}

/** Over the browser's own error page: what did not answer, and a way to try again. */
@Composable
private fun NoAnswer(address: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Design.Colors.Bg).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(Codicons.Warning, contentDescription = null, tint = Design.Colors.Warning, modifier = Modifier.size(32.dp))
        Text(
            stringResource(R.string.preview_no_answer, PreviewUrls.label(address)),
            style = Design.Type.BodyMedium,
            color = Design.Colors.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Btn(stringResource(R.string.preview_retry), onClick = onRetry, kind = BtnKind.Primary, small = true, icon = Codicons.Refresh)
    }
}

/** What there is to show: servers the terminals name, newest last; whether the project has a page of its own. */
private data class Found(val servers: List<String> = emptyList(), val projectPage: Boolean = false)

private fun find(workspace: File?, terminals: TerminalSessions?): Found {
    val servers = terminals?.sessions?.value.orEmpty()
        .filter { !it.isTranscript && it.state.value == TerminalSessions.State.RUNNING }
        .flatMap { PreviewUrls.localUrls(it.plainTail(TAIL_CHARS)) }
        .distinct()
    val page = workspace?.let { File(it, "index.html").isFile } == true
    return Found(servers, page)
}

/** How often the terminals are read for servers while the preview is on screen. */
private const val FIND_EVERY_MS = 2_000L

/** After a change, before reloading: edits come in bursts. */
private const val RELOAD_AFTER_MS = 400L

/** Of each terminal's output, where a server's address is looked for. */
private const val TAIL_CHARS = 32_000

private const val MAX_SHOWN = 3
