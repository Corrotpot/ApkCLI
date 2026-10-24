package dev.apkcli.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexMathTest {

    private fun t(source: String) = LatexMath.translate(source)

    @Test
    fun `symbols and Greek become their characters`() {
        assertEquals("α → ∞", t("""\alpha \to \infty"""))
        assertEquals("A → B", t("""A \rightarrow B"""))
        assertEquals("x ≤ y ≠ z", t("""x \leq y \neq z"""))
        assertEquals("∀ ε > 0 ∃ δ", t("""\forall \epsilon > 0 \exists \delta"""))
        assertEquals("ℝ ⊂ ℂ", t("""\mathbb{R} \subset \mathbb{C}"""))
        // Spacing is the author's: LaTeX would set πr², but a transcription
        // that glues names together is worse than one space too many.
        assertEquals("π r²", t("""\pi r^2"""))
        assertEquals("lim x", t("""\lim x"""))
    }

    @Test
    fun `scripts use Unicode when every character has a form`() {
        assertEquals("E = mc²", t("E = mc^2"))
        assertEquals("x₁ + x₂", t("x_1 + x_2"))
        assertEquals("∑ᵢ₌₁ⁿ i", t("""\sum_{i=1}^{n} i"""))
        assertEquals("∫₀¹ f", t("""\int_0^1 f"""))
        assertEquals("aⁱ⁺ʲ", t("a^{i+j}"))
        // No superscript q: said in brackets rather than losing the script.
        assertEquals("x^(q+1)", t("x^{q+1}"))
        assertEquals("lim_(n → ∞)", t("""\lim_{n \to \infty}"""))
    }

    @Test
    fun `a fraction is a division, bracketed only where it has to be`() {
        assertEquals("n(n+1)/2", t("""\frac{n(n+1)}{2}"""))
        assertEquals("(a+b)/c", t("""\frac{a+b}{c}"""))
        assertEquals("½", t("""\frac{1}{2}"""))
        assertEquals("½", t("""\frac12"""))
        assertEquals("√2/2", t("""\frac{\sqrt{2}}{2}"""))
        // -1/n and (-1)/n are the same number; the shorter one wins.
        assertEquals("-1/n", t("""\frac{-1}{n}"""))
        assertEquals("∛x", t("""\sqrt[3]{x}"""))
        assertEquals("√(a+b)", t("""\sqrt{a+b}"""))
    }

    @Test
    fun `the whole sum formula reads as one line`() {
        assertEquals("∑ᵢ₌₁ⁿ i = n(n+1)/2", t("""\sum_{i=1}^{n} i = \frac{n(n+1)}{2}"""))
        assertEquals("O(n log n)", t("""O(n \log n)"""))
    }

    @Test
    fun `delimiters, text and spacing commands are dropped but their content stays`() {
        assertEquals("(x)", t("""\left( x \right)"""))
        assertEquals("|x|", t("""\left| x \right|"""))
        assertEquals("{1, 2}", t("""\left\{ 1, 2 \right\}"""))
        assertEquals("f(x) if x > 0", t("""f(x) \quad \text{if } x > 0"""))
        // `\left.` is "no bracket on this side".
        assertEquals("x⟩", t("""\left. x \right\rangle"""))
    }

    @Test
    fun `rows of an environment become lines`() {
        assertEquals("a b\nc d", t("""\begin{pmatrix} a & b \\ c & d \end{pmatrix}"""))
        assertEquals("x if x ≥ 0\n-x otherwise", t("""\begin{cases} x & \text{if } x \ge 0 \\ -x & \text{otherwise} \end{cases}"""))
    }

    @Test
    fun `an unknown command is left visible rather than dropped`() {
        assertEquals("""\unknownthing{x}""", t("""\unknownthing{x}"""))
        assertEquals("""x \oddcmd y""", t("""x \oddcmd y"""))
    }

    @Test
    fun `broken and streaming input comes back without throwing`() {
        assertEquals("", t(""))
        assertEquals("a", t("""\frac{a}{"""))
        assertEquals("x", t("x^"))
        assertEquals("α", t("""\alpha}"""))
        assertEquals("", t("""\frac"""))
        // Deep nesting stops recursing instead of overflowing the stack.
        val deep = "{".repeat(200) + "x" + "}".repeat(200)
        assertTrue("x" in t(deep))
    }

    @Test
    fun `money and shell variables are not formulas`() {
        assertFalse(LatexMath.looksLikeMath("5 to $7"))
        assertFalse(LatexMath.looksLikeMath("PREFIX"))
        assertFalse(LatexMath.looksLikeMath("the total cost of it"))
        assertFalse(LatexMath.looksLikeMath(" x "))
        assertFalse(LatexMath.looksLikeMath(""))
    }

    @Test
    fun `expressions are formulas`() {
        assertTrue(LatexMath.looksLikeMath("x"))
        assertTrue(LatexMath.looksLikeMath("E = mc^2"))
        assertTrue(LatexMath.looksLikeMath("""\alpha"""))
        assertTrue(LatexMath.looksLikeMath("x > 0"))
        assertTrue(LatexMath.looksLikeMath("a_1"))
    }
}
