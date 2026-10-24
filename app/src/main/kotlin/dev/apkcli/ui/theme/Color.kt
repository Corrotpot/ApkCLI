package dev.apkcli.ui.theme

import androidx.compose.material3.darkColorScheme

/**
 * Material's roles, filled from the design tokens.
 *
 * The screens draw with [Design] directly; this scheme exists for what still
 * comes from Material — text field cursors and selection handles, the odd
 * progress indicator — so those pick up the same black theme instead of
 * Material's purple.
 *
 * The mapping follows what each role means in the design rather than its
 * Material name:
 *
 *  * `primary` is the mint accent, because Material uses it for focus, the
 *    cursor and selection handles — exactly where the design uses the accent.
 *    The design's white "primary button" is not a Material role; buttons draw
 *    it themselves (see `ui.design`).
 *  * Surfaces step up from pure black: `surfaceContainerLow` is the card,
 *    `High` the field, `Highest` the selected chip.
 */
internal val DesignScheme = darkColorScheme(
    primary = Design.Colors.Accent,
    onPrimary = Design.Colors.OnAccent,
    primaryContainer = Design.Colors.AccentMuted,
    onPrimaryContainer = Design.Colors.Text,

    secondary = Design.Colors.Accent,
    onSecondary = Design.Colors.OnAccent,
    secondaryContainer = Design.Colors.Surface3,
    onSecondaryContainer = Design.Colors.Text,

    tertiary = Design.Colors.Accent,
    onTertiary = Design.Colors.OnAccent,
    tertiaryContainer = Design.Colors.AccentMuted,
    onTertiaryContainer = Design.Colors.Text,

    background = Design.Colors.Bg,
    onBackground = Design.Colors.Text,

    surface = Design.Colors.Bg,
    onSurface = Design.Colors.Text,
    surfaceVariant = Design.Colors.Surface2,
    onSurfaceVariant = Design.Colors.TextSecondary,
    surfaceTint = androidx.compose.ui.graphics.Color.Transparent,

    surfaceContainerLowest = Design.Colors.Bg,
    surfaceContainerLow = Design.Colors.Surface1,
    surfaceContainer = Design.Colors.Surface1,
    surfaceContainerHigh = Design.Colors.Surface2,
    surfaceContainerHighest = Design.Colors.Surface3,
    surfaceBright = Design.Colors.Surface3,
    surfaceDim = Design.Colors.Bg,

    outline = Design.Colors.BorderStrong,
    outlineVariant = Design.Colors.Border,

    error = Design.Colors.Error,
    onError = Design.Colors.Bg,
    errorContainer = Design.Colors.ErrorMuted,
    onErrorContainer = Design.Colors.Error,

    inverseSurface = Design.Colors.Text,
    inverseOnSurface = Design.Colors.Bg,
    inversePrimary = Design.Colors.Accent,
    scrim = Design.Colors.Scrim,
)
