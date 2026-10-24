package dev.apkcli.ui.chat.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.apkcli.R
import dev.apkcli.core.agent.ContextBudget
import dev.apkcli.core.agent.ToolDisplay
import dev.apkcli.core.agent.UiBridge
import dev.apkcli.core.model.Block
import dev.apkcli.core.model.Message
import dev.apkcli.ui.chat.ChatCommands
import dev.apkcli.ui.chat.ChatViewModel
import dev.apkcli.ui.chat.ImageAttachments
import dev.apkcli.ui.chat.find.rememberFindable
import dev.apkcli.ui.components.Btn
import dev.apkcli.ui.components.BtnKind
import dev.apkcli.ui.components.Collapse
import dev.apkcli.ui.components.IconBtn
import dev.apkcli.ui.components.IconBtnSize
import dev.apkcli.ui.components.Spinner
import dev.apkcli.ui.components.Tile
import dev.apkcli.ui.components.TileTone
import dev.apkcli.ui.components.hairline
import dev.apkcli.ui.components.pressable
import dev.apkcli.ui.components.rememberFollowingScrollState
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.text.resolve
import dev.apkcli.ui.text.tokensText
import dev.apkcli.ui.theme.Design
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * The agent tab's parts, as design/prototype/app.js draws them: the user's
 * bubble with its checkpoint above it, the reasoning block, the change bar,
 * the composer, the empty state, and the notices that sit over the
 * transcript.
 */

/* ============================================================ checkpoint */

/**
 * The checkpoint above each message of the user's, as Kiro draws it: a pin,
 * the word, a rule, and "Restore" — which takes the chat, the context and
 * the agent's file changes back to before that message.
 */
@Composable
fun CheckpointRow(restoring: Boolean, enabled: Boolean, onRestore: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(28.dp).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Codicons.Location, contentDescription = null, tint = Design.Colors.TextTertiary, modifier = Modifier.size(Design.Size.IconTiny))
        Text(stringResource(R.string.checkpoint), style = Design.Type.LabelTiny, color = Design.Colors.TextTertiary)
        Box(Modifier.weight(1f).height(1.dp).background(Design.Colors.Divider))
        if (restoring) {
            Spinner(color = Design.Colors.Accent)
            Text(stringResource(R.string.checkpoint_restoring), style = Design.Type.LabelTiny, color = Design.Colors.TextSecondary)
        } else {
            Text(
                stringResource(R.string.checkpoint_restore),
                style = Design.Type.LabelTiny,
                color = if (enabled) Design.Colors.Accent else Design.Colors.TextDisabled,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled, role = Role.Button, onClick = onRestore)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/* ================================================================= user */

/** `.u`: right-aligned, surface 3, the tail corner small, images above the words. */
@Composable
fun UserBubble(message: Message, modifier: Modifier = Modifier) {
    val images = remember(message.id) { message.content.filterIsInstance<Block.Image>() }
    val text = message.text
    val shape = RoundedCornerShape(
        topStart = Design.Radius.Bubble,
        topEnd = Design.Radius.Bubble,
        bottomEnd = Design.Radius.BubbleTail,
        bottomStart = Design.Radius.Bubble,
    )
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .widthIn(max = BubbleMax)
                .clip(shape)
                .background(Design.Colors.Surface3)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (images.isNotEmpty()) {
                Row(
                    Modifier
                        .offset(x = (-8).dp, y = (-4).dp)
                        .padding(bottom = if (text.isNotBlank()) 4.dp else 0.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    images.forEachIndexed { index, image -> SentImage("${message.id}#$index", image) }
                }
            }
            if (text.isNotBlank()) {
                // Find in the chat's segment "u" (ChatScreen lists it).
                val findable = rememberFindable("u", text)
                Text(
                    findable.text,
                    style = Design.Type.Body,
                    color = Design.Colors.Text,
                    onTextLayout = findable.onTextLayout,
                    modifier = findable.modifier,
                )
            }
        }
    }
}

/** 85 % of a phone's width; on a wide pane the bubble still reads as a bubble. */
private val BubbleMax = 520.dp

