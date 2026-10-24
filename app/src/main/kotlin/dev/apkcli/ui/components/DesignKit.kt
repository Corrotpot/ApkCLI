package dev.apkcli.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.apkcli.ui.icons.Codicons
import dev.apkcli.ui.theme.Design
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The design's components: one composable per class in
 * design/prototype/app.css, drawn only with [Design] tokens.
 *
 * Shared conventions, the same as the prototype's:
 *  * A press draws the `pressed` overlay, not a ripple ([PressOverlay]).
 *  * Borders are inside the box, as Modifier.border draws them and as CSS
 *    border-box does.
 *  * Text comes only from [Design.Type]; colours only from [Design.Colors].
 *
 * Where a size or gap below is a bare number, it is the number in app.css.
 */

/* ============================================================ pressing */

/**
 * The pressed state: a translucent white overlay faded in over 90 ms, linear.
 *
 * Provided as LocalIndication by the theme, so every `clickable` in the app
 * gets it without asking — the prototype's `:active` rule, everywhere.
 */
object PressOverlay : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressOverlayNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x50ce55
}

private class PressOverlayNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val level = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var held = 0
            var running: Job? = null
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> held++
                    is PressInteraction.Release, is PressInteraction.Cancel -> held = (held - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                val target = if (held > 0) 1f else 0f
                running?.cancel()
                running = launch {
                    level.animateTo(target, tween(Design.Motion.Press, easing = LinearEasing)) { invalidateDraw() }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = level.value
        if (a > 0f) drawRect(Design.Colors.Pressed.copy(alpha = Design.Colors.Pressed.alpha * a))
    }
}

/** Clickable with the design's press overlay, clipped to [shape] when given. */
fun Modifier.pressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role = Role.Button,
    shape: androidx.compose.ui.graphics.Shape? = null,
): Modifier = (if (shape != null) clip(shape) else this)
    .clickable(enabled = enabled, role = role, onClick = onClick)

/* ============================================================ top bar */

/**
 * `.topbar`: 56 dp, back arrow, title over an optional tappable subtitle with a
 * chevron, then icon actions. No background of its own and no hairline: it
 * sits on the page's black.
 */
@Composable
fun TopBar(
    modifier: Modifier = Modifier,
    title: String = "",
    subtitle: String? = null,
    onSubtitleClick: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    backDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().height(Design.Size.TopBar).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconBtn(Codicons.ArrowLeft, contentDescription = backDescription, onClick = onBack)
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (title.isNotEmpty()) {
                Text(
                    text = title,
                    style = Design.Type.Headline,
                    color = Design.Colors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!subtitle.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .then(
                            if (onSubtitleClick != null) Modifier.clickable(role = Role.Button, onClick = onSubtitleClick)
                            else Modifier,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Never cut: in the workspace this is the agent's mode,
                    // and the title above gives way instead. The workspace
                    // keeps it room by moving an action into its menu when
                    // the bar is too narrow (WorkspaceScreen).
                    Text(
                        text = subtitle,
                        style = Design.Type.LabelSmall,
                        color = Design.Colors.TextSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (onSubtitleClick != null) {
                        Icon(
                            Codicons.ChevronDown,
                            contentDescription = null,
                            tint = Design.Colors.TextSecondary,
                            modifier = Modifier.size(Design.Size.IconTiny),
                        )
                    }
                }
            }
        }
        actions()
    }
}

/* ======================================================== icon button */

enum class IconBtnSize(val box: Dp) {
    Large(Design.Size.IconButton),
    Small(Design.Size.IconButtonSmall),
    Composer(Design.Size.IconButtonComposer),
}

/** `.ib`: a round icon button, secondary tint, the press overlay on a circle. */
@Composable
fun IconBtn(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: IconBtnSize = IconBtnSize.Large,
    iconSize: Dp = if (size == IconBtnSize.Large) Design.Size.Icon else Design.Size.IconSmall,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color = if (selected) Design.Colors.Text else Design.Colors.TextSecondary,
    badge: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size.box)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else Design.Colors.TextDisabled,
            modifier = Modifier.size(iconSize),
        )
        if (badge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-10).dp, y = 10.dp)
                    .size(7.dp)
                    .background(Design.Colors.Accent, CircleShape),
            )
        }
    }
}

/* ======================================================= cards and rows */

private val CardShape = RoundedCornerShape(Design.Radius.Card)

