package dev.apkcli.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * A scroll state for a box that output streams into: it keeps the newest
 * line in view while [following] — a running command's terminal, reasoning
 * as it arrives — until the user drags it. Then it stays where they put it,
 * and follows again once they bring it back to the bottom.
 *
 * Only a drag decides. Content growing between frames leaves the box short
 * of its end for a moment, and reading that as "the user left" stopped the
 * following on its own.
 */
@Composable
fun rememberFollowingScrollState(
    following: Boolean = true,
    /** Held by the caller when the position must outlive this composable. */
    scroll: ScrollState = rememberScrollState(),
): ScrollState {
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    var follow by remember(scroll) { mutableStateOf(true) }
    val active by rememberUpdatedState(following)

    LaunchedEffect(scroll) {
        var touched = false
        snapshotFlow { Triple(dragged, scroll.isScrollInProgress, scroll.value >= scroll.maxValue - SLACK_PX) }
            .collect { (dragging, scrolling, atBottom) ->
                if (dragging) touched = true
                if (touched) follow = atBottom
                if (!dragging && !scrolling) touched = false
            }
    }
    LaunchedEffect(scroll) {
        // After layout, when new content has made it taller. Before the first
        // layout the maximum is unknown and reads as Int.MAX_VALUE.
        snapshotFlow { scroll.maxValue }.collect { max ->
            if (max == Int.MAX_VALUE || !active || !follow || scroll.isScrollInProgress) return@collect
            try {
                scroll.scrollTo(max)
            } catch (interrupted: CancellationException) {
                // The user started a scroll of their own: theirs wins.
                if (!currentCoroutineContext().isActive) throw interrupted
            }
        }
    }
    return scroll
}

/** How far from the end still counts as at it. */
private const val SLACK_PX = 24
