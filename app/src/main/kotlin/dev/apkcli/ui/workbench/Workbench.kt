package dev.apkcli.ui.workbench

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import dev.apkcli.R

/**
 * The agent and the other side — the files or the terminals — one or both on
 * screen.
 *
 * Both panes are hosted as movable content inside one [SplitPane], so every
 * change of arrangement — split on or off, swapped, rotated from side by side
 * to stacked — keeps the editor's native view, its undo history and the
 * chat's scroll position.
 *
 * When stacked and the keyboard is up, the pane being typed into takes the
 * whole screen for as long as the keyboard stays. The keyboard covers the
 * bottom of the layout without shrinking it, so the lower pane's visible part
 * is its height *minus* the keyboard — on a phone that left the chat a header
 * and nothing else, with the composer pushed below the keyboard. The stored
 * split is untouched and comes back when the keyboard goes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Workbench(
    layout: WorkbenchLayout,
    /** Resolved by the caller, which also needs it for the bottom bar. */
    resolved: WorkbenchLayout.Resolved,
    onUpdate: ((WorkbenchLayout) -> WorkbenchLayout) -> Unit,
    onReset: () -> Unit,
    files: @Composable () -> Unit,
    chat: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentFiles by rememberUpdatedState(files)
    val currentChat by rememberUpdatedState(chat)
    val filesContent = remember { movableContentOf { currentFiles() } }
    val chatContent = remember { movableContentOf { currentChat() } }

    // The pane last touched: decides which one the keyboard boost favours.
    var activePane by rememberSaveable { mutableStateOf(layout.focus) }

    Box(modifier.fillMaxSize()) {
        val sideBySide = resolved.sideBySide
        val split = resolved.split

        val chatFirst = !layout.chatLast
        val storedChat = layout.chatFraction(sideBySide)
        val typingStacked = split && !sideBySide && WindowInsets.isImeVisible

        val visibility = when {
            typingStacked -> if ((activePane == Pane.CHAT) == chatFirst) SplitVisibility.FIRST else SplitVisibility.SECOND
            split -> SplitVisibility.BOTH
            (layout.focus == Pane.CHAT) == chatFirst -> SplitVisibility.FIRST
            else -> SplitVisibility.SECOND
        }

        val wider = stringResource(R.string.wb_wider_chat)
        val narrower = stringResource(R.string.wb_narrower_chat)
        val resetLabel = stringResource(R.string.wb_reset_split)
        val actions = remember(sideBySide, storedChat, wider, narrower, resetLabel) {
            listOf(
                CustomAccessibilityAction(wider) {
                    onUpdate { it.withChatFraction(sideBySide, storedChat + WorkbenchLayout.STEP) }
                    true
                },
                CustomAccessibilityAction(narrower) {
                    onUpdate { it.withChatFraction(sideBySide, storedChat - WorkbenchLayout.STEP) }
                    true
                },
                CustomAccessibilityAction(resetLabel) {
                    onUpdate { it.withChatFraction(sideBySide, defaultChat(sideBySide)) }
                    true
                },
            )
        }

        val minAlong = if (sideBySide) WorkbenchLayout.MIN_PANE_WIDTH else WorkbenchLayout.MIN_PANE_HEIGHT

        SplitPane(
            vertical = !sideBySide,
            visibility = visibility,
            firstFraction = if (chatFirst) storedChat else 1f - storedChat,
            minFirst = minAlong,
            minSecond = minAlong,
            onCommit = { firstFraction ->
                val chatFraction = if (chatFirst) firstFraction else 1f - firstFraction
                onUpdate { it.withChatFraction(sideBySide, chatFraction).copy(split = true) }
            },
            onCollapse = { keepFirst ->
                val keep = if (keepFirst == chatFirst) Pane.CHAT else layout.side
                onUpdate { it.copy(split = false, focus = keep) }
            },
            onReset = { onUpdate { it.withChatFraction(sideBySide, defaultChat(sideBySide)) } },
            dividerDescription = stringResource(R.string.wb_divider),
            accessibilityActions = actions,
            first = {
                val pane = if (chatFirst) Pane.CHAT else layout.side
                PaneHost(onTouched = { activePane = pane }) {
                    if (chatFirst) chatContent() else filesContent()
                }
            },
            second = {
                val pane = if (chatFirst) layout.side else Pane.CHAT
                PaneHost(onTouched = { activePane = pane }) {
                    if (chatFirst) filesContent() else chatContent()
                }
            },
        )
    }
}

private fun defaultChat(sideBySide: Boolean) =
    if (sideBySide) WorkbenchLayout.DEFAULT_SIDE_CHAT else WorkbenchLayout.DEFAULT_STACK_CHAT

/** Reports the first touch of every gesture inside it, without consuming anything. */
@Composable
private fun PaneHost(onTouched: () -> Unit, content: @Composable () -> Unit) {
    val touched by rememberUpdatedState(onTouched)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touched()
                }
            },
    ) { content() }
}