/**
 * `.card`: surface 1, a 1 dp border, 14 dp corners. Rows inside are separated
 * by [CardDivider], inset past the row's leading tile as the prototype draws it.
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Design.Colors.Surface1)
            .border(1.dp, Design.Colors.Border, CardShape),
        content = content,
    )
}

/** The 1 dp line between rows in a card. [inset] is 68 after a tile row, 14 in a flat card. */
@Composable
fun CardDivider(inset: Dp = 68.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(Design.Colors.Divider),
    )
}

/**
 * `.row`: leading tile or avatar, title over subtitle, trailing slot (a chevron
 * by default). 64 dp tall, 52 when [compact].
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    lead: (@Composable () -> Unit)? = null,
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = { Chevron() },
    compact: Boolean = false,
    subtitleMaxLines: Int = 1,
    titleStyle: TextStyle = Design.Type.Label,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) Design.Size.RowCompact else Design.Size.Row)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        lead?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = Design.Colors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                titleTrailing?.invoke(this)
            }
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = Design.Type.BodySmall,
                    color = Design.Colors.TextSecondary,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = trailing,
            )
        }
    }
}

@Composable
fun Chevron() {
    Icon(
        Codicons.ChevronRight,
        contentDescription = null,
        tint = Design.Colors.TextTertiary,
        modifier = Modifier.size(Design.Size.IconSmall),
    )
}

enum class TileTone { Neutral, Accent, Warning, Error }

/** `.tile`: 40 dp, 10 dp corners, a 20 dp icon; tinted tones drop the border. */
@Composable
fun Tile(icon: ImageVector, tone: TileTone = TileTone.Neutral, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Design.Radius.Tile)
    val (background, tint) = when (tone) {
        TileTone.Neutral -> Design.Colors.Surface2 to Design.Colors.Text
        TileTone.Accent -> Design.Colors.AccentMuted to Design.Colors.Accent
        TileTone.Warning -> Design.Colors.WarningMuted to Design.Colors.Warning
        TileTone.Error -> Design.Colors.ErrorMuted to Design.Colors.Error
    }
    Box(
        modifier = modifier
            .size(Design.Size.Tile)
            .clip(shape)
            .background(background)
            .then(if (tone == TileTone.Neutral) Modifier.border(1.dp, Design.Colors.BorderStrong, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Design.Size.Icon))
    }
}

/** `.avatar`: a project's first letter or digit in the hue its name hashes to. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier) {
    val hue = Design.Avatar.hueFor(name)
    val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = modifier
            .size(Design.Size.Tile)
            .clip(RoundedCornerShape(Design.Radius.Tile))
            .background(hue.copy(alpha = Design.Avatar.BackgroundAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = Design.Type.Avatar, color = hue)
    }
}

/**
 * `.section`: an uppercase overline above a group, 28 dp above and 10 below,
 * with an optional accent link on the right.
 */
@Composable
fun Section(
    text: String,
    modifier: Modifier = Modifier,
    linkText: String? = null,
    onLink: (() -> Unit)? = null,
    top: Dp = 28.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = top, bottom = 10.dp)
            .heightIn(min = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = Design.Type.Overline,
            color = Design.Colors.TextTertiary,
            modifier = Modifier.weight(1f),
        )
        if (linkText != null && onLink != null) {
            Text(
                text = linkText,
                style = Design.Type.LabelSmall,
                color = Design.Colors.Accent,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onLink),
            )
        }
    }
}

enum class BadgeTone { Accent, Neutral, Warning }

/** `.badge`: 22 dp pill with a tinted background. */
@Composable
fun Badge(text: String, tone: BadgeTone = BadgeTone.Accent, modifier: Modifier = Modifier) {
    val (background, color) = when (tone) {
        BadgeTone.Accent -> Design.Colors.AccentMuted to Design.Colors.Accent
        BadgeTone.Neutral -> Design.Colors.Surface3 to Design.Colors.TextSecondary
        BadgeTone.Warning -> Design.Colors.WarningMuted to Design.Colors.Warning
    }
    Box(
        modifier = modifier
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(background)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Design.Type.LabelTiny, color = color, maxLines = 1)
    }
}

/** `.dot`, optionally `.pulse`: 6 dp, fading between full and 30 % every 1.2 s. */
@Composable
fun Dot(color: Color = Design.Colors.Accent, pulse: Boolean = false, modifier: Modifier = Modifier) {
    val level = if (pulse) {
        val transition = rememberInfiniteTransition(label = "dot")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
            label = "dot-alpha",
        ).value
    } else {
        1f
    }
    Box(modifier.size(6.dp).alpha(level).background(color, CircleShape))
}

