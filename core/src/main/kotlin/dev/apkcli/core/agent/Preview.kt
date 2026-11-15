package dev.apkcli.core.agent

import dev.apkcli.core.model.Block

/**
 * What the preview, the app's own browser in its Preview tab, shows, as the
 * preview tools report it ([UiBridge.previewOpen] and the others).
 */
data class PreviewPage(
    /** The page's address after redirects; a project file's is under the preview's own host. Null when nothing is open. */
    val url: String?,
    val title: String? = null,
    /** Why the page did not load, in the network's words; null when it did. */
    val failure: String? = null,
    /** The server's error status for the page itself, such as `HTTP 404`; null when there was none. */
    val httpError: String? = null,
    /** Still loading when the report was made. */
    val loading: Boolean = false,
    /** Console messages since the last report, oldest first. */
    val console: List<ConsoleLine> = emptyList(),
    /** How many messages before [console] were not kept. */
    val consoleDropped: Int = 0,
    /** The start of the page's visible text. */
    val text: String = "",
    /** The length of all of it. */
    val textLength: Int = 0,
    /** What the preview showed, when a picture was asked for and could be taken. */
    val screenshot: Block.Image? = null,
    /** Why there is no picture, when one was asked for. */
    val screenshotProblem: String? = null,
)

/** One console message: `error`, `warning`, `log`, `debug` or `tip`, and where in the page's code it came from. */
data class ConsoleLine(val level: String, val message: String, val source: String? = null, val line: Int = 0)

/** What a script run in the preview gave: its value as JSON or text, or its error. */
data class PreviewScript(
    val page: PreviewPage,
    val value: String? = null,
    val error: String? = null,
    /** It returned a Promise that had not settled when the wait was over. */
    val pending: Boolean = false,
)
