package dev.apkcli.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import dev.apkcli.ui.theme.EditorColors

/**
 * What a run of characters is, for colouring. The roles are VS Code Dark
 * Modern's, which is what the design's syntax tokens are: declaration
 * keywords and control flow differ in colour, literals in a third, and a
 * capitalised name reads as a type.
 */
internal enum class TokenKind {
    COMMENT, STRING, NUMBER, KEYWORD, CONTROL, CONSTANT, VARIABLE, TYPE, FUNCTION, ANNOTATION, ATTRIBUTE,
}

/** Keywords that steer control flow, in whichever language lists them. */
private val CONTROL_WORDS = setOf(
    "if", "else", "elif", "when", "for", "while", "do", "until", "loop", "return", "break", "continue",
    "throw", "raise", "try", "catch", "except", "finally", "switch", "case", "default", "match", "yield",
    "await", "goto", "pass", "with", "in", "is", "not", "and", "or", "del", "assert", "then", "fi", "done",
    "esac", "defer", "go", "select", "fallthrough", "range",
)

/** Literal names: booleans and the null of each language. */
private val CONSTANT_WORDS = setOf(
    "true", "false", "null", "True", "False", "None", "nil", "undefined", "NULL", "nullptr", "iota",
    "yes", "no", "on", "off",
)

internal class LexToken(val start: Int, val end: Int, val kind: TokenKind)

/**
 * The lexer behind every piece of highlighted code: the editor (through
 * [HighlightedLanguage]), diffs in tool cards, and code blocks in chat. One
 * implementation, so a Kotlin file looks the same in all three.
 *
 * Line by line, carrying only "inside a block comment" from one line to the
 * next. That is what lets a diff, which shows scattered lines, be coloured
 * without the rest of the file.
 */
internal object SyntaxLexer {

    /** Lex one line into [out]. Returns whether a block comment is still open at its end. */
    fun lexLine(syntax: Syntax, line: String, startsInBlock: Boolean, out: MutableList<LexToken>): Boolean {
        var i = 0
        val n = line.length
        val block = syntax.blockComment

        if (startsInBlock && block != null) {
            val close = line.indexOf(block.second)
            if (close < 0) {
                if (n > 0) out += LexToken(0, n, TokenKind.COMMENT)
                return true
            }
            out += LexToken(0, close + block.second.length, TokenKind.COMMENT)
            i = close + block.second.length
        }

        while (i < n) {
            val c = line[i]

            if (block != null && line.startsWith(block.first, i)) {
                val close = line.indexOf(block.second, i + block.first.length)
                if (close < 0) {
                    out += LexToken(i, n, TokenKind.COMMENT)
                    return true
                }
                val end = close + block.second.length
                out += LexToken(i, end, TokenKind.COMMENT)
                i = end
                continue
            }

            if (syntax.lineComments.any { line.startsWith(it, i) } && !(c == '#' && syntax.markup)) {
                out += LexToken(i, n, TokenKind.COMMENT)
                return false
            }

            if (c in syntax.quotes) {
                val triple = syntax.tripleQuotes && line.startsWith("$c$c$c", i)
                val end = if (triple) {
                    line.indexOf("$c$c$c", i + 3).let { if (it < 0) n else it + 3 }
                } else {
                    stringEnd(line, i, c)
                }
                out += LexToken(i, end, TokenKind.STRING)
                i = end
                continue
            }

            if (syntax.markup && c == '<') {
                var j = i + 1
                if (j < n && (line[j] == '/' || line[j] == '?' || line[j] == '!')) j++
                val nameStart = j
                while (j < n && (line[j].isLetterOrDigit() || line[j] in ":-_.")) j++
                if (j > nameStart) out += LexToken(nameStart, j, TokenKind.KEYWORD)
                i = j
                continue
            }

            if (c.isDigit() && (i == 0 || !line[i - 1].isLetterOrDigit() && line[i - 1] != '_')) {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_')) j++
                out += LexToken(i, j, TokenKind.NUMBER)
                i = j
                continue
            }

            if (syntax.annotations && c == '@' && i + 1 < n && line[i + 1].isLetter()) {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '.')) j++
                out += LexToken(i, j, TokenKind.ANNOTATION)
                i = j
                continue
            }

