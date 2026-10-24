package dev.apkcli.ui.workbench

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.apkcli.ui.theme.LocalEditorColors
import kotlin.math.roundToInt

/** Which children of a [SplitPane] are on screen. */
enum class SplitVisibility { BOTH, FIRST, SECOND }

/**
 * Two panes with a draggable divider between them.
 *
 * Both children stay in the composition whatever [visibility] says. A hidden
 * pane is laid out at zero size instead of being removed, so turning the split
 * on and off does not throw away the chat's scroll position or tear down and
 * rebuild the editor's native view — which is what happens to anything that
 * leaves the composition.
 *
 * The divider is an 8dp gutter with a grip, and a 28dp touch target that
 * overhangs both panes slightly. Dragging is live and committed on release
 * through [onCommit]; releasing with a pane squeezed well below its minimum
 * collapses it through [onCollapse] instead, which is how a pane is closed by
 * dragging it shut. A double tap resets. Dragging is never the only way: the
 * divider carries accessibility actions, and the layout menu has the same
 * operations as buttons.
 *
 * The drag position is read only in the layout and draw phases, so a drag
 * re-measures the two panes but never recomposes them.
 */
@Composable
fun SplitPane(
    vertical: Boolean,
    visibility: SplitVisibility,
    firstFraction: Float,
    minFirst: Dp,
    minSecond: Dp,
    onCommit: (firstFraction: Float) -> Unit,
    onCollapse: (keepFirst: Boolean) -> Unit,
    onReset: () -> Unit,
    dividerDescription: String,
    modifier: Modifier = Modifier,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val colors = LocalEditorColors.current
    val scrim = colors.chrome.copy(alpha = 0.72f)
    val gripIdle = colors.borderStrong
    val gripActive = colors.accentButton
    val gutterColor = colors.chrome

    // Live fraction while a drag is in progress; null otherwise.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // Written by the measure pass, read by the drag handler and the draw
    // lambdas: plain fields, so measuring never schedules another measure.
    val geometry = remember { SplitGeometry() }

    val committed by rememberUpdatedState(firstFraction)
    val commit by rememberUpdatedState(onCommit)
    val collapse by rememberUpdatedState(onCollapse)
    val reset by rememberUpdatedState(onReset)

    fun zone(): CollapseZone = dragFraction?.let(geometry::zoneFor) ?: CollapseZone.NONE

    val dragModifier = Modifier.pointerInput(vertical) {
        val onStart: (Offset) -> Unit = { dragFraction = geometry.clampToMinimums(committed) }
        val onEnd: () -> Unit = {
            val final = dragFraction
            dragFraction = null
            if (final != null) {
                when (geometry.zoneFor(final)) {
                    CollapseZone.FIRST -> collapse(false)
                    CollapseZone.SECOND -> collapse(true)
                    CollapseZone.NONE -> commit(geometry.clampToMinimums(final))
                }
            }
        }
        val onCancel: () -> Unit = { dragFraction = null }
        val onDelta: (Float) -> Unit = { delta ->
            val available = geometry.availablePx
            if (available > 0) {
                val current = dragFraction ?: committed
                dragFraction = (current + delta / available).coerceIn(0.04f, 0.96f)
            }
        }
        if (vertical) {
            detectVerticalDragGestures(
                onDragStart = onStart,
                onDragEnd = onEnd,
                onDragCancel = onCancel,
                onVerticalDrag = { change, delta ->
                    change.consume()
                    onDelta(delta)
                },
            )
        } else {
            detectHorizontalDragGestures(
                onDragStart = onStart,
                onDragEnd = onEnd,
                onDragCancel = onCancel,
                onHorizontalDrag = { change, delta ->
                    change.consume()
                    onDelta(delta)
                },
            )
        }
    }

    val firstContent: @Composable () -> Unit = {
        Box(
            Modifier.drawWithContent {
                drawContent()
                if (zone() == CollapseZone.FIRST) drawRect(scrim)
            },
        ) { first() }
    }

    val secondContent: @Composable () -> Unit = {
        Box(
            Modifier.drawWithContent {
                drawContent()
                if (zone() == CollapseZone.SECOND) drawRect(scrim)
            },
        ) { second() }
    }

    val dividerContent: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxSize()
                .then(dragModifier)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
                .semantics {
                    contentDescription = dividerDescription
                    if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
                }
                .drawBehind {
                    // The gutter, centred in the wider touch target, then the grip.
                    val gutter = GUTTER.toPx()
                    val gripLong = 36.dp.toPx()
                    val gripShort = 3.dp.toPx()
                    val grip = if (dragFraction != null) gripActive else gripIdle
                    if (vertical) {
                        drawRect(gutterColor, Offset(0f, (size.height - gutter) / 2), Size(size.width, gutter))
                        drawRoundRect(
                            color = grip,
                            topLeft = Offset((size.width - gripLong) / 2, (size.height - gripShort) / 2),
                            size = Size(gripLong, gripShort),
                            cornerRadius = CornerRadius(gripShort / 2),
                        )
                    } else {
                        drawRect(gutterColor, Offset((size.width - gutter) / 2, 0f), Size(gutter, size.height))
                        drawRoundRect(
                            color = grip,
                            topLeft = Offset((size.width - gripShort) / 2, (size.height - gripLong) / 2),
                            size = Size(gripShort, gripLong),
                            cornerRadius = CornerRadius(gripShort / 2),
                        )
                    }
                },
        )
    }

    Layout(
        contents = listOf(firstContent, dividerContent, secondContent),
        modifier = modifier,
    ) { (firstMeasurables, dividerMeasurables, secondMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val zero = Constraints.fixed(0, 0)

        fun along(main: Int): Constraints = main.coerceAtLeast(0).let {
            if (vertical) Constraints.fixed(width, it) else Constraints.fixed(it, height)
        }

        when (visibility) {
            SplitVisibility.FIRST, SplitVisibility.SECOND -> {
                val showFirst = visibility == SplitVisibility.FIRST
                val full = Constraints.fixed(width, height)
                val firstPlaceables = firstMeasurables.map { it.measure(if (showFirst) full else zero) }
                val secondPlaceables = secondMeasurables.map { it.measure(if (showFirst) zero else full) }
                geometry.availablePx = 0
                layout(width, height) {
                    firstPlaceables.forEach { it.place(0, 0) }
                    secondPlaceables.forEach { it.place(0, 0) }
                }
            }

            SplitVisibility.BOTH -> {
                val total = if (vertical) height else width
                val gutter = GUTTER.roundToPx()
                val available = (total - gutter).coerceAtLeast(0)
                geometry.availablePx = available
                geometry.minFirstPx = minFirst.roundToPx()
                geometry.minSecondPx = minSecond.roundToPx()

                val fraction = dragFraction ?: geometry.clampToMinimums(firstFraction)
                val firstSize = (available * fraction).roundToInt().coerceIn(0, available)
                val secondSize = available - firstSize
                val hit = HIT.roundToPx().coerceAtLeast(gutter)

                val firstPlaceables = firstMeasurables.map { it.measure(along(firstSize)) }
                val secondPlaceables = secondMeasurables.map { it.measure(along(secondSize)) }
                val dividerPlaceables = dividerMeasurables.map { it.measure(along(hit)) }
                val dividerOffset = firstSize + gutter / 2 - hit / 2

                layout(width, height) {
                    firstPlaceables.forEach { it.place(0, 0) }
                    secondPlaceables.forEach {
                        if (vertical) it.place(0, firstSize + gutter) else it.place(firstSize + gutter, 0)
                    }
                    // Last, so its overhang sits above both panes.
                    dividerPlaceables.forEach {
                        if (vertical) it.place(0, dividerOffset) else it.place(dividerOffset, 0)
                    }
                }
            }
        }
    }
}

private enum class CollapseZone { NONE, FIRST, SECOND }

private class SplitGeometry {
    var availablePx = 0
    var minFirstPx = 0
    var minSecondPx = 0

    /** Squeezed below 60% of its minimum: released there, the pane closes. */
    fun zoneFor(fraction: Float): CollapseZone {
        if (availablePx <= 0) return CollapseZone.NONE
        val firstPx = availablePx * fraction
        val secondPx = availablePx - firstPx
        return when {
            firstPx < minFirstPx * 0.6f -> CollapseZone.FIRST
            secondPx < minSecondPx * 0.6f -> CollapseZone.SECOND
            else -> CollapseZone.NONE
        }
    }

    /** Keep both panes at or above their minimum, when there is room for that at all. */
    fun clampToMinimums(fraction: Float): Float {
        if (availablePx <= 0 || minFirstPx + minSecondPx > availablePx) return fraction.coerceIn(0.1f, 0.9f)
        val low = minFirstPx.toFloat() / availablePx
        val high = 1f - minSecondPx.toFloat() / availablePx
        return fraction.coerceIn(low, high)
    }
}

private val GUTTER = 8.dp
private val HIT = 28.dp
