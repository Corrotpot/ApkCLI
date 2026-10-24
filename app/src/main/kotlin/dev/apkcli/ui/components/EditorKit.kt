package dev.apkcli.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.theme.Design

/*
 * The older component names, kept so screens can move to the design one at a
 * time. Each is now a thin face over a DesignKit component and draws exactly
 * what the design does; new code uses DesignKit directly.
 */

/** Draw a 1dp line along one edge, without the extra layout a Divider costs. */
fun Modifier.hairline(color: Color, top: Boolean = false): Modifier = drawBehind {
    val y = if (top) 0f else size.height - 1.dp.toPx() / 2
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
}

/* ------------------------------------------------------------- structure */

/** A screen or panel title: the design's top bar with a custom navigation slot. */
@Composable
fun PanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Design.Size.TopBar)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigation?.invoke() ?: Box(Modifier.size(width = 4.dp, height = 1.dp))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = title,
                style = Design.Type.Headline,
                color = Design.Colors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = Design.Type.LabelSmall,
                    color = Design.Colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        actions()
    }
}

/** The design's section overline, inset like a card's content. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 10.dp)
            .heightIn(min = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = Design.Type.Overline,
            color = Design.Colors.TextTertiary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

/** A card, 16 dp in from the screen edges. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Design.Radius.Card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(Design.Colors.Surface1)
            .border(1.dp, if (highlighted) Design.Colors.Accent else Design.Colors.Border, shape),
        content = content,
    )
}

@Composable
fun PanelDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Design.Colors.Divider),
    )
}

/* -------------------------------------------------------------- controls */

enum class ButtonKind { Primary, Secondary, Ghost, Danger }

/** The design's small button (36 dp), in the four kinds. */
@Composable
fun EditorButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Btn(
        text = text,
        onClick = onClick,
        modifier = modifier,
        kind = when (kind) {
            ButtonKind.Primary -> BtnKind.Primary
            ButtonKind.Secondary -> BtnKind.Secondary
            ButtonKind.Ghost -> BtnKind.Ghost
            ButtonKind.Danger -> BtnKind.Danger
        },
        small = true,
        icon = icon,
        enabled = enabled,
        busy = busy,
    )
}

/** An icon button: 48 dp, secondary tint, the press overlay. */
@Composable
fun ToolbarIcon(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Design.Colors.TextSecondary,
    size: Dp = Design.Size.Icon,
) {
    IconBtn(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tint = tint,
        iconSize = size,
    )
}

/** The design's field: label, 48 dp input, helper. */
@Composable
fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    supporting: String? = null,
    isError: Boolean = false,
    mono: Boolean = false,
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Field(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        helper = supporting,
        error = isError,
        mono = mono,
        secret = secret,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardType = keyboardType,
        imeAction = imeAction,
        enabled = enabled,
        trailing = trailing,
        top = 12.dp,
    )
}

/** Mutually exclusive choices: the design's segmented control. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    Segmented(options = options, selected = selected, onSelect = onSelect, label = label, modifier = modifier)
}

/** One choice in a vertical list: a radio row. */
@Composable
fun OptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioRow(title = title, selected = selected, onClick = onClick, subtitle = description, modifier = Modifier.weight(1f))
        if (trailing != null) Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/* -------------------------------------------------------------- feedback */

enum class Tone { Info, Success, Warning, Error, Neutral }

fun Tone.color(): Color = when (this) {
    Tone.Info -> Design.Colors.Accent
    Tone.Success -> Design.Colors.Success
    Tone.Warning -> Design.Colors.Warning
    Tone.Error -> Design.Colors.Error
    Tone.Neutral -> Design.Colors.TextSecondary
}

fun Tone.icon(): ImageVector = when (this) {
    Tone.Info, Tone.Neutral -> Codicons.Info
    Tone.Success -> Codicons.Pass
    Tone.Warning -> Codicons.Warning
    Tone.Error -> Codicons.Error
}

private fun Tone.tile(): TileTone = when (this) {
    Tone.Warning -> TileTone.Warning
    Tone.Error -> TileTone.Error
    Tone.Info, Tone.Success -> TileTone.Accent
    Tone.Neutral -> TileTone.Neutral
}

/**
 * An inline notice: the design's `.notice` card — a tone tile, a title, the
 * text, and actions underneath.
 */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Info,
    title: String? = null,
    onDismiss: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Design.Radius.Card)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(shape)
            .background(Design.Colors.Surface1)
            .border(1.dp, Design.Colors.Border, shape)
            .padding(start = 14.dp, end = if (onDismiss != null) 4.dp else 16.dp, top = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Tile(tone.icon(), tone.tile())
        Column(Modifier.weight(1f)) {
            if (title != null) {
                Text(title, style = Design.Type.Label, color = Design.Colors.Text)
            }
            Text(
                text,
                style = Design.Type.BodySmall,
                color = Design.Colors.TextSecondary,
                modifier = Modifier.padding(top = if (title != null) 4.dp else 0.dp),
            )
            content?.invoke(this)
            if (actions != null) {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
        if (onDismiss != null) {
            IconBtn(Codicons.Close, contentDescription = "Dismiss", onClick = onDismiss, size = IconBtnSize.Composer)
        }
    }
}

/** A small tinted label: the design's badge. */
@Composable
fun StatusBadge(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Badge(
        text = text,
        tone = when (tone) {
            Tone.Warning, Tone.Error -> BadgeTone.Warning
            Tone.Neutral -> BadgeTone.Neutral
            else -> BadgeTone.Accent
        },
        modifier = modifier,
    )
}

/** Label on the left, value on the right; values are mono by default. */
@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier, mono: Boolean = true) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(key, style = Design.Type.BodySmall, color = Design.Colors.TextSecondary)
        Text(
            value,
            style = if (mono) Design.Type.MonoCaption else Design.Type.BodySmall,
            color = Design.Colors.Text,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A leading icon and a line of text, for one-off status rows. */
@Composable
fun IconLine(icon: ImageVector, text: String, tint: Color = Design.Colors.TextSecondary) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Design.Size.IconSmall))
        Text(text, style = Design.Type.BodySmall, color = tint)
    }
}