@Composable
private fun SentImage(key: String, image: Block.Image) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key) {
        value = withContext(Dispatchers.Default) { ImageAttachments.thumbnail(key, image) }
    }
    Box(
        Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Design.Colors.Surface2),
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = image.label ?: stringResource(R.string.chat_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/* ============================================================ reasoning */

/**
 * `.think-head` and `.think-body`: a chevron, the accent thinking glyph and
 * "Reasoning · 4,2 s", over a quiet, ruled block that scrolls inside itself.
 * Collapsed once the answer starts: it is usually longer than the answer
 * and not what the user came for, but it is paid for, and when the agent
 * gets something wrong it is where to look.
 */
@Composable
fun ThinkingBlock(
    text: String,
    expanded: Boolean,
    inProgress: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tokens: Int = 0,
    /** Wall-clock start while it streams, for "Thinking · 12 s". */
    startedAt: Long? = null,
    scroll: ScrollState? = null,
) {
    val ownScroll = rememberScrollState()
    if (text.isBlank()) return
    val spoken = stringResource(if (expanded) R.string.a11y_reasoning_shown else R.string.a11y_reasoning_hidden)
    val elapsed by produceState(0L, inProgress, startedAt) {
        if (!inProgress || startedAt == null) return@produceState
        while (true) {
            value = System.currentTimeMillis() - startedAt
            delay(1_000)
        }
    }
    val label = when {
        inProgress && startedAt != null -> stringResource(R.string.chat_thinking_for, durationText(elapsed.coerceAtLeast(0)))
        inProgress -> stringResource(R.string.chat_thinking)
        tokens > 0 -> stringResource(R.string.chat_reasoning_tokens, tokensText(tokens))
        else -> stringResource(R.string.chat_reasoning)
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(start = 2.dp, end = 8.dp)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    role = Role.Button
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (expanded) Codicons.ChevronDown else Codicons.ChevronRight,
                contentDescription = null,
                tint = Design.Colors.TextSecondary,
                modifier = Modifier.size(Design.Size.IconSmall),
            )
            Icon(Codicons.Thinking, contentDescription = null, tint = Design.Colors.Accent, modifier = Modifier.size(Design.Size.IconSmall))
            Text(label, style = Design.Type.LabelSmall, color = Design.Colors.TextSecondary)
        }
        Collapse(expanded) {
            // A box of its own height that scrolls inside: as it streams it
            // follows its newest line, until the user drags it to read back.
            val following = rememberFollowingScrollState(following = inProgress, scroll = scroll ?: ownScroll)
            val pieces = remember(text) { reasoningPieces(text) }
            Box(
                Modifier
                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                    .heightIn(max = 240.dp)
                    .drawBehind { drawRect(Design.Colors.BorderStrong, size = Size(2.dp.toPx(), size.height)) },
            ) {
                Column(
                    Modifier
                        .verticalScroll(following)
                        .padding(start = 14.dp, end = 4.dp),
                ) {
                    // By position: pieces are only ever added at the end, or
                    // the last one grows. Find in the chat's segments
                    // "think:i"; the streaming turn's reasoning is drawn
                    // outside any item and passes through.
                    pieces.forEachIndexed { i, piece ->
                        val findable = rememberFindable("think:$i", piece.text)
                        Text(
                            text = findable.text,
                            style = Design.Type.BodySmall,
                            color = Design.Colors.TextTertiary,
                            onTextLayout = findable.onTextLayout,
                            modifier = Modifier
                                .padding(top = if (piece.gap) PARAGRAPH_GAP else 0.dp)
                                .then(findable.modifier),
                        )
                    }
                }
            }
        }
    }
}

/** One piece of reasoning as [ThinkingBlock] draws it, a text each; [gap] when it opens a paragraph after another. */
internal class ReasoningPiece(val text: String, val gap: Boolean)

/**
 * Reasoning in pieces: its paragraphs, and a paragraph longer than
 * [REASONING_PIECE] characters in parts, cut at a line break, else a space.
 *
 * Drawn as one text, the whole reasoning was laid out again on every
 * streamed update, twenty times a second, and a long one made the chat
 * stutter. In pieces, only the last changes as it streams, and the others
 * are left as they were drawn. Every cut depends only on the text before
 * it, so a piece once complete stays the same as more arrives.
 */
