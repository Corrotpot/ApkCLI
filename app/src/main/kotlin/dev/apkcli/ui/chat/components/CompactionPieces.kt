package dev.apkcli.ui.chat.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.apkcli.R
import dev.apkcli.core.agent.AgentEvent
import dev.apkcli.core.model.ConversationDigest
import dev.apkcli.core.store.ConversationStore
import dev.apkcli.ui.chat.ChatCommands
import dev.apkcli.ui.chat.ChatViewModel
import dev.apkcli.ui.chat.find.LocalFindItem
import dev.apkcli.ui.chat.find.LocalFindMarks
import dev.apkcli.ui.chat.markdown.MarkdownText
import dev.apkcli.ui.chat.markdown.markdownFindSegments
import dev.apkcli.ui.components.Collapse
import dev.apkcli.ui.components.Spinner
import dev.apkcli.ui.components.hairline
import dev.apkcli.ui.components.pressable
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.text.tokensText
import dev.apkcli.ui.theme.Design
import kotlinx.coroutines.delay

/*
 * /compact and the seams compactions leave in the transcript:
 * design/prototype/app.js commandsHtml, seamHtml and digestHtml.
 *
 * A seam is drawn where a compaction folded the history, after the last
 * message it took: a ruled line, how much of the context it freed, and the
 * summary itself, folded, as the agent reads it. The messages above stay on
 * screen and in the agent's search. While one runs — the line alone, with
 * its clock — or when one ended changing nothing — failed, stopped, not
 * worth it — the seam is at the end of the chat, and the next message takes
 * it away ([CompactionStatus]).
 */

/* ============================================================== commands */

/** What the commands plaque and the send button show of [ChatCommands.Command]. */
internal val ChatCommands.Command.icon: ImageVector
    get() = when (this) {
        ChatCommands.Command.COMPACT -> Codicons.Fold
    }

@get:StringRes
internal val ChatCommands.Command.title: Int
    get() = when (this) {
        ChatCommands.Command.COMPACT -> R.string.command_compact_title
    }

@get:StringRes
private val ChatCommands.Command.argument: Int
    get() = when (this) {
        ChatCommands.Command.COMPACT -> R.string.command_compact_arg
    }

/** A row of the commands plaque: a command the words can still become. */
class CommandRow(
    val command: ChatCommands.Command,
    /** The words already are this command: its row is lit. */
    val exact: Boolean,
    /** It can run now. */
    val enabled: Boolean,
    /** What it does, with the words typed after it; when it cannot run, why not. */
    val about: String,
)

/**
 * `.cmds`: over the composer, in the changes bar's place, while the words
 * begin with "/". A tap on a row runs its command.
 */
@Composable
fun CommandsPlaque(rows: List<CommandRow>, onRun: (ChatCommands.Command) -> Unit, modifier: Modifier = Modifier) {
    // `menu-in`: from 96 % and clear, over the menu's 120 ms, from the bottom edge.
    val entered = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entered.animateTo(1f, tween(Design.Motion.Menu, easing = Design.Motion.Standard)) }
    val shape = RoundedCornerShape(Design.Radius.Menu)
    Column(
        modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .fillMaxWidth()
            .graphicsLayer {
                val t = entered.value
                alpha = t
                scaleX = 0.96f + 0.04f * t
                scaleY = 0.96f + 0.04f * t
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .clip(shape)
            .background(Design.Colors.Surface2)
            .border(1.dp, Design.Colors.BorderStrong, shape)
            .padding(4.dp),
    ) {
        Text(
            stringResource(R.string.commands_title).uppercase(),
            style = Design.Type.Overline,
            color = Design.Colors.TextTertiary,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
        )
        rows.forEach { row -> CommandRowView(row, onRun) }
    }
}