            if (c.isLetter() || c == '_' || c == '$') {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                val word = line.substring(i, j)
                when {
                    word in syntax.variables -> out += LexToken(i, j, TokenKind.VARIABLE)
                    word in syntax.keywords && word in CONSTANT_WORDS -> out += LexToken(i, j, TokenKind.CONSTANT)
                    word in syntax.keywords && word in CONTROL_WORDS -> out += LexToken(i, j, TokenKind.CONTROL)
                    word in syntax.keywords -> out += LexToken(i, j, TokenKind.KEYWORD)
                    !syntax.markup && nextNonSpace(line, j) == '(' -> out += LexToken(i, j, TokenKind.FUNCTION)
                    syntax.markup && nextNonSpace(line, j) == '=' -> out += LexToken(i, j, TokenKind.ATTRIBUTE)
                    // A capitalised name is a type in every language listed
                    // here; in markup it is just text.
                    !syntax.markup && word[0].isUpperCase() && syntax.keywords.isNotEmpty() ->
                        out += LexToken(i, j, TokenKind.TYPE)
                }
                i = j
                continue
            }

            i++
        }
        return false
    }

    private fun stringEnd(line: String, start: Int, quote: Char): Int {
        var j = start + 1
        while (j < line.length) {
            when (line[j]) {
                '\\' -> j += 2
                quote -> return j + 1
                else -> j++
            }
        }
        return line.length
    }

    private fun nextNonSpace(line: String, from: Int): Char? {
        var j = from
        while (j < line.length && line[j] == ' ') j++
        return line.getOrNull(j)
    }
}

/** Colours for highlighted code outside the editor widget. */
internal class SyntaxPalette(
    val text: Color,
    private val colors: EditorColors,
) {
    fun colorOf(kind: TokenKind): Color = when (kind) {
        TokenKind.COMMENT -> colors.syntaxComment
        TokenKind.STRING -> colors.syntaxString
        TokenKind.NUMBER -> colors.syntaxNumber
        TokenKind.KEYWORD -> colors.syntaxKeyword
        TokenKind.CONTROL -> colors.syntaxControl
        TokenKind.CONSTANT -> colors.syntaxConstant
        TokenKind.VARIABLE -> colors.syntaxVariable
        TokenKind.TYPE -> colors.syntaxType
        TokenKind.FUNCTION, TokenKind.ANNOTATION -> colors.syntaxFunction
        TokenKind.ATTRIBUTE -> colors.syntaxVariable
    }

    companion object {
        /** [text] is the colour of everything not tokenised; the design's is `syntax.plain`. */
        fun of(colors: EditorColors, text: Color = colors.syntaxPlain) = SyntaxPalette(text, colors)
    }
}

/**
 * Highlight [lines] as consecutive lines of one file. Plain text when [syntax]
 * is null. [startsInBlock] says whether the first line opens inside a block
 * comment, which a diff hunk cannot know and guesses as false.
 */
internal fun highlightLines(
    lines: List<String>,
    syntax: Syntax?,
    palette: SyntaxPalette,
    startsInBlock: Boolean = false,
): List<AnnotatedString> {
    if (syntax == null) return lines.map { AnnotatedString(it) }
    var inBlock = startsInBlock
    val tokens = ArrayList<LexToken>()
    return lines.map { line ->
        tokens.clear()
        // Long generated lines are drawn plain: lexing a minified bundle per
        // frame is all cost and no reading benefit.
        if (line.length > MAX_HIGHLIGHT_LINE) return@map AnnotatedString(line)
        inBlock = SyntaxLexer.lexLine(syntax, line, inBlock, tokens)
        buildAnnotatedString {
            append(line)
            for (token in tokens) {
                val end = token.end.coerceAtMost(line.length)
                if (token.start >= end) continue
                // Upright comments, as Dark Modern draws them.
                addStyle(SpanStyle(color = palette.colorOf(token.kind)), token.start, end)
            }
        }
    }
}

private const val MAX_HIGHLIGHT_LINE = 2_000