/* ============================================================ buttons */

enum class BtnKind { Primary, Secondary, Danger, Ghost }

/**
 * `.btn`: 48 dp (36 when [small]), 12 dp corners (10 small), label text.
 *
 * Primary is the white button with black text; its pressed state is a darker
 * grey rather than the overlay, as in the prototype.
 */
@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Secondary,
    small: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ghost = kind == BtnKind.Ghost
    val height = if (small || ghost) Design.Size.ButtonSmall else Design.Size.Button
    val shape = RoundedCornerShape(if (small || ghost) Design.Radius.Tile else Design.Radius.Button)
    val live = enabled && !busy

    val (background, content) = when {
        !enabled && !ghost -> Design.Colors.Surface3 to Design.Colors.TextDisabled
        !enabled -> Color.Transparent to Design.Colors.TextDisabled
        kind == BtnKind.Primary -> (if (pressed) Design.Colors.PrimaryPressed else Design.Colors.Primary) to Design.Colors.OnPrimary
        kind == BtnKind.Secondary -> (if (pressed) Design.Colors.Surface3 else Design.Colors.Surface2) to Design.Colors.Text
        kind == BtnKind.Danger -> Design.Colors.ErrorMuted to Design.Colors.Error
        else -> Color.Transparent to Design.Colors.Accent
    }
    val overlay = kind == BtnKind.Danger || ghost

    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(background)
            .then(
                if (kind == BtnKind.Secondary && enabled) Modifier.border(1.dp, Design.Colors.BorderStrong, shape)
                else Modifier,
            )
            .clickable(
                interactionSource = interaction,
                indication = if (overlay) PressOverlay else null,
                enabled = live,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (ghost) 10.dp else if (small) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        when {
            busy -> Spinner(color = content)
            icon != null -> Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(Design.Size.IconSmall))
        }
        Text(
            text = text,
            style = if (small || ghost) Design.Type.LabelSmall else Design.Type.Label,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ============================================================= fields */

/**
 * `.field`: a label, the `.input` box and a helper line.
 *
 * The box is 48 dp, surface 2, a strong border that turns accent on focus and
 * red on error. [leading] and [trailing] go inside it, as the prototype's key
 * icon and eye button do.
 */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    helper: String? = null,
    error: Boolean = false,
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    secret: Boolean = false,
    mono: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    enabled: Boolean = true,
    autoCorrect: Boolean = !(mono || secret),
    top: Dp = 20.dp,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Design.Radius.Input)
    val textStyle = if (mono) Design.Type.Code else Design.Type.Body

    Column(modifier.fillMaxWidth().padding(top = top), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) {
            Text(label, style = Design.Type.LabelSmall, color = Design.Colors.TextSecondary)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Design.Size.Input)
                .clip(shape)
                .background(Design.Colors.Surface2)
                .border(
                    1.dp,
                    when {
                        error -> Design.Colors.Error
                        focused -> Design.Colors.Accent
                        else -> Design.Colors.BorderStrong
                    },
                    shape,
                )
                .padding(start = 14.dp, end = if (trailing != null) 4.dp else 14.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (leading != null) {
                Icon(
                    leading,
                    contentDescription = null,
                    tint = Design.Colors.TextTertiary,
                    modifier = Modifier.padding(top = if (singleLine) 0.dp else 15.dp).size(Design.Size.IconSmall),
                )
            }
            Box(Modifier.weight(1f).padding(vertical = if (singleLine) 13.dp else 12.dp)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        placeholder,
                        style = textStyle,
                        color = Design.Colors.TextTertiary,
                        maxLines = if (singleLine) 1 else maxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    interactionSource = interaction,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        // A secret is always KeyboardType.Password: it also stops
                        // the IME learning it, and an API key in the keyboard's
                        // prediction dictionary is a real leak.
                        keyboardType = if (secret) KeyboardType.Password else keyboardType,
                        imeAction = imeAction,
                        autoCorrectEnabled = autoCorrect,
                    ),
                    keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
                    textStyle = textStyle.copy(color = Design.Colors.Text),
                    cursorBrush = SolidColor(Design.Colors.Accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                )
            }
            trailing?.invoke()
        }
        if (helper != null) {
            Text(
                helper,
                style = Design.Type.Caption,
                color = if (error) Design.Colors.Error else Design.Colors.TextTertiary,
                modifier = Modifier.heightIn(min = 16.dp),
            )
        }
    }
}

