package dev.apkcli.core.agent.tools

import dev.apkcli.core.runtime.ExecEcho
import dev.apkcli.core.runtime.TerminalBuffer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The live view of a tool that is not a command and so has no terminal of
 * its own — the package installer is the app's code, not apt — but prints
 * what apt would: the lines already done, and the line in hand rewritten as
 * it advances, the way a terminal shows a download.
 *
 * Updates may come from any thread and as often as once per 64 KB received.
 * Snapshots go to [post] from a ticker, at most every [intervalMs], so a burst
 * of updates costs one post and a long step still shows its latest state.
 */
internal class LiveLog(
    private val post: (List<TerminalBuffer.Row>) -> Unit,
    private val keep: Int = 12,
    private val intervalMs: Long = 250,
    /** The same lines in the chat's "Agent" terminal, the status line redrawn in place. */
    private val echo: ExecEcho? = null,
) {
    private val lines = ArrayDeque<String>()
    private var current: String? = null
    private var dirty = false
    private var echoedStatusAt = 0L

    /** A finished step; it stays in view. */
    @Synchronized
    fun line(text: String) {
        current = null
        lines.addLast(text)
        while (lines.size > keep) lines.removeFirst()
        dirty = true
        echo?.output("\r$ERASE_LINE$text\r\n")
    }

    /** The step in hand, replacing the previous status. */
    @Synchronized
    fun status(text: String) {
        if (text != current) {
            current = text
            dirty = true
            // A download reports every 64 KB; the terminal needs a few a second.
            val now = System.currentTimeMillis()
            if (echo != null && now - echoedStatusAt >= intervalMs) {
                echoedStatusAt = now
                echo.output("\r$ERASE_LINE$text")
            }
        }
    }

    private companion object {
        const val ERASE_LINE = "\u001b[K"
    }

    @Synchronized
    private fun snapshot(): List<TerminalBuffer.Row>? {
        if (!dirty) return null
        dirty = false
        return (lines + listOfNotNull(current)).map { TerminalBuffer.Row(it, emptyList()) }
    }

    /** Run [block] while the log is posted. */
    suspend fun <T> around(block: suspend () -> T): T = coroutineScope {
        val ticker = launch {
            while (true) {
                snapshot()?.let(post)
                delay(intervalMs)
            }
        }
        try {
            block()
        } finally {
            ticker.cancel()
        }
    }
}

/**
 * [block] with a [LiveLog] posting to [onOutput], or with none where nobody
 * is watching. With an [echo], the work also shows in the chat's "Agent"
 * terminal as [title] would at a prompt, run in [cwd].
 */
internal suspend fun <T> withLiveLog(
    onOutput: ((List<TerminalBuffer.Row>) -> Unit)?,
    echo: ExecEcho? = null,
    title: String = "",
    cwd: String = "",
    block: suspend (LiveLog?) -> T,
): T {
    if (onOutput == null && echo == null) return block(null)
    val log = LiveLog(onOutput ?: {}, echo = echo)
    val started = System.currentTimeMillis()
    echo?.begin(title, cwd, null)
    var failed = true
    var stopped = false
    try {
        return log.around { block(log) }.also { failed = false }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) {
            stopped = true
        } else {
            // Why it failed, where the user is watching it fail.
            echo?.output("\r\u001b[K" + (t.message ?: t.javaClass.simpleName).replace("\n", "\r\n") + "\r\n")
        }
        throw t
    } finally {
        val exit = when {
            stopped -> null
            failed -> 1
            else -> 0
        }
        echo?.end(exit, null, false, System.currentTimeMillis() - started)
    }
}