/** `.cmd`: the glyph, `/name [what it takes]`, and what it does or why it cannot. */
@Composable
private fun CommandRowView(row: CommandRow, onRun: (ChatCommands.Command) -> Unit) {
    val shape = RoundedCornerShape(Design.Radius.MenuItem)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (row.exact) Design.Colors.Surface3 else Color.Transparent)
            .pressable(onClick = { onRun(row.command) }, enabled = row.enabled)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Icon(
                row.command.icon,
                contentDescription = null,
                tint = if (row.enabled) Design.Colors.Accent else Design.Colors.TextDisabled,
                modifier = Modifier.size(Design.Size.IconSmall),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "/${row.command.word}",
                    style = Design.Type.MonoLabel,
                    color = if (row.enabled) Design.Colors.Text else Design.Colors.TextDisabled,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    stringResource(row.command.argument),
                    style = Design.Type.Caption,
                    color = Design.Colors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                )
            }
            Text(
                row.about,
                style = Design.Type.BodySmall,
                color = if (row.enabled) Design.Colors.TextSecondary else Design.Colors.TextTertiary,
            )
        }
    }
}

/* ================================================================= seams */

/** Find in the chat's key for a summary's words: `seam:<section>:<block>`. */
private const val SEAM_FIND = "seam"

/**
 * Where a compaction folded the history: how much of the context it freed,
 * what it was asked to keep, and its summary under "Show".
 */
@Composable
fun CompactionSeam(stored: ConversationStore.StoredDigest, modifier: Modifier = Modifier) {
    val digest = stored.digest
    var open by rememberSaveable(stored.id) { mutableStateOf(false) }
    // And open while a search has a match in the summary.
    val found = LocalFindMarks.current?.opens(LocalFindItem.current, SEAM_FIND) == true
    val shown = digest != null && (open || found)
    val trigger = digest?.trigger
    Seam(
        glyph = { SeamGlyph(Codicons.Fold, Design.Colors.Accent) },
        title = stringResource(
            when (trigger) {
                // From a build that did not record who compacted, or how much.
                null -> R.string.chat_digest_seam
                ConversationDigest.TRIGGER_AUTO -> R.string.seam_done_auto
                else -> R.string.seam_done
            },
        ),
        modifier = modifier,
    ) {
        if (digest != null && trigger != null) {
            SeamCaption(stringResource(R.string.seam_freed, tokensText(digest.tokensFreed.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())))
            if (trigger == ConversationDigest.TRIGGER_AUTO) SeamCaption(stringResource(R.string.seam_auto_note))
            digest.focus?.let { SeamCaption(stringResource(R.string.seam_focus, it)) }
        }
        if (digest != null) {
            SeamAction(
                text = stringResource(if (shown) R.string.seam_hide else R.string.seam_show),
                onClick = { open = !shown },
                trailing = if (shown) Codicons.ChevronUp else Codicons.ChevronDown,
            )
            Box(Modifier.fillMaxWidth()) {
                Collapse(shown) { DigestCard(stored, digest) }
            }
        }
    }
}

/**
 * A compaction at the end of the chat: running, with its clock, or ended
 * having changed nothing — failed, with "Retry"; stopped; or not worth it.
 */
