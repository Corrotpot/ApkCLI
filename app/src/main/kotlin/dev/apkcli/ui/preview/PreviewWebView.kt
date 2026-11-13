package dev.apkcli.ui.preview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.MimeTypeMap
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.apkcli.R
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The browser the preview shows pages in.
 *
 * Pages under development need JavaScript and storage, so both are on; files
 * on the device are out of reach of a page (no `file:`, no `content:`), and
 * the project's own files are served under [PreviewUrls.PROJECT_HOST] by
 * [Client.shouldInterceptRequest] instead, with no caching, so a reload shows
 * the agent's latest edit.
 */
internal object PreviewWebView {

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, state: PreviewState): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // A page of the project's (HTTPS here) asking a local server (plain HTTP) for data.
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        webViewClient = Client(state)
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                state.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                state.title = title?.takeIf { it.isNotBlank() }
            }
        }
    }

    private class Client(private val state: PreviewState) : WebViewClient() {

        /** The project's files; everything else goes to the network. On a WebView thread. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.url.host != PreviewUrls.PROJECT_HOST) return null
            val path = request.url.path.orEmpty()
            val file = state.project?.let { root -> PreviewUrls.projectFile(root, path) }
                ?: return notFound(view.context, path)
            val type = typeOf(file)
            return WebResourceResponse(
                type,
                if (type.startsWith("text/") || type in TEXT_TYPES) "utf-8" else null,
                200,
                "OK",
                NO_CACHE,
                file.inputStream(),
            )
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            when (request.url.scheme?.lowercase()) {
                "http", "https", "about", "data", "blob" -> return false
                // A link to mail, a phone number, a store page: the app for it,
                // not a page that cannot load here. Never what reaches into
                // the device (`intent:`, `file:`, `content:`).
                "mailto", "tel", "sms", "geo", "market" -> runCatching {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url != state.failed) state.failed = null
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            state.canGoBack = view.canGoBack()
            url?.let(state::onNavigated)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // A page that does not answer, not a picture on it that does not.
            if (request.isForMainFrame) state.failed = request.url.toString()
        }

        /**
         * A dev server's own certificate (`vite --https`) is accepted on this
         * device's loopback alone, where nothing stands between the page and
         * the server; anywhere else a bad certificate stops the page.
         */
        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val host = runCatching { Uri.parse(error.url).host }.getOrNull()
            if (host in LOOPBACK) handler.proceed() else handler.cancel()
        }

        private fun notFound(context: Context, path: String): WebResourceResponse {
            val page = "<!doctype html><meta name=viewport content=\"width=device-width\">" +
                "<body style=\"font:16px sans-serif;padding:24px;color:#888\">" +
                html(context.getString(R.string.preview_not_in_project, path.trimStart('/').ifEmpty { "index.html" })) +
                "</body>"
            return WebResourceResponse("text/html", "utf-8", 404, "Not Found", NO_CACHE, ByteArrayInputStream(page.toByteArray()))
        }

        private fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    /** A file's type as a browser needs it; the platform's table lacks the newer web ones. */
    private fun typeOf(file: File): String {
        val extension = file.extension.lowercase()
        return WEB_TYPES[extension]
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private val WEB_TYPES = mapOf(
        "html" to "text/html", "htm" to "text/html",
        "js" to "text/javascript", "mjs" to "text/javascript", "css" to "text/css",
        "json" to "application/json", "map" to "application/json", "webmanifest" to "application/manifest+json",
        "svg" to "image/svg+xml", "wasm" to "application/wasm",
        "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
    )

    private val TEXT_TYPES = setOf("application/json", "application/manifest+json", "image/svg+xml", "application/javascript")

    private val NO_CACHE = mapOf("Cache-Control" to "no-store")

    private val LOOPBACK = setOf("localhost", "127.0.0.1", "[::1]", "::1")
}
