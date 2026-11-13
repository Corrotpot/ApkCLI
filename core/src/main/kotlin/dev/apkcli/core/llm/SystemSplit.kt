package dev.apkcli.core.llm

/**
 * A request's system prompt in its two parts (`SystemPrompt.Parts`), as every
 * adapter sends it: [stable], the same on every request of the session, where
 * the system prompt goes; [volatile] — the window's fill, the open files, the
 * terminals — after the conversation, with the newest message, as the app's
 * words ([appContext]).
 *
 * A provider's prompt cache matches a request's prefix: OpenAI's, DeepSeek's
 * and Gemini's by themselves, Anthropic's at the breakpoints the request sets,
 * Kiro's by itself. Sent at the head, the part that changes made every request
 * differ from the last right after the stable part, so no cache reached the
 * conversation, and a long chat was read again in full on every request.
 */
internal class SystemSplit(request: CompletionRequest) {
    private val at = request.systemStableLength.takeIf { it in 0..request.system.length } ?: request.system.length

    val stable: String = request.system.substring(0, at).trim()
    val volatile: String = request.system.substring(at).trim()

    /** [volatile] as it goes with the newest message; null when there is none. */
    val appContext: String? get() = volatile.takeIf { it.isNotBlank() }?.let(::appContext)
}

/**
 * The per-request part of the system prompt, as the newest message carries it:
 * marked as the app's, so it is not read as the user's words.
 */
internal fun appContext(text: String): String =
    "<app-context>\nSent by the app with this request, not written by the user.\n\n$text\n</app-context>"
