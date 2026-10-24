package dev.apkcli.ui.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** The UI languages the app ships. [SYSTEM] follows the device. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    RUSSIAN("ru"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag != null && tag != null && tag.startsWith(it.tag) } ?: SYSTEM
    }
}

/**
 * Per-app language.
 *
 * Two mechanisms, because the platform only grew one in Android 13:
 *
 *  * **API 33+**: [LocaleManager]. The system stores the choice, shows it in
 *    Settings › Apps › Language (that is what `locales_config.xml` is for), and
 *    delivers it as a configuration change. `locale` is in the Activity's
 *    `configChanges`, so the change is applied in place — a streaming turn and
 *    an open editor survive it — and Compose recomposes with the new strings.
 *  * **API 26–32**: a preference, applied by wrapping the Activity's base
 *    context in [wrap]. The override configuration is deliberately sparse —
 *    only the locale is set — so it merges with the real configuration and
 *    rotation keeps updating the rest. Changing it recreates the Activity;
 *    ViewModels, and therefore a running turn, survive that.
 *
 * AppCompat's `setApplicationLocales` would cover both, but only for an
 * AppCompatActivity, and pulling in AppCompat for this alone is a poor trade.
 */
object AppLocales {

    private const val PREFS = "apkcli_ui"
    private const val KEY_LANGUAGE = "language"

    fun current(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            return if (locales == null || locales.isEmpty) AppLanguage.SYSTEM else AppLanguage.fromTag(locales[0].toLanguageTag())
        }
        return AppLanguage.fromTag(storedTag(context))
    }

    fun apply(context: Context, language: AppLanguage) {
        if (current(context) == language) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            manager.applicationLocales = language.tag
                ?.let { LocaleList.forLanguageTags(it) }
                ?: LocaleList.getEmptyLocaleList()
            return
        }
        // commit, not apply: the recreated Activity reads this synchronously
        // in attachBaseContext, before an async write is guaranteed to land.
        prefs(context).edit().putString(KEY_LANGUAGE, language.tag).commit()
        context.findActivity()?.recreate()
    }

    /** For `Activity.attachBaseContext`. A no-op on API 33+, where the platform does this. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = storedTag(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val override = Configuration().apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(override)
    }

    private fun storedTag(context: Context): String? =
        prefs(context).getString(KEY_LANGUAGE, null)?.takeIf { it.isNotBlank() }

    // Straight from the given context: in attachBaseContext it is the bare
    // ContextImpl, and preferences are per process, not per context.
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The Activity behind a (possibly wrapped) context. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