@Composable
fun CompactionStatus(run: ChatViewModel.CompactionRun, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (run.state) {
        ChatViewModel.CompactionRun.State.RUNNING -> {
            val elapsed by produceState(0L, run.startedAt) {
                while (true) {
                    value = (System.currentTimeMillis() - run.startedAt).coerceAtLeast(0L)
                    delay(1_000)
                }
            }
            // The line alone: what is happening, and for how long.
            Seam(
                glyph = { Spinner() },
                title = stringResource(R.string.seam_compacting, elapsedClock(elapsed)),
                modifier = modifier,
            )
        }

        ChatViewModel.CompactionRun.State.FAILED -> {
            val failure = run.failure
            Seam(
                glyph = { SeamGlyph(Codicons.Warning, Design.Colors.Warning) },
                title = stringResource(R.string.seam_failed),
                modifier = modifier,
            ) {
                SeamCaption(
                    stringResource(
                        if (failure?.kind == AgentEvent.CompactionFailed.Kind.NO_MODEL) {
                            R.string.seam_failed_no_model
                        } else {
                            R.string.seam_failed_body
                        },
                    ),
                )
                // The provider's or the parser's own words: what to go on when it fails again.
                failure?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(
                        detail,
                        style = Design.Type.MonoCaption,
                        color = Design.Colors.TextTertiary,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                if (run.manual) SeamAction(stringResource(R.string.seam_retry), onRetry, leading = Codicons.Refresh)
            }
        }

        ChatViewModel.CompactionRun.State.STOPPED -> Seam(
            glyph = { SeamGlyph(Codicons.DebugStop, Design.Colors.TextTertiary) },
            title = stringResource(R.string.seam_stopped),
            modifier = modifier,
        ) {
            SeamCaption(stringResource(R.string.seam_stopped_body))
        }

        ChatViewModel.CompactionRun.State.SKIPPED -> Seam(
            glyph = { SeamGlyph(Codicons.Info, Design.Colors.TextTertiary) },
            title = stringResource(R.string.seam_skipped),
            modifier = modifier,
        ) {
            SeamCaption(
                stringResource(
                    when (run.skipped) {
                        AgentEvent.CompactionSkipped.Why.TOO_SHORT -> R.string.seam_skipped_short
                        AgentEvent.CompactionSkipped.Why.NOTHING_NEW -> R.string.seam_skipped_nothing
                        else -> R.string.seam_skipped_body
                    },
                ),
            )
        }
    }
}

/** `.seam`: the ruled line with [glyph] and [title] in it, and what goes under it, centred. */
@Composable
private fun Seam(
    glyph: @Composable () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SeamRule()
            glyph()
            // At its own width; the rules take what is left.
            Text(title, style = Design.Type.LabelSmall, color = Design.Colors.TextSecondary, textAlign = TextAlign.Center)
            SeamRule()
        }
        content()
    }
}

@Composable
private fun RowScope.SeamRule() {
    Box(Modifier.weight(1f).height(1.dp).background(Design.Colors.Divider))
}

@Composable
private fun SeamGlyph(icon: ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Design.Size.IconSmall))
}

/** `.seam-sub`. */
@Composable
private fun SeamCaption(text: String) {
    Text(
        text,
        style = Design.Type.Caption,
        color = Design.Colors.TextTertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** `.link.seam-act`: an accent word with its glyph. */
@Composable
private fun SeamAction(text: String, onClick: () -> Unit, leading: ImageVector? = null, trailing: ImageVector? = null) {
    Row(
        Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leading?.let { SeamGlyph(it, Design.Colors.Accent) }
        Text(text, style = Design.Type.LabelSmall, color = Design.Colors.Accent)
        trailing?.let { SeamGlyph(it, Design.Colors.Accent) }
    }
}

/* ================================================================ digest */

/**
 * `.seam-digest`: the summary as the agent reads it, in the user's
 * language for its headings — [ConversationDigest]'s fields, the empty ones
 * left out — and what it costs in a request.
 */
@Composable
private fun DigestCard(stored: ConversationStore.StoredDigest, digest: ConversationDigest) {
    val shape = RoundedCornerShape(Design.Radius.ToolCard)
    val sections = remember(digest) { digestSections(digest) }
    Column(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Design.Colors.Surface1)
            .border(1.dp, Design.Colors.Border, shape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeamGlyph(Codicons.Fold, Design.Colors.Accent)
            Text(
                stringResource(R.string.digest_title),
                style = Design.Type.LabelSmall,
                color = Design.Colors.Text,
                modifier = Modifier.weight(1f),
            )
            if (stored.tokens > 0) {
                Text(tokensText(stored.tokens), style = Design.Type.MonoCaption, color = Design.Colors.TextTertiary)
            }
        }
        sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(section.title).uppercase(),
                    style = Design.Type.Overline,
                    color = Design.Colors.TextTertiary,
                )
                MarkdownText(
                    text = section.markdown,
                    style = Design.Type.BodySmall,
                    findPrefix = "$SEAM_FIND:${section.key}",
                )
            }
        }
        Text(
            stringResource(R.string.digest_foot),
            style = Design.Type.Caption,
            color = Design.Colors.TextTertiary,
            modifier = Modifier
                .fillMaxWidth()
                .hairline(Design.Colors.Divider, top = true)
                .padding(top = 10.dp),
        )
    }
}