/** `.search`: 44 dp, a magnifier, a quieter border that steps up on focus, and a clear button. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    clearDescription: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Design.Radius.Input)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(Design.Colors.Surface2)
            .border(1.dp, if (focused) Design.Colors.BorderFocus else Design.Colors.Border, shape)
            .padding(start = 12.dp, end = if (value.isNotEmpty()) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Codicons.Search, contentDescription = null, tint = Design.Colors.TextTertiary, modifier = Modifier.size(Design.Size.IconSmall))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = Design.Type.Body, color = Design.Colors.TextTertiary, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                textStyle = Design.Type.Body.copy(color = Design.Colors.Text),
                cursorBrush = SolidColor(Design.Colors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconBtn(Codicons.Close, contentDescription = clearDescription, onClick = { onValueChange("") }, size = IconBtnSize.Composer)
        }
    }
}

/**
 * `.seg`: 36 dp, surface 2, the selected segment raised to surface 3 with an
 * inset border. [disabled] options are drawn and not selectable.
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    disabled: Set<T> = emptySet(),
) {
    val outer = RoundedCornerShape(Design.Radius.Segment)
    val inner = RoundedCornerShape(Design.Radius.SegmentInner)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(outer)
            .background(Design.Colors.Surface2)
            .border(1.dp, Design.Colors.Border, outer)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            val off = option in disabled
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(inner)
                    .then(if (on) Modifier.background(Design.Colors.Surface3).border(1.dp, Design.Colors.BorderStrong, inner) else Modifier)
                    .selectable(selected = on, enabled = !off, role = Role.RadioButton) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = Design.Type.LabelSmall,
                    color = when {
                        off -> Design.Colors.TextDisabled
                        on -> Design.Colors.Text
                        else -> Design.Colors.TextSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** `.radio`: 20 dp ring; selected, an accent ring around a 10 dp dot. */
@Composable
fun Radio(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(20.dp)
            .border(2.dp, if (selected) Design.Colors.Accent else Design.Colors.TextTertiary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).background(Design.Colors.Accent, CircleShape))
    }
}

/** `.checkbox`: 20 dp, 6 dp corners; checked, filled with the accent. */
@Composable
fun CheckMark(checked: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(shape)
            .then(
                if (checked) Modifier.background(Design.Colors.Accent)
                else Modifier.border(2.dp, Design.Colors.TextTertiary, shape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Codicons.Check, contentDescription = null, tint = Design.Colors.OnAccent, modifier = Modifier.size(14.dp))
    }
}

/** A row with a radio mark: a choice among several, in a sheet's card. */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Design.Size.Row)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Radio(selected)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Design.Type.Label, color = Design.Colors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = Design.Type.BodySmall, color = Design.Colors.TextSecondary)
            }
        }
    }
}

/**
 * A row with a check mark: one of several things that can each be on, in a
 * card. The whole row toggles, and says so to TalkBack as a checkbox.
 */
@Composable
fun CheckRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Design.Size.Row)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CheckMark(checked)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Design.Type.Label, color = Design.Colors.Text)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = Design.Type.BodySmall, color = Design.Colors.TextSecondary)
            }
        }
    }
}

/* ============================================================ notes, empty */

/** `.note`: an info icon and tertiary text, 16 dp below what it annotates. */
@Composable
fun Note(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Codicons.Info,
    top: Dp = 16.dp,
    color: Color = Design.Colors.TextTertiary,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = top),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(Design.Size.IconSmall))
        Text(text, style = Design.Type.BodySmall, color = color, modifier = Modifier.weight(1f))
    }
}

/** `.empty`: centred tertiary text inside a card. */
@Composable
fun EmptyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = Design.Type.BodySmall,
        color = Design.Colors.TextTertiary,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
    )
}

/* ========================================================== progress */

/** `.spin`: a 14 dp ring with one quarter open, turning every 800 ms. */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    color: Color = Design.Colors.Accent,
    size: Dp = Design.Size.Spinner,
) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(Design.Motion.Spinner, easing = LinearEasing)),
        label = "spin-angle",
    )
    Box(
        modifier
            .size(size)
            .rotate(angle)
            .drawBehind {
                val stroke = 2.dp.toPx()
                drawArc(
                    color = color,
                    startAngle = 0f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    style = Stroke(stroke),
                )
            },
    )
}

