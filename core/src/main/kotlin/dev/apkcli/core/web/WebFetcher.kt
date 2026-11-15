package dev.apkcli.core.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.apkcli.core.llm.Http
import dev.apkcli.core.llm.executeCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fetches a web page as readable text.
 *
 * Two tiers, because the cheap one is right most of the time:
 *
 * **Static** — an HTTP GET plus [HtmlExtractor]. Covers documentation, source
 * forges, blogs, RFCs, package registries. Costs one request and no rendering.
 * It asks for Markdown first, which sites behind Cloudflare's Markdown for
 * Agents and many documentation hosts serve, and says it is a tool: after a
 * bot check it asks once more as nothing but one (see [fetch]).
 *
 * **Rendered** — the page is loaded in a `WebView` and the post-JavaScript DOM
 * is read out. This is the Android equivalent of driving headless Chrome, and
 * it is *cheaper* here than on a desktop: Chromium is already present as a
 * system component, so there is no browser to download, install or keep
 * updated. The renderer also already runs in its own sandboxed process, so a
 * page that crashes does not take the app with it.
 *
 * The rendered tier is opt-in and is also suggested automatically when the
 * static fetch comes back as an empty client-side shell — that detection is
 * what stops the model from being handed three words from a React app and
 * concluding the page is empty.
 *
 * Costs that justify keeping it opt-in: starting a WebView is hundreds of
 * milliseconds, running a site's JavaScript is seconds and real battery, and
 * some pages never stop making requests.
 *
 * **Known cost, not yet fixed.** The rendered tier runs on `Dispatchers.Main`,
 * because a WebView must be constructed on a thread with a Looper and all
 * WebViews in a process must share one. In the current single-process layout
 * that is the UI thread, so constructing the view costs a few dropped frames.
 * The page load itself is asynchronous and does not block. The real fix is the
 * `:tools` process described in docs/ARCHITECTURE.md; until then this is one
 * more reason the tier is opt-in rather than automatic.
 */
class WebFetcher(private val context: Context) {

    data class Page(
        val requestedUrl: String,
        val finalUrl: String,
        val status: Int,
        val title: String,
        val text: String,
        val links: List<HtmlExtractor.Result.Link>,
        val renderedWithJs: Boolean,
        val contentType: String,
        val bytes: Int,
        val truncated: Boolean,
        /** Set when the static fetch looked like an unhydrated shell. */
        val suggestsRendering: Boolean,
    )

    sealed interface Outcome {
        data class Success(val page: Page) : Outcome

        data class Failure(
            val message: String,
            val status: Int? = null,
            /** The address asked, normalised; null when it was not one. */
            val url: String? = null,
            /**
             * The site would not answer this device — refused it, rate-limited
             * it, blocked it for its region, or could not be reached — rather
             * than answering with a page that is not there.
             */
            val blocked: Boolean = false,
            /** The refusal was a bot check (Cloudflare's), which a browser engine may pass. */
            val botCheck: Boolean = false,
        ) : Outcome
    }

    /**
     * @param headers extra request headers. Used for API credentials on the
     *   static path; ignored when rendering, since a WebView load cannot carry
     *   arbitrary headers reliably.
     */
    suspend fun fetch(
        url: String,
        renderJs: Boolean = false,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        headers: Map<String, String> = emptyMap(),
    ): Outcome {
        val normalised = normaliseUrl(url)
            ?: return Outcome.Failure(
                "Not a fetchable URL: '$url'. Only http and https are supported.",
            )

        if (renderJs) return fetchRendered(normalised, timeoutMs, maxBytes)
        // A bot check is shown to clients that claim to be a browser; one
        // that says plainly it is a tool is let through or refused, which
        // is what OpenCode found as well. Asked again once as that.
        val first = fetchStatic(normalised, maxBytes, timeoutMs, headers, USER_AGENT)
        if (first !is Outcome.Failure || !first.botCheck) return first
        return fetchStatic(normalised, maxBytes, timeoutMs, headers, PLAIN_USER_AGENT)
    }

    /* ------------------------------------------------------------- static */

