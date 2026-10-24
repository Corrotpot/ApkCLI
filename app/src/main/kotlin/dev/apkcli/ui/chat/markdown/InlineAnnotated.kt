package dev.apkcli.ui.chat.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

/** What each [InlineStyle] looks like, and what a path link does. Resolved once per theme. */
internal class InlineStyles(
    val code: SpanStyle,
    val link: TextLinkStyles,
    val mark: SpanStyle,
    /** Transcribed maths: told apart from prose by its family, not by a background. */
    val math: SpanStyle,
    /** Opens a project file; null leaves path links as text. */
    val onOpenPath: ((path: String, line: Int?) -> Unit)?,
) {
    fun spanFor(style: InlineStyle): SpanStyle = when (style) {
        InlineStyle.STRONG -> STRONG
        InlineStyle.EMPHASIS -> EMPHASIS
        InlineStyle.STRONG_EMPHASIS -> STRONG_EMPHASIS
        InlineStyle.STRIKE -> STRIKE
        InlineStyle.UNDERLINE -> UNDERLINE
        InlineStyle.CODE -> code
        InlineStyle.MARK -> mark
        InlineStyle.SUB -> SUB
        InlineStyle.SUP -> SUP
        InlineStyle.SMALL -> SMALL
        InlineStyle.MATH -> math
    }

    private companion object {
        val STRONG = SpanStyle(fontWeight = FontWeight.SemiBold)
        val EMPHASIS = SpanStyle(fontStyle = FontStyle.Italic)
        val STRONG_EMPHASIS = SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)
        val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)
        val UNDERLINE = SpanStyle(textDecoration = TextDecoration.Underline)
        val SUB = SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.8.em)
        val SUP = SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.8.em)
        val SMALL = SpanStyle(fontSize = 0.85.em)
    }
}

/** Inline Markdown as styled, clickable text. */
internal fun renderInline(text: String, styles: InlineStyles): AnnotatedString {
    val builder = AnnotatedString.Builder()
    MarkdownInline.parse(text, AnnotatedSink(builder, styles))
    return builder.toAnnotatedString()
}

private class AnnotatedSink(
    private val builder: AnnotatedString.Builder,
    private val styles: InlineStyles,
) : InlineSink {
    override val length: Int get() = builder.length

    override fun append(text: String) {
        builder.append(text)
    }

    override fun style(style: InlineStyle, start: Int, end: Int) {
        if (end > start) builder.addStyle(styles.spanFor(style), start, end)
    }

    override fun link(target: LinkTarget, start: Int, end: Int) {
        if (end <= start) return
        when (target) {
            is LinkTarget.Web -> builder.addLink(LinkAnnotation.Url(target.url, styles.link), start, end)
            is LinkTarget.Path -> {
                val open = styles.onOpenPath ?: return
                builder.addLink(
                    LinkAnnotation.Clickable("path:${target.path}", styles.link) { open(target.path, target.line) },
                    start,
                    end,
                )
            }
        }
    }
}