internal fun reasoningPieces(text: String): List<ReasoningPiece> {
    val out = ArrayList<ReasoningPiece>()
    val n = text.length
    var i = 0
    while (i < n) {
        // The paragraph from i runs to a blank line, or to the end.
        var end = n
        var next = n
        var j = text.indexOf('\n', i)
        while (j in 0 until n) {
            var k = j + 1
            while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
            if (k < n && text[k] == '\n') {
                end = j
                next = k + 1
                break
            }
            j = text.indexOf('\n', j + 1)
        }
        val paragraph = text.substring(i, end).trimStart('\n').trimEnd()
        if (paragraph.isNotEmpty()) {
            var start = 0
            var first = true
            while (start < paragraph.length) {
                var cut = minOf(start + REASONING_PIECE, paragraph.length)
                if (cut < paragraph.length) {
                    val at = paragraph.lastIndexOf('\n', cut - 1).takeIf { it > start }
                        ?: paragraph.lastIndexOf(' ', cut - 1).takeIf { it > start }
                    if (at != null) cut = at + 1
                }
                val part = paragraph.substring(start, cut).trimEnd()
                if (part.isNotEmpty()) out += ReasoningPiece(part, gap = first && out.isNotEmpty())
                first = false
                start = cut
            }
        }
        i = next
    }
    return out
}

/** A piece of reasoning past this many characters is drawn in parts; see [reasoningPieces]. */
private const val REASONING_PIECE = 1_200

/** Between paragraphs of reasoning. */
private val PARAGRAPH_GAP = 8.dp

/** `.waiting`: the turn has started and nothing has arrived yet. */
@Composable
fun WaitingRow(text: String) {
    Row(
        Modifier.height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spinner(color = Design.Colors.Accent)
        Text(text, style = Design.Type.LabelSmall, color = Design.Colors.TextSecondary)
    }
}

/* ================================================================= plan */

/**
 * The agent's plan, one line folded — "Plan 2/4 · Writing tests" — over the
 * transcript; open, the `.tasks` list, about six rows before it scrolls.
 */
@Composable
fun PlanCard(tasks: List<UiBridge.Task>, expanded: Boolean, onToggle: () -> Unit, onDismiss: () -> Unit) {
    val done = tasks.count { it.done }
    val current = tasks.firstOrNull { it.active } ?: tasks.firstOrNull { !it.done }
    val shape = RoundedCornerShape(Design.Radius.ToolCard)
    Column(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Design.Colors.Surface1)
            .border(1.dp, Design.Colors.Border, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .heightIn(min = 40.dp)
                .padding(start = 14.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Codicons.Checklist, null, tint = Design.Colors.Text, modifier = Modifier.size(Design.Size.IconSmall))
            Text(stringResource(R.string.chat_plan, done, tasks.size), style = Design.Type.Label, color = Design.Colors.Text)
            Text(
                if (!expanded && current != null) "· ${current.text}" else "",
                style = Design.Type.BodySmall,
                color = Design.Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Codicons.ChevronUp else Codicons.ChevronDown,
                null,
                tint = Design.Colors.TextTertiary,
                modifier = Modifier.size(Design.Size.IconSmall),
            )
            IconBtn(Codicons.Close, stringResource(R.string.chat_plan_dismiss), onClick = onDismiss, size = IconBtnSize.Composer)
        }
        Collapse(expanded) {
            Column(
                Modifier
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            ) {
                tasks.forEach { task -> TaskLine(ToolDisplay.Tasks.Item(task.text, task.done, task.active)) }
            }
        }
    }
}

/** `.task`: the glyph — done, now, to do — and the step. */
@Composable
fun TaskLine(item: ToolDisplay.Tasks.Item) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (item.done) Codicons.PassFilled else Codicons.CircleLarge,
            contentDescription = null,
            tint = when {
                item.done -> Design.Colors.Success
                item.active -> Design.Colors.Accent
                else -> Design.Colors.TextTertiary
            },
            modifier = Modifier.padding(top = 2.dp).size(Design.Size.IconTiny),
        )
        Text(
            item.text,
            style = Design.Type.BodySmall,
            color = if (item.done) Design.Colors.TextTertiary else Design.Colors.TextBody,
            modifier = Modifier.weight(1f),
        )
    }
}

/* ============================================================== notices */