/** `.progress`: a 4 dp track; indeterminate, a 40 % bar sweeping across; [done], full. */
@Composable
fun ProgressLine(done: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "progress")
    val phase by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(1400, easing = Design.Motion.Standard)),
        label = "progress-phase",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Design.Colors.Surface3)
            .drawBehind {
                val radius = CornerRadius(2.dp.toPx())
                if (done) {
                    drawRoundRect(Design.Colors.Accent, cornerRadius = radius)
                } else {
                    val bar = size.width * 0.4f
                    drawRoundRect(
                        Design.Colors.Accent,
                        topLeft = Offset(bar * phase, 0f),
                        size = Size(bar, size.height),
                        cornerRadius = radius,
                    )
                }
            },
    )
}

/** Expand and collapse vertically over 200 ms, the prototype's `.collapse`. */
@Composable
fun Collapse(open: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(tween(Design.Motion.Expand, easing = Design.Motion.Standard)),
        exit = shrinkVertically(tween(Design.Motion.Expand, easing = Design.Motion.Standard)),
    ) {
        Column(content = content)
    }
}

/* ============================================================== sheets */

private val SheetShape = RoundedCornerShape(topStart = Design.Radius.Sheet, topEnd = Design.Radius.Sheet)

/**
 * `.sheet`: a bottom sheet on surface 1 with a strong top edge, 20 dp corners
 * and the 36×4 handle, over the black scrim.
 *
 * Material's ModalBottomSheet underneath, for what it gets right — its own
 * window above the keyboard, back to dismiss, drag to close — restyled to the
 * design and opened straight to its full height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // `.sheet { max-height: calc(100% - 40px) }`: a long sheet stops short
    // of the status bar, with the page showing above it, rather than run
    // under the clock.
    val statusBar = WindowInsets.statusBars
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = SheetShape,
        containerColor = Design.Colors.Surface1,
        contentColor = Design.Colors.Text,
        tonalElevation = 0.dp,
        scrimColor = Design.Colors.Scrim,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
        modifier = modifier,
    ) {
        Column(
            Modifier
                // On the content, not on the sheet: Material moves the sheet
                // to where it rests inside its own modifier, and an edge drawn
                // outside that move stayed at the top of the screen.
                .topEdge(Design.Colors.BorderStrong, Design.Radius.Sheet)
                .layout { measurable, constraints ->
                    val capped = if (constraints.hasBoundedHeight) {
                        val room = constraints.maxHeight - statusBar.getTop(this) - SheetTopGap.roundToPx()
                        constraints.copy(maxHeight = room.coerceIn(constraints.minHeight, constraints.maxHeight))
                    } else {
                        constraints
                    }
                    val placeable = measurable.measure(capped)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 14.dp)
                    .size(Design.Size.SheetHandleWidth, Design.Size.SheetHandleHeight)
                    .background(Design.Colors.Handle, RoundedCornerShape(2.dp)),
            )
            content()
        }
    }
}

/** Between the status bar and the top of a sheet as tall as it gets. */
private val SheetTopGap = 40.dp

/** A 1 dp line along the top edge only, following the corner radius. */
private fun Modifier.topEdge(color: Color, radius: Dp): Modifier = drawWithContent {
    drawContent()
    val stroke = 1.dp.toPx()
    val r = radius.toPx()
    val half = stroke / 2
    val path = Path().apply {
        moveTo(half, r)
        arcTo(Rect(half, half, half + 2 * r, half + 2 * r), 180f, 90f, false)
        lineTo(size.width - r, half)
        arcTo(Rect(size.width - half - 2 * r, half, size.width - half, half + 2 * r), 270f, 90f, false)
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Butt))
}

