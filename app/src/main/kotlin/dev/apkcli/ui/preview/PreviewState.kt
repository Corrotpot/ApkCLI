package dev.apkcli.ui.preview

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * The preview's browser and what the screen shows of it: the address, the
 * page's title, how far it has loaded, and an address that did not answer.
 *
 * One per activity (RootScreen keeps it), with one WebView for its whole
 * life: going to the files and back leaves a page where it was, scrolled and
 * with its state, rather than loading it again. Each project remembers the
 * address it showed last.
 */
@Stable
class PreviewState(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What is shown; null while nothing is. */
    var url by mutableStateOf<String?>(null)
        private set

    var title by mutableStateOf<String?>(null)
        internal set

    /** How far the page has loaded, 0 to 100; 100 when nothing is loading. */
    var progress by mutableIntStateOf(100)
        internal set

    /** An address that did not answer, while the screen says so. */
    var failed by mutableStateOf<String?>(null)
        internal set

    var canGoBack by mutableStateOf(false)
        internal set

    /** The project shown, whose files [PreviewUrls.PROJECT_HOST] serves. Read on the WebView's own threads. */
    @Volatile
    var project: File? = null
        private set

    private var webView: WebView? = null

    /** Show [dir]: the address it had last, or nothing yet. */
    fun openProject(dir: File?) {
        if (dir?.absolutePath == project?.absolutePath) return
        project = dir
        url = dir?.let { prefs.getString(key(it), null) }
        title = null
        failed = null
        canGoBack = false
        webView?.let { view ->
            view.loadUrl(url ?: BLANK)
            // Back is within a project's pages, not into the last one's.
            view.clearHistory()
        }
    }

    fun load(address: String) {
        failed = null
        keep(address)
        webView?.loadUrl(address)
    }

    fun reload() {
        failed = null
        val view = webView ?: return
        // A page that never answered has nothing to reload; its address is loaded again.
        if (view.url.isNullOrEmpty() || view.url == BLANK) url?.let(view::loadUrl) else view.reload()
    }

    /** One page back, in the browser's history; false when there is none. */
    fun back(): Boolean {
        val view = webView ?: return false
        if (!view.canGoBack()) return false
        view.goBack()
        return true
    }

    /** The browser, made the first time; taken off the screen it was on before. */
    internal fun view(context: Context): WebView {
        webView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        return PreviewWebView.create(context, this).also { created ->
            webView = created
            url?.let(created::loadUrl)
        }
    }

    /** The page went somewhere: a link, a redirect, a step back. */
    internal fun onNavigated(address: String) {
        if (address != BLANK) keep(address)
    }

    fun destroy() {
        webView?.destroy()
        webView = null
    }

    private fun keep(address: String) {
        url = address
        project?.let { prefs.edit().putString(key(it), address).apply() }
    }

    private fun key(dir: File) = "url:${dir.absolutePath}"

    private companion object {
        const val PREFS = "preview"
        const val BLANK = "about:blank"
    }
}