    private suspend fun fetchStatic(
        url: String,
        maxBytes: Int,
        timeoutMs: Long,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = USER_AGENT,
    ): Outcome {
        val client = Http.client.newBuilder()
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            // The whole call, not just each read: DNS, connect, TLS and a body
            // that trickles in a byte at a time all count. A read timeout
            // alone let a slow server keep a search "running" indefinitely.
            .callTimeout(timeoutMs + CALL_TIMEOUT_SLACK_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            // Markdown first: a site behind Cloudflare's Markdown for Agents,
            // and many documentation hosts, answer with the page as Markdown,
            // already without its navigation.
            .header("Accept", ACCEPT)
            .header("Accept-Language", "en,*;q=0.5")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()

        return try {
            client.newCall(request).executeCancellable { response ->
                val contentType = response.header("Content-Type") ?: ""
                // Before the type check: a bot check is an HTML page.
                if (isBotCheck(response.code, response.header("cf-mitigated"))) {
                    return@executeCancellable Outcome.Failure(
                        "HTTP ${response.code}: the site answered with a bot check (Cloudflare).",
                        response.code,
                        url = url,
                        blocked = true,
                        botCheck = true,
                    )
                }
                if (!isTextual(contentType)) {
                    return@executeCancellable Outcome.Failure(
                        "The response is $contentType, which is not text. This tool reads " +
                            "HTML, plain text, Markdown and JSON. Binary content, images and " +
                            "PDFs cannot be read — Android can render a PDF to an image but " +
                            "has no text extraction for it.",
                        response.code,
                    )
                }

                // OkHttp 5: `body` is non-null. An empty body is a zero-byte
                // ResponseBody, which the read loop below handles.
                val body = response.body

                // Read at most `limit` bytes. Chunked rather than
                // `readByteString(limit)`, which throws EOFException when the
                // page is smaller than the limit — i.e. almost always.
                val limit = minOf(maxBytes.toLong(), MAX_HARD_BYTES)
                val source = body.source()
                val buffer = Buffer()
                var remaining = limit
                while (remaining > 0) {
                    val read = source.read(buffer, minOf(remaining, CHUNK_BYTES))
                    if (read == -1L) break
                    remaining -= read
                }
                val truncated = !source.exhausted()
                val raw = decodeBody(buffer.readByteArray(), contentType)

                if (!response.isSuccessful) {
                    return@executeCancellable Outcome.Failure(
                        "HTTP ${response.code} ${response.message}. Body: ${raw.take(500)}",
                        response.code,
                        url = url,
                        blocked = response.code in REFUSALS,
                    )
                }

                val finalUrl = response.request.url.toString()
                val page = if (contentType.contains("html", ignoreCase = true)) {
                    val extracted = HtmlExtractor.extract(raw, finalUrl)
                    Page(
                        requestedUrl = url,
                        finalUrl = finalUrl,
                        status = response.code,
                        title = extracted.title,
                        text = extracted.text,
                        links = extracted.links,
                        renderedWithJs = false,
                        contentType = contentType,
                        bytes = raw.length,
                        truncated = truncated,
                        suggestsRendering = extracted.looksLikeEmptyShell,
                    )
                } else {
                    // Plain text, Markdown and JSON are already readable.
                    Page(
                        requestedUrl = url,
                        finalUrl = finalUrl,
                        status = response.code,
                        title = if (contentType.contains("markdown", ignoreCase = true)) markdownTitle(raw) else "",
                        text = raw,
                        links = emptyList(),
                        renderedWithJs = false,
                        contentType = contentType,
                        bytes = raw.length,
                        truncated = truncated,
                        suggestsRendering = false,
                    )
                }
                Outcome.Success(page)
            }
        } catch (t: Throwable) {
            // Stopped from outside: unwind, do not report it as a failed fetch.
            currentCoroutineContext().ensureActive()
            // No answer at all: the host is down, mistyped, or blocked on the
            // way here, which another service's network may not be.
            Outcome.Failure(Http.classify(t).message, url = url, blocked = true)
        }
    }

    /* ----------------------------------------------------------- rendered */

    private suspend fun fetchRendered(
        url: String,
        timeoutMs: Long,
        maxBytes: Int,
    ): Outcome {
        val html = try {
            renderToHtml(url, timeoutMs)
        } catch (t: Throwable) {
            Log.w(TAG, "render failed", t)
            return Outcome.Failure(
                "JavaScript rendering failed: ${t.javaClass.simpleName}: ${t.message}. " +
                    "Retry without render, or check that the URL loads at all.",
            )
        } ?: return Outcome.Failure(
            "The page did not finish loading within ${timeoutMs / 1000}s. Some sites " +
                "never go idle. Try the static fetch instead — it often has the content " +
                "anyway.",
        )

        val capped = if (html.length > maxBytes) html.take(maxBytes) else html
        val extracted = HtmlExtractor.extract(capped, url)
        return Outcome.Success(
            Page(
                requestedUrl = url,
                finalUrl = url,
                status = 200,
                title = extracted.title,
                text = extracted.text,
                links = extracted.links,
                renderedWithJs = true,
                contentType = "text/html",
                bytes = capped.length,
                truncated = html.length > maxBytes,
                suggestsRendering = false,
            ),
        )
    }

    /**
     * Load [url] in an offscreen WebView and return the post-script DOM.
     *
     * Not attached to any window. The WebView is still laid out to a plausible
     * viewport, because a zero-size view makes some sites skip rendering
     * entirely and makes every `IntersectionObserver`-driven lazy loader
     * conclude nothing is visible.
     *
     * Must run on a thread with a Looper — WebView construction asserts on it.
     * The whole lifecycle therefore sits on the main dispatcher of whichever
     * process calls this (the `:tools` process in normal operation).
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderToHtml(url: String, timeoutMs: Long): String? =
        withContext(Dispatchers.Main) {
            var webView: WebView? = null
            try {
                withTimeoutOrNull(timeoutMs) {
                    val finished = CompletableDeferred<Unit>()
                    // Monotonic count of subresource requests the page has
                    // started. The page is treated as settled once this stops
                    // increasing for SETTLE_MS — a better signal than
                    // onPageFinished alone, which fires before most SPAs have
                    // fetched the data they render from.
                    val requestsStarted = AtomicInteger(0)

                    val view = WebView(context)
                    webView = view
                    view.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // Images are never part of the text we extract, and on
                        // a metered phone connection they are most of the bytes.
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        userAgentString = BROWSER_USER_AGENT
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        // No reason for a fetched page to reach the filesystem.
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        mediaPlaybackRequiresUserGesture = true
                        setGeolocationEnabled(false)
                        setSupportMultipleWindows(false)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        view.settings.safeBrowsingEnabled = true
                    }

                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            // Stay on the page we asked for; do not chase
                            // redirects into app links or other schemes.
                            val target = request?.url?.scheme?.lowercase()
                            return target != "http" && target != "https"
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!finished.isCompleted) finished.complete(Unit)
                        }

                        override fun onLoadResource(view: WebView?, url: String?) {
                            requestsStarted.incrementAndGet()
                        }
                    }

                    // A plausible viewport, without being in the view hierarchy.
                    view.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                    view.loadUrl(url)

                    finished.await()

                    // Wait for the request count to stop moving. The outer
                    // withTimeoutOrNull is the backstop for pages that poll
                    // forever and never go quiet.
                    var lastSeen = -1
                    var quietFor = 0L
                    while (quietFor < SETTLE_MS) {
                        delay(POLL_MS)
                        val now = requestsStarted.get()
                        if (now == lastSeen) quietFor += POLL_MS else quietFor = 0
                        lastSeen = now
                    }

                    val result = CompletableDeferred<String>()
                    view.evaluateJavascript("document.documentElement.outerHTML") { value ->
                        result.complete(value ?: "null")
                    }
                    val encoded = result.await()
                    // evaluateJavascript hands back a JSON string literal, so
                    // the HTML arrives escaped and has to be decoded.
                    runCatching {
                        Http.json.parseToJsonElement(encoded).jsonPrimitive.content
                    }.getOrElse { encoded }
                }
            } finally {
                webView?.let { view ->
                    runCatching {
                        view.stopLoading()
                        view.webViewClient = WebViewClient()
                        view.loadUrl("about:blank")
                        view.destroy()
                    }
                }
            }
        }

    /* ------------------------------------------------------------ helpers */

    /**
     * Decode the body with the right charset.
     *
     * Assuming UTF-8 would be wrong often enough to matter: a large part of the
     * Russian-language web is still served as windows-1251, and decoding that
     * as UTF-8 produces mojibake that is worse than useless — the model gets
     * plausible-looking garbage rather than an obvious failure.
     *
     * Order: the `Content-Type` header, then a `<meta charset>` sniff over the
     * head of the document, then UTF-8.
     */
    private fun decodeBody(bytes: ByteArray, contentType: String): String {
        val fromHeader = CHARSET_PATTERN.find(contentType)?.groupValues?.getOrNull(1)
        val fromMeta = if (fromHeader == null) {
            // Sniff as ISO-8859-1 so every byte maps to a character and the
            // declaration is readable whatever the real encoding is.
            val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
            META_CHARSET_PATTERN.find(head)?.groupValues?.getOrNull(1)
                ?: CHARSET_PATTERN.find(head)?.groupValues?.getOrNull(1)
        } else {
            null
        }

        val name = (fromHeader ?: fromMeta)?.trim()?.trim('"', '\'')
        val charset = name?.let {
            runCatching { java.nio.charset.Charset.forName(it) }.getOrNull()
        } ?: Charsets.UTF_8

        return String(bytes, charset)
    }

    private fun isTextual(contentType: String): Boolean {
        val ct = contentType.lowercase()
        return ct.isEmpty() ||
            ct.startsWith("text/") ||
            ct.contains("html") ||
            ct.contains("json") ||
            ct.contains("xml") ||
            ct.contains("javascript") ||
            ct.contains("markdown")
    }

    /**
     * Accept what a model is likely to pass and reject what must not be fetched.
     *
     * `file:`, `content:` and `data:` are refused outright: a tool that fetches
     * URLs should not become a way to read the device's filesystem through a
     * URL-shaped argument.
     */
    private fun normaliseUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains("://") -> return null // some other scheme: refuse
            else -> "https://$trimmed"
        }
        return runCatching {
            val url = java.net.URL(withScheme)
            if (url.host.isNullOrBlank()) null else withScheme
        }.getOrNull()
    }

    companion object {
        private const val TAG = "WebFetcher"

        /**
         * Who the static tier says it is: browser-like, so sites serve their
         * full desktop page rather than a stripped mobile one or a push to
         * their app, and honest about being a tool. A plain Chrome string
         * from a client whose TLS is not Chrome's is what Cloudflare's bot
         * checks are for. OpenCode's, with this app's name.
         */
        private const val USER_AGENT =
            "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; ApkCLI/0.1; " +
                "+https://github.com/Corrotpot/ApkCLI"

        /** Asked as after a bot check: a tool and nothing else, which such checks let through or refuse. */
        private const val PLAIN_USER_AGENT = "ApkCLI"

        /**
         * The rendered tier's: a desktop Chrome, which the WebView is, so
         * sites that detect Android do not hand it their mobile page.
         */
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        /** Markdown first, then HTML, as OpenCode asks. */
        private const val ACCEPT =
            "text/markdown;q=1.0, text/x-markdown;q=0.9, text/html;q=0.8, application/xhtml+xml;q=0.8, " +
                "text/plain;q=0.7, application/json;q=0.6, */*;q=0.1"

        /** Answers that refuse the device rather than say the page is not there: forbidden, too many, blocked for its region. */
        private val REFUSALS = setOf(403, 429, 451)

        /** Cloudflare's bot check: a 403 or 503 marked `cf-mitigated: challenge`. */
        internal fun isBotCheck(status: Int, cfMitigated: String?): Boolean =
            (status == 403 || status == 503) && cfMitigated?.trim().equals("challenge", ignoreCase = true)

        /**
         * A Markdown page's title: `title:` in its front matter, which
         * Cloudflare's conversion writes, or else its first `#` heading.
         */
        internal fun markdownTitle(text: String): String {
            val lines = text.trimStart().lines()
            if (lines.firstOrNull()?.trim() == "---") {
                val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
                if (end >= 0) {
                    lines.subList(1, end + 1)
                        .firstOrNull { it.startsWith("title:") }
                        ?.substringAfter(':')?.trim()?.trim('"', '\'')
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { return it.take(300) }
                }
            }
            return lines.take(MARKDOWN_TITLE_LINES)
                .firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")?.trim()?.take(300)
                .orEmpty()
        }

        /** How far into a Markdown page its title heading is looked for. */
        private const val MARKDOWN_TITLE_LINES = 40

        const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_HARD_BYTES = 8L * 1024 * 1024
        private const val CHUNK_BYTES = 64L * 1024
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /** On top of the read timeout, for connecting and the body as a whole. */
        private const val CALL_TIMEOUT_SLACK_MS = 15_000L

        private val CHARSET_PATTERN =
            Regex("""charset\s*=\s*["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
        private val META_CHARSET_PATTERN =
            Regex("""<meta[^>]+charset\s*=\s*["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)

        private const val VIEWPORT_WIDTH = 1280
        private const val VIEWPORT_HEIGHT = 2000
        private const val SETTLE_MS = 700L
        private const val POLL_MS = 150L
    }
}
