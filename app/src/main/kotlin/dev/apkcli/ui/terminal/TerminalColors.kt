package dev.apkcli.ui.terminal

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.apkcli.core.runtime.TermColor
import dev.apkcli.core.runtime.TermStyle
import dev.apkcli.ui.theme.Design

/**
 * The ANSI palette: design/tokens.json `ansi`, VS Code's dark terminal colours.
 *
 * The sixteen named colours are a convention, not values: a program asking
 * for "red" means "the terminal's red". Indices 16–231 are the 6x6x6 cube and
 * 232–255 the grey ramp, both defined by xterm as exact formulas — computed
 * rather than tabulated.
 */
object TerminalPalette {

    private val named = listOf(
        Design.Ansi.Black, Design.Ansi.Red, Design.Ansi.Green, Design.Ansi.Yellow,
        Design.Ansi.Blue, Design.Ansi.Magenta, Design.Ansi.Cyan, Design.Ansi.White,
        Design.Ansi.BrightBlack, Design.Ansi.BrightRed, Design.Ansi.BrightGreen, Design.Ansi.BrightYellow,
        Design.Ansi.BrightBlue, Design.Ansi.BrightMagenta, Design.Ansi.BrightCyan, Design.Ansi.BrightWhite,
    )

    fun named(index: Int): Color = named[index.coerceIn(0, 15)]

    fun cube(index: Int): Color = when {
        index < 16 -> named(index)
        index < 232 -> {
            val n = index - 16
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            Color(steps[n / 36 % 6], steps[n / 6 % 6], steps[n % 6])
        }
        else -> {
            val level = 8 + (index - 232) * 10
            Color(level, level, level)
        }
    }
}

/** Resolves what a program asked for against the terminal's colours. */
@Immutable
class TerminalTheme(
    val background: Color = Design.Colors.TerminalBg,
    val foreground: Color = Design.Ansi.Foreground,
) {

    fun resolve(colour: TermColor, default: Color): Color = when (colour) {
        TermColor.Default -> default
        is TermColor.Indexed -> TerminalPalette.cube(colour.index)
        is TermColor.Rgb -> Color(0xFF000000L.toInt() or colour.value)
    }

    /**
     * Foreground and background for a style, with reverse video and dim
     * already applied — the two attributes a renderer forgets, which is how a
     * selected line in a shell's completion menu ends up invisible.
     */
    fun paint(style: TermStyle): Pair<Color, Color?> {
        var fg = resolve(style.fg, foreground)
        var bg = if (style.bg == TermColor.Default) null else resolve(style.bg, background)
        if (style.inverse) {
            val swapped = bg ?: background
            bg = fg
            fg = swapped
        }
        if (style.dim) fg = fg.copy(alpha = 0.6f)
        return fg to bg
    }

    companion object {
        /** The one the design has. */
        val Default = TerminalTheme()
    }
}
