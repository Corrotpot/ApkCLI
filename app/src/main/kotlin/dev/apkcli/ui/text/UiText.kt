package dev.apkcli.ui.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources

/**
 * Text produced outside composition, resolved inside it.
 *
 * ViewModels hold the application context, whose resources are in the
 * *system* locale on Android 12 and older — the in-app language only reaches
 * the Activity there (see [dev.apkcli.ui.locale.AppLocales]). A ViewModel that
 * called `getString` would therefore show English notices in a Russian UI.
 * Instead it describes the text, and the screen turns it into a string with
 * the Activity's resources.
 *
 * Arguments may themselves be [UiText]; they are resolved recursively.
 */
sealed interface UiText {

    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Already final: provider messages, file names, numbers. */
    data class Raw(val text: String) : UiText

    /** Parts joined by [separator], for "headline (HTTP 401)"-style composites. */
    data class Concat(val parts: List<UiText>, val separator: String = "") : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

        fun raw(text: String): UiText = Raw(text)
    }
}

/** Resolve with explicit resources; for non-composable callers holding an Activity context. */
fun UiText.resolve(context: Context): String = resolveWith(context.resources)

@Composable
@ReadOnlyComposable
fun UiText.resolve(): String {
    // Read the context too, so a language change (a configuration change in
    // this app) recomposes every caller.
    LocalContext.current
    return resolveWith(LocalResources.current)
}

private fun UiText.resolveWith(resources: android.content.res.Resources): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> if (args.isEmpty()) {
        resources.getString(id)
    } else {
        resources.getString(id, *args.map { it.resolveArg(resources) }.toTypedArray())
    }
    is UiText.Plural -> resources.getQuantityString(id, count, *args.map { it.resolveArg(resources) }.toTypedArray())
    is UiText.Concat -> parts.joinToString(separator) { it.resolveWith(resources) }
}

private fun Any.resolveArg(resources: android.content.res.Resources): Any =
    if (this is UiText) resolveWith(resources) else this
