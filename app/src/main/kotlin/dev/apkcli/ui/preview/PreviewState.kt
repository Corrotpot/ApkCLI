package dev.apkcli.ui.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.apkcli.core.agent.ConsoleLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * The preview's browser and what the screen shows of it: the address, the
 * page's title, how far it has loaded, and an address that did not answer.
 *
 * One per activity (RootScreen keeps it), with one WebView for its whole
 * life: going to the files and back leaves a page where it was, scrolled and
 * with its state, rather than loading it again. Each project remembers the
 * address it showed last.
 *
 * It also keeps what the agent's preview tools read ([PreviewAgent]): the
 * page's console, why it failed, and when a load ended. All of it is touched
 * on the main thread only, where the WebView calls back.
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

    /** Why the page did not load, in the network's words; with [failed]. */
    internal var failureReason: String? = null

    /** The server's error status for the page itself (`HTTP 404`), which still shows a page. */
    internal var httpError: String? = null

    private val settledLoads = MutableStateFlow(0L)

    /** Counts the page loads that ended, finished or failed, for a waiter to see its page come in. */
    internal val settled: StateFlow<Long> = settledLoads.asStateFlow()

    /** The page's console since it began loading or was last read; the oldest go past [MAX_CONSOLE]. */
    private val console = ArrayDeque<ConsoleLine>()
    private var consoleDropped = 0

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

    /** A page began loading: its console and its failure are its own. */
    internal fun onPageStarted() {
        console.clear()
        consoleDropped = 0
        failureReason = null
        httpError = null
    }

    /** A page load ended, loaded or not. */
    internal fun onLoadEnded() {
        settledLoads.value += 1
    }

    internal fun onConsole(line: ConsoleLine) {
        if (console.size >= MAX_CONSOLE) {
            console.removeFirst()
            consoleDropped++
        }
        console.addLast(line)
    }

    /** The console since it was last taken, and how many older lines were not kept; it starts empty again. */
    internal fun takeConsole(): Pair<List<ConsoleLine>, Int> {
        val taken = console.toList() to consoleDropped
        console.clear()
        consoleDropped = 0
        return taken
    }

    /** The address the browser itself is on, after redirects; null before it has one. */
    internal fun currentAddress(): String? = webView?.url?.takeIf { it.isNotEmpty() && it != BLANK } ?: url

    /** Whether the browser has been made: it is, the first time the preview's pane is on screen. */
    internal fun hasBrowser(): Boolean = webView != null

    /** Whether the browser is on screen and laid out, which a picture of it needs. */
    internal fun onScreen(): Boolean = webView?.let { it.isAttachedToWindow && it.width > 0 && it.height > 0 } == true

    /** What the browser shows, drawn into a bitmap; null when it is not on screen. */
    internal fun capture(): Bitmap? {
        val view = webView?.takeIf { onScreen() } ?: return null
        return runCatching {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }.getOrNull()
    }

    /**
     * [script] run in the page, its value as the WebView gives it: JSON.
     * Null when there is no browser yet, or it never answered: a page that
     * navigated away mid-call answers nothing.
     */
    internal suspend fun evaluate(script: String): String? {
        val view = webView ?: return null
        return suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value) }
        }
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

        /** A page that logs on every frame would otherwise grow this without end. */
        const val MAX_CONSOLE = 200
    }
}
