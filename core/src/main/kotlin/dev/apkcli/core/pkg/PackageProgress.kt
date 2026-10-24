package dev.apkcli.core.pkg

/**
 * How far a package operation has got, for the live view under its tool call.
 *
 * Two kinds of line, the way a terminal shows a download: a [step] is done
 * and stays in view; the [status] line is the step in hand, rewritten as it
 * advances (bytes downloaded, files unpacked). Called from the worker thread
 * and often, once per 64 KB downloaded: implementations must be cheap and
 * thread-safe.
 */
interface PackageProgress {
    fun step(line: String)

    fun status(line: String)

    companion object {
        val NONE: PackageProgress = object : PackageProgress {
            override fun step(line: String) = Unit

            override fun status(line: String) = Unit
        }
    }
}
