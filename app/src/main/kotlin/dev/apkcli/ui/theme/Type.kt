package dev.apkcli.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle

/**
 * Type.
 *
 * Inter for interface and prose, JetBrains Mono for code, both bundled and both
 * from the design tokens ([Design.Type]). They used to be the platform fonts, to
 * save about a megabyte; that saved the megabyte and lost the design, because
 * the system sans is a different typeface on a Pixel, a Samsung and a Xiaomi,
 * and no layout survives that 1:1. Both cover Cyrillic.
 *
 * Ligatures are off in the mono style (`liga`/`calt`): `!=` and `->` should
 * read as the characters typed, in code and in the terminal alike.
 */

/** Code, terminal output, diffs, tool arguments. */
val MonoStyle: TextStyle get() = Design.Type.Code

/** Smaller mono, for inline file paths, ids and token counts. */
val MonoSmallStyle: TextStyle get() = Design.Type.CodeSmall

/** Reasoning text: smaller than prose, so it reads as an aside. */
val ThinkingStyle: TextStyle get() = Design.Type.BodySmall

/** Section headers, set in capitals by the component that draws them. */
val SectionLabelStyle: TextStyle get() = Design.Type.Overline

/**
 * Material's slots, each filled with the design style that plays that part, so
 * anything still drawn through MaterialTheme.typography matches the rest.
 */
val AppTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Design.Fonts.Sans),
        displayMedium = base.displayMedium.copy(fontFamily = Design.Fonts.Sans),
        displaySmall = base.displaySmall.copy(fontFamily = Design.Fonts.Sans),
        headlineLarge = Design.Type.Title,
        headlineMedium = Design.Type.Title,
        headlineSmall = Design.Type.Title,

        titleLarge = Design.Type.Headline,
        titleMedium = Design.Type.Label,
        titleSmall = Design.Type.LabelMedium,

        bodyLarge = Design.Type.Body,
        bodyMedium = Design.Type.BodyMedium,
        bodySmall = Design.Type.BodySmall,

        labelLarge = Design.Type.Label,
        labelMedium = Design.Type.LabelSmall,
        labelSmall = Design.Type.LabelTiny,
    )
}