/** No key for the connection requests go to: the one thing that stops everything. */
@Composable
fun KeyNoticeCard(endpointName: String, onOpenSettings: () -> Unit) {
    NoticeCard(
        icon = Codicons.Key,
        tone = TileTone.Warning,
        title = stringResource(R.string.setup_needs_key),
        body = stringResource(R.string.setup_needs_key_body, endpointName),
        action = stringResource(R.string.setup_open_settings),
        onAction = onOpenSettings,
    )
}

/**
 * A failed turn: the headline, what to do, and — one tap away — the server's
 * own words, which are usually the useful part.
 */
@Composable
fun ErrorCard(
    error: ChatViewModel.UiError,
    onDismiss: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    var showDetail by rememberSaveable(error.headline.toString()) { mutableStateOf(false) }
    val detail = error.iterationLimit?.let { stringResource(R.string.chat_iteration_limit_detail, it) } ?: error.detail
    val hasDetail = error.iterationLimit == null && detail.isNotBlank()
    val body = when {
        error.discoveredContextLimit != null -> stringResource(R.string.chat_error_limit_learned, error.discoveredContextLimit)
        error.body != null -> error.body.resolve()
        error.iterationLimit != null -> detail
        else -> stringResource(R.string.chat_error_see_details)
    }
    val shape = RoundedCornerShape(Design.Radius.ToolCard)
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Design.Colors.Surface1)
            .border(1.dp, Design.Colors.Error.copy(alpha = 0.35f), shape)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Codicons.Error, null, tint = Design.Colors.Error, modifier = Modifier.padding(top = 2.dp).size(Design.Size.IconSmall))
            Column(Modifier.weight(1f)) {
                Text(error.headline.resolve(), style = Design.Type.Label, color = Design.Colors.Text)
                Text(body, style = Design.Type.BodySmall, color = Design.Colors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
            }
            IconBtn(Codicons.Close, stringResource(R.string.common_close), onClick = onDismiss, size = IconBtnSize.Composer)
        }
        Collapse(showDetail && hasDetail) {
            Text(
                detail,
                style = Design.Type.CodeSmall,
                color = Design.Colors.TextBody,
                modifier = Modifier
                    .padding(top = 8.dp, end = 10.dp)
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        val settings = onOpenSettings?.takeIf { error.opensSettings }
        if (hasDetail || settings != null) {
            Row(Modifier.padding(start = 16.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (settings != null) {
                    Btn(stringResource(R.string.setup_open_settings), onClick = settings, kind = BtnKind.Ghost, icon = Codicons.SettingsGear)
                }
                if (hasDetail) {
                    Btn(
                        stringResource(if (showDetail) R.string.error_hide_details else R.string.error_details),
                        onClick = { showDetail = !showDetail },
                        kind = BtnKind.Ghost,
                    )
                }
            }
        }
    }
}

/** A line of information over the transcript, closable: compaction, a finished revert. */
@Composable
fun InfoLine(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector = Codicons.Info, onDismiss: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(Design.Radius.ToolCard)
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Design.Colors.Surface1)
            .border(1.dp, Design.Colors.Border, shape)
            .heightIn(min = 40.dp)
            .padding(start = 12.dp, end = if (onDismiss != null) 2.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = Design.Colors.Accent, modifier = Modifier.size(Design.Size.IconSmall))
        Text(text, style = Design.Type.BodySmall, color = Design.Colors.TextSecondary, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (onDismiss != null) IconBtn(Codicons.Close, stringResource(R.string.common_close), onClick = onDismiss, size = IconBtnSize.Composer)
    }
}

