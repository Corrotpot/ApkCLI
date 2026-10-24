package dev.apkcli.core.llm

/**
 * Separates reasoning that a model writes *into its answer* — `<think>…</think>`
 * — from the answer itself, as the text streams in.
 *
 * DeepSeek-style models served through generic OpenAI-compatible gateways do
 * this instead of using a `reasoning_content` field. Two shapes occur:
 *
 *  * `<think>reasoning</think>answer` — the tags are split off and the inside
 *    becomes reasoning.
 *  * `reasoning</think>answer`, with no opening tag at all, because the chat
 *    template put `<think>` in the *prompt*. Nothing marks the reasoning until
 *    the closing tag arrives, so by then it has been streamed as answer text;
 *    [Piece.EarlierTextWasThinking] tells the consumer to move it.
 *
 * Tags split across chunks (`</thi` + `nk>`) are held back until they can be
 * recognised, so a fragment of one never flashes up in the answer.
 */
class ThinkTagSplitter {

    sealed interface Piece {
        data class Text(val text: String) : Piece

        data class Thinking(val text: String) : Piece

        /** A closing tag with no opening one: all text emitted so far was reasoning. */
        data object EarlierTextWasThinking : Piece
    }

    private var inThinking = false
    private var closedOnce = false

    /** Drop the whitespace that follows a tag: `</think>\n\nAnswer` starts at "Answer". */
    private var trimLeading = false
    private val carry = StringBuilder()

    fun accept(chunk: String): List<Piece> {
        if (chunk.isEmpty()) return emptyList()
        carry.append(chunk)
        var text = carry.toString()
        carry.setLength(0)

        val out = ArrayList<Piece>(2)
        while (true) {
            val found = findTag(text) ?: break
            emitRun(text.substring(0, found.index), out)
            when (found.tag.closing) {
                false -> if (!inThinking) {
                    inThinking = true
                    trimLeading = true
                }
                true -> {
                    if (inThinking) {
                        inThinking = false
                    } else if (!closedOnce) {
                        out += Piece.EarlierTextWasThinking
                    }
                    closedOnce = true
                    trimLeading = true
                }
            }
            text = text.substring(found.index + found.tag.text.length)
        }

        val keep = partialTagSuffix(text)
        emitRun(text.substring(0, text.length - keep), out)
        carry.append(text, text.length - keep, text.length)
        return out
    }

    /** End of the stream: whatever was held back is ordinary text after all. */
    fun finish(): List<Piece> {
        val out = ArrayList<Piece>(1)
        emitRun(carry.toString(), out)
        carry.setLength(0)
        return out
    }

    private fun emitRun(run: String, out: MutableList<Piece>) {
        var s = run
        if (trimLeading) {
            s = s.trimStart()
            if (s.isEmpty()) return
            trimLeading = false
        }
        if (s.isEmpty()) return
        out += if (inThinking) Piece.Thinking(s) else Piece.Text(s)
    }

    private class Tag(val text: String, val closing: Boolean)

    private class Found(val index: Int, val tag: Tag)

    private fun findTag(text: String): Found? {
        var from = 0
        while (true) {
            val lt = text.indexOf('<', from)
            if (lt < 0) return null
            TAGS.firstOrNull { text.regionMatches(lt, it.text, 0, it.text.length, ignoreCase = true) }
                ?.let { return Found(lt, it) }
            from = lt + 1
        }
    }

    /** Length of the longest tail of [text] that could still grow into a tag. */
    private fun partialTagSuffix(text: String): Int {
        val longest = minOf(text.length, MAX_TAG - 1)
        for (length in longest downTo 1) {
            val start = text.length - length
            if (text[start] != '<') continue
            if (TAGS.any { it.text.regionMatches(0, text, start, length, ignoreCase = true) }) return length
        }
        return 0
    }

    /** Result of [split]. */
    data class Split(val thinking: String, val text: String)

    companion object {
        private val TAGS = listOf(
            Tag("<think>", closing = false),
            Tag("</think>", closing = true),
            Tag("<thinking>", closing = false),
            Tag("</thinking>", closing = true),
        )
        private val MAX_TAG = TAGS.maxOf { it.text.length }

        /** Split finished text, for messages stored before this existed. */
        fun split(text: String): Split {
            if ('<' !in text) return Split("", text)
            val splitter = ThinkTagSplitter()
            val thinking = StringBuilder()
            val answer = StringBuilder()
            for (piece in splitter.accept(text) + splitter.finish()) {
                when (piece) {
                    is Piece.Text -> answer.append(piece.text)
                    is Piece.Thinking -> thinking.append(piece.text)
                    Piece.EarlierTextWasThinking -> {
                        thinking.append(answer)
                        answer.setLength(0)
                    }
                }
            }
            return Split(thinking.toString().trim(), answer.toString())
        }
    }
}
