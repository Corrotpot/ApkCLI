package dev.apkcli.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.apkcli.ui.components.PressOverlay

/**
 * Colours with a meaning or a structural job, for code that predates [Design].
 *
 * Every value comes from the design tokens. New code reads [Design.Colors] and
 * [Design.Syntax] directly; this stays so the editor widget, the diff view and
 * the Markdown renderer — which take a colour set as a parameter — keep one
 * place to read it from.
 */
data class EditorColors(
    // structure
    val chrome: Color,
    val canvas: Color,
    val raised: Color,
    val input: Color,
    val selection: Color,
    val border: Color,
    val borderStrong: Color,
    val textTertiary: Color,
    val accentButton: Color,
    val onAccentButton: Color,

    // meaning
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val diffAddForeground: Color,
    val diffAddBackground: Color,
    val diffRemoveForeground: Color,
    val diffRemoveBackground: Color,
    val thinking: Color,
    val codeBackground: Color,
    val currentLine: Color,
    val gutter: Color,

    // syntax: VS Code Dark Modern's token colours, shared by the editor,
    // diffs and code blocks in chat
    val syntaxPlain: Color,
    val syntaxKeyword: Color,
    val syntaxControl: Color,
    val syntaxString: Color,
    val syntaxNumber: Color,
    val syntaxComment: Color,
    val syntaxFunction: Color,
    val syntaxType: Color,
    val syntaxVariable: Color,
    val syntaxConstant: Color,
) {
    val syntaxAnnotation: Color get() = syntaxFunction

    val toolRunning: Color get() = info
    val toolOk: Color get() = success
    val toolError: Color get() = error
    val toolRefused: Color get() = warning

    /** Meter colour for a fill fraction: the accent until the window is nearly full. */
    fun meterFor(fraction: Float): Color = when {
        fraction < 0.70f -> info
        fraction < 0.88f -> warning
        else -> error
    }
}

private val DesignEditorColors = EditorColors(
    chrome = Design.Colors.Bg,
    canvas = Design.Colors.Bg,
    raised = Design.Colors.Surface1,
    input = Design.Colors.Surface2,
    selection = Design.Colors.AccentMuted,
    border = Design.Colors.Border,
    borderStrong = Design.Colors.BorderStrong,
    textTertiary = Design.Colors.TextTertiary,
    accentButton = Design.Colors.Primary,
    onAccentButton = Design.Colors.OnPrimary,
    success = Design.Colors.Success,
    warning = Design.Colors.Warning,
    error = Design.Colors.Error,
    info = Design.Colors.Accent,
    diffAddForeground = Design.Colors.DiffAddFg,
    diffAddBackground = Design.Colors.DiffAddBg,
    diffRemoveForeground = Design.Colors.DiffRemoveFg,
    diffRemoveBackground = Design.Colors.DiffRemoveBg,
    thinking = Design.Colors.Accent,
    codeBackground = Design.Colors.CodeBg,
    currentLine = Design.Colors.CurrentLine,
    gutter = Design.Colors.TextTertiary,
    syntaxPlain = Design.Syntax.Plain,
    syntaxKeyword = Design.Syntax.Keyword,
    syntaxControl = Design.Syntax.Control,
    syntaxString = Design.Syntax.String,
    syntaxNumber = Design.Syntax.Number,
    syntaxComment = Design.Syntax.Comment,
    syntaxFunction = Design.Syntax.Function,
    syntaxType = Design.Syntax.Type,
    syntaxVariable = Design.Syntax.Variable,
    syntaxConstant = Design.Syntax.Constant,
)

val LocalEditorColors = staticCompositionLocalOf { DesignEditorColors }

/** The few Material shapes left in use, at the design's radii. */
private val DesignShapes = Shapes(
    extraSmall = RoundedCornerShape(Design.Radius.InlineCode),
    small = RoundedCornerShape(Design.Radius.Box),
    medium = RoundedCornerShape(Design.Radius.Button),
    large = RoundedCornerShape(Design.Radius.Card),
    extraLarge = RoundedCornerShape(Design.Radius.Sheet),
)

/**
 * The black theme. There is one: the design is black, and a light variant would
 * be a second design to keep in step, not a setting.
 */
@Composable
fun ApkCliTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalEditorColors provides DesignEditorColors) {
        MaterialTheme(
            colorScheme = DesignScheme,
            typography = AppTypography,
            shapes = DesignShapes,
        ) {
            // MaterialTheme provides a ripple as the indication; the design has
            // a pressed overlay instead, so it replaces the ripple inside.
            CompositionLocalProvider(LocalIndication provides PressOverlay, content = content)
        }
    }
}