@Composable
private fun NoticeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tone: TileTone,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    val shape = RoundedCornerShape(Design.Radius.Card)
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Design.Colors.Surface1)
            .border(1.dp, Design.Colors.Border, shape)
            .padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Tile(icon, tone)
        Column(Modifier.weight(1f)) {
            Text(title, style = Design.Type.Label, color = Design.Colors.Text)
            Text(body, style = Design.Type.BodySmall, color = Design.Colors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
            Btn(action, onClick = onAction, kind = BtnKind.Primary, small = true, icon = Codicons.SettingsGear, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/* ================================================================= empty */

/** The suggestions a new chat offers, in the user's language. */
private val SUGGESTIONS = listOf(
    Codicons.ListTree to R.string.suggest_explain,
    Codicons.Beaker to R.string.suggest_tests,
    Codicons.Search to R.string.suggest_why_fails,
    Codicons.Package to R.string.suggest_update_deps,
)

/** `.chat-empty`: what the agent is for, and four ways to start. */
@Composable
fun EmptyChat(onSuggest: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Codicons.Sparkle, null, tint = Design.Colors.Accent, modifier = Modifier.padding(bottom = 14.dp).size(28.dp))
        Text(stringResource(R.string.chat_empty_title), style = Design.Type.Headline, color = Design.Colors.Text)
        Text(
            stringResource(R.string.chat_empty_body),
            style = Design.Type.Body,
            color = Design.Colors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SUGGESTIONS.forEach { (icon, text) ->
                val words = stringResource(text)
                val shape = RoundedCornerShape(Design.Radius.Button)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(shape)
                        .background(Design.Colors.Surface1)
                        .border(1.dp, Design.Colors.Border, shape)
                        .pressable(onClick = { onSuggest(words) })
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(icon, null, tint = Design.Colors.TextTertiary, modifier = Modifier.size(Design.Size.IconSmall))
                    Text(
                        words,
                        style = Design.Type.Body,
                        color = Design.Colors.TextBody,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/* =============================================================== changes */

/**
 * `.changes`: what the agent changed since the user last looked — "4 files
 * +64 −1" — with the list and "keep" right there.
 *
 * Nothing in it is ever cut. The count is short (the diff icon and the
 * green and red numbers say what kind of count it is), and when the sum and
 * the buttons do not fit on one line, the buttons move to a second line
 * under it rather than eat the numbers. The phone's width and the user's
 * font size decide; see [SumThenActions].
 */
@Composable
fun ChangesBar(
    count: Int,
    added: Int?,
    removed: Int?,
    onView: () -> Unit,
    onKeep: () -> Unit,
) {
    if (count == 0) return
    val shape = RoundedCornerShape(Design.Radius.Button)
    SumThenActions(
        Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(Design.Colors.Surface1)
            .border(1.dp, Design.Colors.Border, shape)
            .padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        sum = {
            Row(
                Modifier.heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Codicons.Diff, null, tint = Design.Colors.TextSecondary, modifier = Modifier.size(Design.Size.IconSmall))
                Text(
                    buildAnnotatedString {
                        append(pluralStringResource(R.plurals.changes_files, count, count))
                        if (added != null && added > 0) {
                            append(" ")
                            withStyle(SpanStyle(color = Design.Colors.Accent)) { append("+$added") }
                        }
                        if (removed != null && removed > 0) {
                            append(" ")
                            withStyle(SpanStyle(color = Design.Colors.Error)) { append("\u2212$removed") }
                        }
                    },
                    style = Design.Type.LabelSmall,
                    color = Design.Colors.Text,
                )
            }
        },
        actions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GhostAction(stringResource(R.string.changes_view_short), Design.Colors.Accent, onView)
                GhostAction(stringResource(R.string.changes_keep_short), Design.Colors.TextSecondary, onKeep)
            }
        },
    )
}

/**
 * [sum] at the start and [actions] at the end of one line when both fit at
 * their full width, otherwise [actions] on a second line, at the end. The
 * sum is never measured narrower than it wants, so it is never cut; only a
 * sum wider than the whole bar wraps onto more lines of its own.
 */
@Composable
private fun SumThenActions(
    modifier: Modifier,
    sum: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Layout(contents = listOf(sum, actions), modifier = modifier) { (sums, actionList), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val actionPlaceables = actionList.map { it.measure(loose) }
        val actionsWidth = actionPlaceables.maxOfOrNull { it.width } ?: 0
        val actionsHeight = actionPlaceables.maxOfOrNull { it.height } ?: 0
        // The sum at the width it wants, or the bar's if it wants more.
        val natural = sums.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val sumPlaceables = sums.map { it.measure(loose.copy(maxWidth = natural.coerceAtMost(constraints.maxWidth))) }
        val sumWidth = sumPlaceables.maxOfOrNull { it.width } ?: 0
        val sumHeight = sumPlaceables.maxOfOrNull { it.height } ?: 0
        val gap = 8.dp.roundToPx()
        val oneLine = sumWidth + gap + actionsWidth <= constraints.maxWidth
        val width = constraints.maxWidth
        val height = if (oneLine) maxOf(sumHeight, actionsHeight) else sumHeight + actionsHeight
        layout(width, height.coerceAtLeast(constraints.minHeight)) {
            val total = height.coerceAtLeast(constraints.minHeight)
            if (oneLine) {
                sumPlaceables.forEach { it.place(0, (total - it.height) / 2) }
                actionPlaceables.forEach { it.place(width - it.width, (total - it.height) / 2) }
            } else {
                sumPlaceables.forEach { it.place(0, 0) }
                actionPlaceables.forEach { it.place(width - it.width, sumHeight) }
            }
        }
    }
}

/* ================================================================== find */

/**
 * `.find-nav`: the composer's place while searching the chat — where the
 * reader is among the matches ([text]), ↑ to older ones and ↓ to newer,
 * both wrapping. The hint shown before anything is typed may take two
 * lines on a narrow phone rather than be cut.
 */
@Composable
fun FindNav(text: String, canMove: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Design.Colors.Surface1)
            .hairline(Design.Colors.Border, top = true)
            .height(56.dp)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text,
            style = Design.Type.LabelSmall,
            color = Design.Colors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        IconBtn(Codicons.ChevronUp, stringResource(R.string.find_previous), onClick = onPrevious, enabled = canMove)
        IconBtn(Codicons.ChevronDown, stringResource(R.string.find_next), onClick = onNext, enabled = canMove)
    }
}

/** `.changes .btn.ghost`: 32 dp, tighter than the usual ghost button. */
@Composable
private fun GhostAction(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(Design.Radius.Tile))
            .pressable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Design.Type.LabelSmall, color = color, maxLines = 1)
    }
}