/** `.sheet-head`: an optional tile, the title and a secondary line. */
@Composable
fun SheetHead(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tone: TileTone = TileTone.Neutral,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (icon != null) Tile(icon, tone)
        Column(Modifier.weight(1f)) {
            Text(title, style = Design.Type.Headline, color = Design.Colors.Text)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = Design.Type.BodySmall,
                    color = Design.Colors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** `.sheet-body`: the part of a sheet that scrolls, between the head and the actions. */
@Composable
fun ColumnScope.SheetBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}

/** `.sheet-actions`: buttons sharing the width, 10 dp apart, 20 dp below the body. */
@Composable
fun SheetActions(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A yes/no question as a sheet: what happens, and the two answers. */
@Composable
fun ConfirmSheet(
    title: String,
    body: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector = Codicons.Warning,
    destructive: Boolean = true,
) {
    Sheet(onDismiss = onDismiss) {
        SheetHead(title = title, subtitle = body, icon = icon, tone = if (destructive) TileTone.Error else TileTone.Neutral)
        SheetActions {
            Btn(dismiss, onClick = onDismiss, kind = BtnKind.Secondary, modifier = Modifier.weight(1f))
            Btn(
                confirm,
                onClick = onConfirm,
                kind = if (destructive) BtnKind.Danger else BtnKind.Primary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/* =============================================================== menus */

/**
 * `.menu`: surface 2, a strong border, 12 dp corners, 6 dp padding, at least
 * 216 dp wide. Items are [MenuItem]; groups split with [MenuSeparator].
 */
@Composable
fun Menu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        shape = RoundedCornerShape(Design.Radius.Menu),
        containerColor = Design.Colors.Surface2,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Design.Colors.BorderStrong),
        modifier = modifier.widthIn(min = 216.dp),
    ) {
        Column(Modifier.padding(horizontal = 6.dp), content = content)
    }
}

/** `.menu-item`: 44 dp, a secondary icon and the label; [danger] in red. */
@Composable
fun MenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val color = when {
        !enabled -> Design.Colors.TextDisabled
        danger -> Design.Colors.Error
        else -> Design.Colors.Text
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(Design.Radius.MenuItem))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (danger && enabled) Design.Colors.Error else Design.Colors.TextSecondary,
                modifier = Modifier.size(Design.Size.IconSmall),
            )
        }
        Text(text, style = Design.Type.Body, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `.menu-sep`: a divider that runs through the menu's 6 dp padding to its border. */
@Composable
fun MenuSeparator() {
    Box(
        Modifier
            .padding(vertical = 6.dp)
            .layout { measurable, constraints ->
                val bleed = 6.dp.roundToPx()
                if (!constraints.hasBoundedWidth) {
                    val placeable = measurable.measure(constraints)
                    return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                val wide = constraints.maxWidth + 2 * bleed
                val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
            }
            .fillMaxWidth()
            .height(1.dp)
            .background(Design.Colors.Divider),
    )
}

/* ============================================================== toasts */

/** One message at a time, replaced by the next, gone after 2.2 s. */
@Stable
class Toaster {
    internal var current by mutableStateOf<ToastMessage?>(null)
    private var serial = 0L

    fun show(text: String, icon: ImageVector? = null) {
        serial++
        current = ToastMessage(serial, text, icon)
    }

    internal fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }
}

internal data class ToastMessage(val id: Long, val text: String, val icon: ImageVector?)

val LocalToaster = staticCompositionLocalOf { Toaster() }

/**
 * `.toast`: surface 3, a strong border, 12 dp corners, rising 8 dp as it fades
 * in. [bottom] keeps it above whatever bar the screen has.
 */
@Composable
fun BoxScope.ToastHost(toaster: Toaster, bottom: Dp) {
    val message = toaster.current
    val scope = rememberCoroutineScope()
    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    androidx.compose.runtime.LaunchedEffect(message?.id) {
        val shown = message ?: return@LaunchedEffect
        delay(TOAST_MILLIS)
        toaster.dismiss(shown.id)
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(Design.Motion.Toast)) +
            slideInVertically(tween(Design.Motion.Toast, easing = Design.Motion.Decelerate)) { rise },
        exit = fadeOut(tween(Design.Motion.Toast)) + slideOutVertically(tween(Design.Motion.Toast)) { rise },
        modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = bottom),
    ) {
        val shown = message ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(Design.Radius.Button)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(Design.Colors.Surface3)
                .border(1.dp, Design.Colors.BorderStrong, shape)
                .clickable { scope.launch { toaster.dismiss(shown.id) } }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                shown.icon ?: Codicons.Info,
                contentDescription = null,
                tint = Design.Colors.Text,
                modifier = Modifier.size(Design.Size.IconSmall),
            )
            Text(shown.text, style = Design.Type.BodySmall, color = Design.Colors.Text, modifier = Modifier.weight(1f))
        }
    }
}

private const val TOAST_MILLIS = 2_200L

/** Provide a [Toaster] to everything below, and the host that draws it. */
@Composable
fun ProvideToaster(toaster: Toaster, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalToaster provides toaster, content = content)
}