/** A part of a summary as its card shows it: the heading, and the words as Markdown. */
internal class DigestSection(val key: String, @StringRes val title: Int, val markdown: String)

/**
 * The parts of [digest] that say something, in the card's order. Pure, so
 * that find in the chat lists the words exactly as the card draws them
 * ([digestFindSegments]).
 */
internal fun digestSections(digest: ConversationDigest): List<DigestSection> = buildList {
    fun paragraph(key: String, @StringRes title: Int, text: String) {
        if (text.isNotBlank()) add(DigestSection(key, title, text.trim()))
    }

    fun list(key: String, @StringRes title: Int, items: List<String>, ordered: Boolean = false) {
        val lines = items.map(::oneLine).filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        val markdown = lines.mapIndexed { i, line -> if (ordered) "${i + 1}. $line" else "- $line" }.joinToString("\n")
        add(DigestSection(key, title, markdown))
    }

    paragraph("objective", R.string.digest_objective, digest.objective)
    paragraph("state", R.string.digest_state, digest.currentState)
    list("kept", R.string.digest_kept, digest.keptByRequest)
    list("constraints", R.string.digest_constraints, digest.constraints)
    list("preferences", R.string.digest_preferences, digest.userPreferences)
    list("decisions", R.string.digest_decisions, digest.decisions.map { if (it.why.isBlank()) it.what else "${it.what} — ${it.why}" })
    list("failed", R.string.digest_failed, digest.failedApproaches.map { "${it.approach} → ${it.whyItFailed}" })
    list("files", R.string.digest_files, digest.fileMap.map { "${codeSpan(it.path)} — ${it.summary}" })
    // A change taken back is struck through, as the agent reads "(reverted)".
    list("edits", R.string.digest_edits, digest.edits.map { "${codeSpan(it.path)} — ${it.description}".let { line -> if (it.applied) line else "~~$line~~" } })
    list("commands", R.string.digest_commands, digest.commands.map { "${codeSpan(it.command)} → ${it.outcome}" + (it.exitCode?.let { code -> " (exit $code)" } ?: "") })
    val pins = digest.verbatimPins.filter { it.isNotBlank() }
    if (pins.isNotEmpty()) add(DigestSection("verbatim", R.string.digest_verbatim, pins.joinToString("\n\n", transform = ::fenced)))
    list("open", R.string.digest_open, digest.openThreads)
    list("next", R.string.digest_next, digest.nextSteps, ordered = true)
    list("terms", R.string.digest_terms, digest.glossary.map { "**${oneLine(it.term)}**: ${it.meaning}" })
}

/** What find in the chat searches under a seam: its summary, under the keys the card draws it with. */
internal fun digestFindSegments(digest: ConversationDigest?): List<Pair<String, String>> =
    digest?.let { d -> digestSections(d).flatMap { markdownFindSegments(it.markdown, "$SEAM_FIND:${it.key}") } }.orEmpty()

/** A list item stays one item: the model's line breaks inside it become spaces. */
private fun oneLine(text: String): String = text.replace(LINE_BREAKS, " ").trim()

private val LINE_BREAKS = Regex("""\s*\n\s*""")

/** [text] as inline code, fenced by one more backtick than the longest run inside it. */
private fun codeSpan(text: String): String {
    val words = oneLine(text)
    if (words.isEmpty()) return words
    val fence = "`".repeat(longestRun(words, '`') + 1)
    val pad = if (words.startsWith('`') || words.endsWith('`')) " " else ""
    return "$fence$pad$words$pad$fence"
}

/** [text] as a code block, kept exactly, fenced by more backticks than it holds in a row. */
private fun fenced(text: String): String {
    val fence = "`".repeat(maxOf(3, longestRun(text, '`') + 1))
    return "$fence\n${text.trimEnd()}\n$fence"
}

private fun longestRun(text: String, char: Char): Int {
    var longest = 0
    var run = 0
    for (c in text) {
        run = if (c == char) run + 1 else 0
        if (run > longest) longest = run
    }
    return longest
}