/* ============================================================== composer */

/**
 * `.composer`: one box, the text above and the controls below — attach, the
 * context ring, the model, the reasoning level, send. The pickers are a
 * value and a chevron with no box of their own: the row already sits inside
 * the composer's border, and two more outlines there read as clutter.
 */
@Composable
fun ComposerBox(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    busy: Boolean,
    enabled: Boolean,
    accounting: ContextBudget.Accounting?,
    modelLabel: String,
    reasoningLabel: String?,
    onModel: () -> Unit,
    onReasoning: () -> Unit,
    onContext: () -> Unit,
    attachments: List<ChatViewModel.Attachment>,
    preparingAttachments: Int,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    maxAttachments: Int,
    warning: String? = null,
    /** The words are this command: send runs it, under its own glyph. */
    command: ChatCommands.Command? = null,
    /** [command] can run now. */
    commandEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Design.Radius.Composer)
    val pending = attachments.size + preparingAttachments

    Box(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Design.Colors.Surface1)
                .border(1.dp, if (focused) Design.Colors.BorderFocus else Design.Colors.BorderStrong, shape)
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
        ) {
            if (pending > 0) {
                AttachmentStrip(attachments, preparingAttachments, onRemoveAttachment)
            }
            warning?.let {
                Text(it, style = Design.Type.Caption, color = Design.Colors.Warning, modifier = Modifier.padding(end = 8.dp, bottom = 8.dp))
            }
            Box(Modifier.fillMaxWidth().padding(end = 8.dp).heightIn(min = 22.dp, max = 132.dp)) {
                if (value.isEmpty()) {
                    Text(stringResource(R.string.chat_composer_hint), style = Design.Type.Body, color = Design.Colors.TextTertiary)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled || busy,
                    interactionSource = interaction,
                    textStyle = Design.Type.Body.copy(color = Design.Colors.Text),
                    cursorBrush = SolidColor(Design.Colors.Accent),
                    // No ImeAction.Send: a multi-line instruction is the norm,
                    // and hijacking Enter to send makes writing one infuriating.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconBtn(
                    Codicons.Attach,
                    contentDescription = stringResource(R.string.a11y_attach_image),
                    onClick = onAttach,
                    size = IconBtnSize.Composer,
                    iconSize = Design.Size.Icon,
                    enabled = (enabled || busy) && pending < maxAttachments,
                )
                ContextRing(accounting, onContext)
                Pick(modelLabel, stringResource(R.string.composer_model, modelLabel), onModel, Modifier.weight(1f, fill = false))
                if (reasoningLabel != null) {
                    Pick(reasoningLabel, stringResource(R.string.composer_reasoning, reasoningLabel), onReasoning)
                }
                Box(Modifier.weight(1f))
                if (busy) {
                    SendButton(enabled = true, description = stringResource(R.string.a11y_stop_turn), onClick = onStop) {
                        Box(Modifier.size(10.dp).background(Design.Colors.OnPrimary, RoundedCornerShape(2.dp)))
                    }
                } else if (command != null) {
                    val canRun = enabled && commandEnabled
                    SendButton(enabled = canRun, description = stringResource(command.title), onClick = onSend) {
                        Icon(
                            command.icon,
                            null,
                            tint = if (canRun) Design.Colors.OnPrimary else Design.Colors.TextDisabled,
                            modifier = Modifier.size(Design.Size.IconSmall),
                        )
                    }
                } else {
                    val canSend = enabled && preparingAttachments == 0 && (value.isNotBlank() || attachments.isNotEmpty())
                    SendButton(enabled = canSend, description = stringResource(R.string.a11y_send_message), onClick = onSend) {
                        Icon(
                            Codicons.ArrowUp,
                            null,
                            tint = if (canSend) Design.Colors.OnPrimary else Design.Colors.TextDisabled,
                            modifier = Modifier.size(Design.Size.IconSmall),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(Design.Size.Send)
            .clip(CircleShape)
            .background(if (enabled) Design.Colors.Primary else Design.Colors.Surface3)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** `.pick`: the value and a chevron, 30 dp; the press shows, the box does not. */
@Composable
private fun Pick(text: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(Design.Size.Chip)
            .clip(RoundedCornerShape(Design.Radius.Tile))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 8.dp, end = 2.dp)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text,
            style = Design.Type.LabelSmall,
            color = Design.Colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(Codicons.ChevronDown, null, tint = Design.Colors.TextSecondary, modifier = Modifier.size(Design.Size.IconSmall))
    }
}

/** `.ring`: how full the window is, a 20 dp arc on a 36 dp target that opens the breakdown. */
@Composable
private fun ContextRing(accounting: ContextBudget.Accounting?, onClick: () -> Unit) {
    val fraction = (accounting?.fraction ?: 0f).coerceIn(0f, 1f)
    val animated by animateFloatAsState(fraction, tween(400), label = "context-ring")
    val percent = (fraction * 100).toInt()
    val spoken = stringResource(R.string.a11y_context_meter, percent)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Design.Size.Ring)
                .drawBehind {
                    val stroke = 2.5.dp.toPx()
                    val radius = 8.dp.toPx()
                    val topLeft = Offset(center.x - radius, center.y - radius)
                    val arc = Size(radius * 2, radius * 2)
                    drawArc(Design.Colors.Surface3, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
                    if (animated > 0f) {
                        drawArc(Design.Colors.Accent, -90f, 360f * animated, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                },
        )
    }
}

/** `.attachments`: 56 dp thumbs, each with a round close in its corner. */
@Composable
private fun AttachmentStrip(
    attachments: List<ChatViewModel.Attachment>,
    preparing: Int,
    onRemove: (String) -> Unit,
) {
    val shape = RoundedCornerShape(Design.Radius.Tile)
    Row(
        Modifier
            .padding(end = 8.dp, bottom = 10.dp, top = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            val name = attachment.image.label ?: stringResource(R.string.chat_image)
            Box(Modifier.padding(top = 6.dp, end = 6.dp)) {
                Image(
                    bitmap = attachment.thumbnail,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(shape).border(1.dp, Design.Colors.Border, shape),
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-6).dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Design.Colors.Surface3)
                        .border(1.dp, Design.Colors.BorderStrong, CircleShape)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.a11y_remove_image, name),
                            onClick = { onRemove(attachment.id) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Codicons.Close, stringResource(R.string.a11y_remove_image, name), tint = Design.Colors.Text, modifier = Modifier.size(Design.Size.IconTiny))
                }
            }
        }
        repeat(preparing) {
            Box(
                Modifier.padding(top = 6.dp).size(56.dp).clip(shape).border(1.dp, Design.Colors.Border, shape),
                contentAlignment = Alignment.Center,
            ) { Spinner() }
        }
    }
}
