package dev.apkcli.ui.chat.find

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import dev.apkcli.ui.theme.Design
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/*
 * Find in the chat: design/prototype/app.js applyFind().
 *
 * What is searched is what the transcript shows, piece by piece: the user's
 * messages, the answers (as rendered, without their Markdown marks), the
 * reasoning, each tool card's target and output, diff lines. A piece is a
 * *segment*, keyed the same way by the code that lists them for the search
 * (ChatScreen's findSegments, markdownFindSegments, diffFindSegments) and by
 * the code that draws them ([rememberFindable]), so a match found in the list
 * is the one marked on screen. Matches are numbered top to bottom; the
 * search starts at the newest.
 */

/** One match: in which transcript item ([item], a message id), which piece of it, where. */
data class FindHit(val item: String, val segment: String, val start: Int, val length: Int)

/** Searchable text of one transcript item, in the order it is drawn. */
class FindItem(val key: String, val segments: List<Pair<String, String>>)

object ChatFindIndex {

    /**
     * Where [query] is in [text]: case-insensitive, without overlaps, by
     * region rather than on lowercased copies, whose lengths can differ from
     * the text's.
     */
    fun occurrences(text: String, query: String): List<Int> {
        if (query.isEmpty() || text.length < query.length) return emptyList()
        val out = ArrayList<Int>()
        var at = 0
        while (at <= text.length - query.length) {
            if (text.regionMatches(at, query, 0, query.length, ignoreCase = true)) {
                out += at
                at += query.length
            } else {
                at++
            }
        }
        return out
    }

    /** Every match of [query] in [items], top to bottom. */
    fun hits(items: List<FindItem>, query: String): List<FindHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<FindHit>()
        for (item in items) {
            for ((segment, text) in item.segments) {
                occurrences(text, q).forEach { start -> out += FindHit(item.key, segment, start, q.length) }
            }
        }
        return out
    }
}

/**
 * Show [hit]: asked for once each time the current match changes, after the
 * transcript has scrolled its item into view. Answered by the text that
 * holds the match, once — a text drawn again later (the user scrolled away
 * and back) does not pull the transcript back to it.
 */
class FindReveal(val hit: FindHit) {
    internal var done = false
}

/**
 * What the transcript's text reads while a search is on: the words, the
 * current match, and which segments hold a match at all, so the many that
 * do not are passed through untouched and the parts that hold one open.
 */
class FindMarks(val query: String, val current: FindHit?, hits: List<FindHit>, val reveal: FindReveal?) {
    private val withHits: Set<Pair<String, String>> = hits.mapTo(HashSet()) { it.item to it.segment }
    private val itemsWithHits: Map<String, List<String>> = hits.groupBy({ it.item }, { it.segment })

    /** [text] with every match in it marked, the current one more strongly. */
    fun mark(text: AnnotatedString, item: String, segment: String): AnnotatedString {
        if ((item to segment) !in withHits) return text
        val starts = ChatFindIndex.occurrences(text.text, query)
        if (starts.isEmpty()) return text
        val here = current?.takeIf { it.item == item && it.segment == segment }
        return buildAnnotatedString {
            append(text)
            starts.forEach { start ->
                addStyle(if (here?.start == start) CURRENT else MATCH, start, start + query.length)
            }
        }
    }

    /** Whether any segment of [item] whose key starts with [prefix] holds a match: a closed part opens for it. */
    fun opens(item: String?, prefix: String): Boolean =
        item != null && itemsWithHits[item]?.any { it.startsWith(prefix) } == true

    private companion object {
        /** Amber, not the accent: a match must not read as a link or an added line. */
        val MATCH = SpanStyle(background = Design.Colors.Warning.copy(alpha = 0.30f))
        val CURRENT = SpanStyle(background = Design.Colors.Warning, color = Design.Colors.OnPrimary)
    }
}

/**
 * The search while it is on; null otherwise. Not static: it changes with
 * every key and every ↑↓, and only the texts that read it need redrawing.
 */
val LocalFindMarks = compositionLocalOf<FindMarks?> { null }

/** The transcript item being drawn: its message id. Null outside the transcript (sheets, the editor). */
val LocalFindItem = staticCompositionLocalOf<String?> { null }

/** A text as drawn while searching: [text] marked, and what shows the current match when it is here. */
class Findable(
    val text: AnnotatedString,
    /** Goes last on the text's own modifier chain, so match boxes are in its coordinates. */
    val modifier: Modifier,
    val onTextLayout: (TextLayoutResult) -> Unit,
)

/**
 * [text] as segment [segment] of the transcript item being drawn: its
 * matches marked, and — when the match to show ([FindMarks.reveal]) is in
 * it — scrolled into view by whatever scrolls it: the transcript, a card's
 * output box, a code block sideways.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberFindable(segment: String, text: AnnotatedString): Findable {
    val marks = LocalFindMarks.current
    val item = LocalFindItem.current
    if (marks == null || item == null) return remember(text) { Findable(text, Modifier, {}) }

    val marked = remember(text, marks, item, segment) { marks.mark(text, item, segment) }
    val requester = remember { BringIntoViewRequester() }
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val margin = with(LocalDensity.current) { 72.dp.toPx() }
    val reveal = marks.reveal?.takeIf { it.hit.item == item && it.hit.segment == segment }
    LaunchedEffect(reveal) {
        if (reveal == null || reveal.done) return@LaunchedEffect
        // The layout of the marked text: a card or reasoning that opened for
        // the match has laid it out by now.
        val result = snapshotFlow { layout.value?.takeIf { it.layoutInput.text.text == marked.text } }.filterNotNull().first()
        val length = result.layoutInput.text.length
        if (length == 0) return@LaunchedEffect
        val hit = reveal.hit
        val start = hit.start.coerceIn(0, length - 1)
        val end = (hit.start + hit.length - 1).coerceIn(start, length - 1)
        val box = result.getBoundingBox(start).let { first ->
            val last = result.getBoundingBox(end)
            Rect(minOf(first.left, last.left), first.top, maxOf(first.right, last.right), last.bottom)
        }
        // Some lines around it, not the match against the screen's edge.
        val around = Rect(box.left, box.top - margin, box.right, box.bottom + margin)
        reveal.done = true
        requester.bringIntoView(around)
        // A card or reasoning that opened for the match is still growing:
        // once it has, again, or the match can end up under the bar.
        delay(OPENING_MS)
        requester.bringIntoView(around)
    }
    return Findable(marked, Modifier.bringIntoViewRequester(requester)) { layout.value = it }
}

/** Longer than a part takes to open ([dev.apkcli.ui.components.Collapse], the card's AnimatedVisibility). */
private const val OPENING_MS = 350L

/** [rememberFindable] for plain text. */
@Composable
fun rememberFindable(segment: String, text: String): Findable =
    rememberFindable(segment, remember(text) { AnnotatedString(text) })
