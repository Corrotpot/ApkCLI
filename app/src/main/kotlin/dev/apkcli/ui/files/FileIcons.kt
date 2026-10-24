package dev.apkcli.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.apkcli.R
import dev.apkcli.ui.icons.SetiIcons
import dev.apkcli.ui.theme.LocalEditorColors

/*
 * File-type icons as VS Code draws them by default: its Seti theme, which is
 * a font — one glyph per file type, coloured by the theme. The font is VS
 * Code's own seti.woff (unwrapped to TrueType for Android, the glyphs
 * untouched) and the colours and mappings are its theme's; see
 * tools/gen_seti_icons.py. The name beside the icon carries the meaning, so
 * the icons are decorative (no content description).
 */

private val Seti = FontFamily(Font(R.font.seti))

/**
 * VS Code sets the glyph at 150% of the explorer's 13px text, in a 16px
 * box: 1.22 times the box. It may overhang the box a little, as it does there.
 */
private const val GLYPH_SCALE = 1.22f

/** The icon for a file called [name]: Python's, Kotlin's, a JSON brace, as VS Code shows them. */
@Composable
fun FileTypeIcon(name: String, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val light = LocalEditorColors.current.canvas.luminance() > 0.5f
    val glyph = remember(name, light) { SetiIcons.forFile(name, light) }
    val fontSize = with(LocalDensity.current) { (size * GLYPH_SCALE).toSp() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Text(
            text = glyph.char.toString(),
            color = Color(glyph.argb),
            style = TextStyle(
                fontFamily = Seti,
                fontSize = fontSize,
                lineHeight = fontSize,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            maxLines = 1,
            softWrap = false,
            // Centred on the box and allowed past its edges, as in VS Code.
            modifier = Modifier.wrapContentSize(unbounded = true),
        )
    }
}
