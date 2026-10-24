package dev.apkcli.core.text

/**
 * LaTeX maths as readable Unicode.
 *
 * Models write formulas in LaTeX whether or not they were asked to, and a
 * phone has nowhere to put a typesetting engine: KaTeX would mean a WebView
 * per formula, a native one glyph metrics, boxes and glue for something that
 * appears a few times a session. So a formula is *transcribed* rather than
 * typeset — `\frac{n(n+1)}{2}` becomes `n(n+1)/2`, `\sum_{i=1}^{n}` becomes
 * `∑ᵢ₌₁ⁿ`, `\alpha \to \infty` becomes `α → ∞` — which is roughly what a
 * person reading the formula aloud would say, and which can be selected and
 * copied like the rest of the reply.
 *
 * Deliberate limits, visible rather than hidden:
 *
 *  * Superscripts and subscripts use Unicode where every character has a
 *    Unicode form, and `^(…)` / `_(…)` where one does not: `x^{2}` is `x²`,
 *    but `x^{q+1}` is `x^(q+1)`, because there is no superscript `q`.
 *  * A fraction is a division, bracketed where dividing without brackets
 *    would change it. Stacked layout is the one thing transcription cannot do.
 *  * A command this does not know stays as it was written, backslash and all,
 *    so an unsupported formula reads mostly right and shows which part was
 *    not understood instead of quietly dropping it.
 *  * Matrices and `cases` become their rows, one per line.
 *
 * Pure text, no Android, no Compose: `MarkdownParser` finds the maths and
 * this translates it.
 */
object LatexMath {

    /** [source] transcribed. Never throws; unknown input comes back close to as it went in. */
    fun translate(source: String): String = Translator(source).run()

    /**
     * Whether `$…$` around [body] was meant as maths rather than as money or
     * a shell variable.
     *
     * A LaTeX command, a script or a group is proof. Without one the body has
     * to be short and read as an expression — a letter with an operator
     * (`$x > 0$`) or a lone symbol (`$n$`). "from $5 to $7" has neither and
     * stays text, and so does a sentence that happens to sit between two
     * prices.
     */
    fun looksLikeMath(body: String): Boolean {
        if (body.isBlank() || body.length > MAX_INLINE) return false
        if (body.any { it == '\\' || it == '^' || it == '_' || it == '{' }) return true
        val letters = body.count { it.isLetter() }
        if (letters == 0) return false
        if (body.length <= 3 && body.all { it.isLetterOrDigit() || it in "'′" }) return true
        // Mostly symbols and a few names, not prose with a number in it.
        return body.any { it in OPERATORS } && letters <= body.length / 2 + 1
    }

    /** Longest `$…$` body considered when there is no command in it. */
    private const val MAX_INLINE = 48

    private const val OPERATORS = "=+-*/<>≤≥≠≈·×÷±∓→←↔∈∉⊂⊃∪∩"

    /** Nesting past this is appended as it stands rather than recursed into. */
    private const val MAX_DEPTH = 24

    private class Translator(private val s: String, private val depth: Int = 0) {
        private val out = StringBuilder()
        private var i = 0

        fun run(): String {
            while (i < s.length) {
                when (val c = s[i]) {
                    '\\' -> command()
                    '^' -> script(superscript = true)
                    '_' -> script(superscript = false)
                    '{' -> out.append(translate(group()))
                    // A brace or a dollar with nothing to match is noise from
                    // a formula that is still streaming in.
                    '}', '$' -> i++
                    // Alignment inside an environment: a gap, not a character.
                    '&' -> {
                        out.append(' ')
                        i++
                    }
                    '~' -> {
                        out.append('\u00A0')
                        i++
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            // Alignment gaps and sizing commands leave runs of spaces behind;
            // one space is all a transcription can mean by them.
            return out.lineSequence()
                .map { line -> SPACES.replace(line, " ").trim() }
                .joinToString("\n")
                .trim('\n')
        }

        private fun translate(part: String): String =
            if (depth >= MAX_DEPTH) part else Translator(part, depth + 1).run()

        /* --------------------------------------------------------- pieces */

        /**
         * The next argument: a braced group, a whole command, or one
         * character. `\frac12`, `\frac{1}{2}` and `\frac\alpha2` all work.
         */
        private fun argument(): String {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return ""
            if (s[i] == '{') return group()
            if (s[i] == '\\') {
                val start = i
                i++
                if (i < s.length && s[i].isLetter()) {
                    while (i < s.length && s[i].isLetter()) i++
                } else if (i < s.length) {
                    i++
                }
                return s.substring(start, i)
            }
            return s[i].toString().also { i++ }
        }

        /** The contents of the group at [i], consuming it. Unclosed: the rest of the input. */
        private fun group(): String {
            if (i >= s.length || s[i] != '{') return ""
            var level = 0
            var k = i
            while (k < s.length) {
                when (s[k]) {
                    '\\' -> k++
                    '{' -> level++
                    '}' -> {
                        level--
                        if (level == 0) {
                            val inner = s.substring(i + 1, k)
                            i = k + 1
                            return inner
                        }
                    }
                }
                k++
            }
            val inner = s.substring(i + 1)
            i = s.length
            return inner
        }

        /** An optional `[…]` argument, as `\sqrt[3]{x}` has. */
        private fun option(): String? {
            if (i >= s.length || s[i] != '[') return null
            val end = s.indexOf(']', i + 1)
            if (end < 0) return null
            val inner = s.substring(i + 1, end)
            i = end + 1
            return inner
        }

        /* ------------------------------------------------------- commands */

        /**
         * A named command. Spacing is the author's: LaTeX drops the space
         * after a command's name, but `α → ∞` reads better than `α→∞` and
         * `lim x` has to keep its gap, so a space in the source stays one
         * here and the main loop appends it like any other character.
         */
        private fun command() {
            i++ // the backslash
            if (i >= s.length) return
            if (!s[i].isLetter()) {
                shortCommand()
                return
            }
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(start, i)

            val replacement = word(name)
            if (replacement == null) {
                // Not understood: left as written, braces and all, so it is
                // clear which part of the formula this could not read.
                out.append('\\').append(name)
                if (i < s.length && s[i] == '{') {
                    out.append('{').append(translate(group())).append('}')
                }
                return
            }
            out.append(replacement)
            if (pendingSkipSpaces) {
                pendingSkipSpaces = false
                while (i < s.length && s[i] == ' ') i++
            }
        }

        /** `\\`, `\,`, `\%`: a command whose name is one character. */
        private fun shortCommand() {
            val c = s[i]
            i++
            when (c) {
                '\\' -> out.append('\n')
                ',', ':', ';', ' ' -> out.append(' ')
                '!' -> Unit // a negative space has nothing to draw
                else -> out.append(c)
            }
        }

        /** What a named command becomes, or null when it is unknown. */
        private fun word(name: String): String? = when (name) {
            "frac", "dfrac", "tfrac", "cfrac" -> fraction(translate(argument()), translate(argument()))
            "binom", "dbinom", "tbinom" -> "C(${translate(argument())}, ${translate(argument())})"
            "sqrt" -> root(option(), translate(argument()))
            // A face this cannot draw: the text is what matters.
            "text", "textrm", "textit", "textbf", "textsf", "texttt", "mathrm", "mathit",
            "mathsf", "mathtt", "mathbf", "mathfrak", "bm", "boldsymbol", "operatorname",
            "mbox", "hbox", "underline", "overbrace", "underbrace",
            -> translate(argument())
            "mathbb" -> mapLetters(translate(argument()), DOUBLE_STRUCK)
            "mathcal", "mathscr" -> mapLetters(translate(argument()), SCRIPT)
            // Sizing and matched delimiters: the delimiter itself is kept.
            "left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr",
            "biggl", "biggr", "Biggl", "Biggr", "middle",
            -> delimiter(name)
            "begin", "end" -> environment(begin = name == "begin")
            // Width this cannot draw: one space, since runs are collapsed.
            "quad", "qquad" -> " "
            "thinspace", "enspace", "medspace", "thickspace", "space" -> " "
            "hspace", "vspace", "phantom", "hphantom", "vphantom" -> {
                argument()
                " "
            }
            "limits", "nolimits", "displaystyle", "textstyle", "scriptstyle", "nonumber", "notag" -> ""
            "label", "tag" -> {
                argument()
                ""
            }
            // An accent is drawn over its argument, which a combining mark does too.
            "hat", "widehat", "bar", "overline", "vec", "tilde", "widetilde",
            "dot", "ddot", "check", "breve",
            -> translate(argument()) + ACCENTS.getValue(name)
            "pmod" -> "(mod ${translate(argument())})"
            "bmod", "mod" -> "mod"
            else -> SYMBOLS[name] ?: FUNCTIONS[name]
        }

        /**
         * The delimiter a `\left`-style command is about to size.
         *
         * A delimiter sits against what it encloses, so the space the author
         * wrote beside it goes: `\left( x \right)` is `(x)`, not `( x )`.
         * Which side to close up follows from the bracket itself, since
         * `\big(` says nothing about direction; `|` closes up on both.
         */
        private fun delimiter(command: String): String {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return ""
            val mark = when {
                // `\left.` and `\right.` mean "no bracket on this side".
                s[i] == '.' -> {
                    i++
                    ""
                }
                s[i] == '\\' -> {
                    i++
                    if (i < s.length && s[i].isLetter()) {
                        val start = i
                        while (i < s.length && s[i].isLetter()) i++
                        val name = s.substring(start, i)
                        SYMBOLS[name] ?: "\\$name"
                    } else if (i < s.length) {
                        s[i].toString().also { i++ }
                    } else {
                        ""
                    }
                }
                else -> s[i].toString().also { i++ }
            }
            val opens = mark.isEmpty() || command.endsWith("l") || mark.all { it in OPENING } ||
                mark.none { it in OPENING || it in CLOSING }
            val closes = mark.isEmpty() || command.endsWith("r") || mark.all { it in CLOSING } ||
                mark.none { it in OPENING || it in CLOSING }
            if (closes) while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
            if (opens) {
                // Skipped after the delimiter is appended by the caller.
                pendingSkipSpaces = true
            }
            return mark
        }

        /** Set by [delimiter] for an opening bracket: the spaces after it are not kept. */
        private var pendingSkipSpaces = false

        /**
         * `\begin{…}` / `\end{…}`. The environment's name goes and its rows
         * stay: `\\` is already a newline and `&` already a gap. A leading or
         * trailing one is trimmed off the result.
         */
        private fun environment(begin: Boolean): String {
            val name = argument().trim().removeSuffix("*")
            // `array` and `tabular` carry a column spec nobody reads.
            if (begin && name in COLUMN_SPEC_ENVIRONMENTS) argument()
            return "\n"
        }

        /* -------------------------------------------------- super and sub */

        private fun script(superscript: Boolean) {
            i++ // ^ or _
            val body = translate(argument())
            val table = if (superscript) SUPERSCRIPT else SUBSCRIPT
            val small = StringBuilder()
            var complete = body.isNotEmpty()
            for (c in body) {
                val mapped = table[c]
                if (mapped == null) {
                    complete = false
                    break
                }
                small.append(mapped)
            }
            when {
                complete -> out.append(small)
                // No Unicode form for part of it: said in brackets rather
                // than dropping the difference between x^2 and x2.
                body.isEmpty() -> Unit
                else -> {
                    out.append(if (superscript) '^' else '_')
                    if (body.length == 1) out.append(body) else out.append('(').append(body).append(')')
                }
            }
        }

        /* ------------------------------------------------------ fractions */

        private fun fraction(top: String, bottom: String): String {
            // Half-written, still streaming in: the part there is, with no
            // division sign, so `\frac{a}{` reads as `a` and then as `a/b`.
            if (top.isBlank()) return bottom.trim()
            if (bottom.isBlank()) return top.trim()
            VULGAR[top.trim() to bottom.trim()]?.let { return it }
            return bracket(top) + "/" + bracket(bottom)
        }

        /** A part of a fraction gets brackets when dividing it without them would change it. */
        private fun bracket(part: String): String {
            val text = part.trim()
            if (text.length <= 1) return text
            var level = 0
            for ((index, c) in text.withIndex()) {
                when (c) {
                    '(', '[', '{', '⟨' -> level++
                    ')', ']', '}', '⟩' -> level--
                    // A leading sign belongs to the number after it.
                    else -> if (level == 0 && index > 0 && c in "+-−±∓/·×÷ ") return "($text)"
                }
            }
            return text
        }

        private fun root(index: String?, body: String): String = when (val n = index?.trim()) {
            null, "", "2" -> "√" + bracket(body)
            "3" -> "∛" + bracket(body)
            "4" -> "∜" + bracket(body)
            else -> {
                val small = n.mapNotNull { SUPERSCRIPT[it] }.joinToString("")
                (if (small.length == n.length) small else "") + "√" + bracket(body)
            }
        }

        private fun mapLetters(body: String, table: Map<Char, String>): String =
            body.map { table[it] ?: it.toString() }.joinToString("")
    }

    private val SPACES = Regex(" {2,}")

    private const val OPENING = "([{⟨⌈⌊"
    private const val CLOSING = ")]}⟩⌉⌋"

    private val COLUMN_SPEC_ENVIRONMENTS = setOf("array", "tabular")

    private val VULGAR = mapOf(
        ("1" to "2") to "½", ("1" to "3") to "⅓", ("2" to "3") to "⅔",
        ("1" to "4") to "¼", ("3" to "4") to "¾", ("1" to "5") to "⅕",
        ("2" to "5") to "⅖", ("3" to "5") to "⅗", ("4" to "5") to "⅘",
        ("1" to "6") to "⅙", ("5" to "6") to "⅚", ("1" to "7") to "⅐",
        ("1" to "8") to "⅛", ("3" to "8") to "⅜", ("5" to "8") to "⅝",
        ("7" to "8") to "⅞", ("1" to "9") to "⅑", ("1" to "10") to "⅒",
    )

    private val ACCENTS = mapOf(
        "hat" to "\u0302", "widehat" to "\u0302", "bar" to "\u0304", "overline" to "\u0304",
        "vec" to "\u20D7", "tilde" to "\u0303", "widetilde" to "\u0303",
        "dot" to "\u0307", "ddot" to "\u0308", "check" to "\u030C", "breve" to "\u0306",
    )

    /** Operator names LaTeX sets upright; the word itself is the answer. */
    private val FUNCTIONS = listOf(
        "lim", "limsup", "liminf", "log", "ln", "lg", "exp", "sin", "cos", "tan", "cot", "sec", "csc",
        "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "coth", "max", "min", "sup", "inf",
        "det", "dim", "deg", "gcd", "arg", "ker", "hom", "Pr",
    ).associateWith { it }

    private val SUPERSCRIPT = mapOf(
        '0' to "⁰", '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴", '5' to "⁵", '6' to "⁶", '7' to "⁷",
        '8' to "⁸", '9' to "⁹", '+' to "⁺", '-' to "⁻", '−' to "⁻", '=' to "⁼", '(' to "⁽", ')' to "⁾",
        'a' to "ᵃ", 'b' to "ᵇ", 'c' to "ᶜ", 'd' to "ᵈ", 'e' to "ᵉ", 'f' to "ᶠ", 'g' to "ᵍ", 'h' to "ʰ",
        'i' to "ⁱ", 'j' to "ʲ", 'k' to "ᵏ", 'l' to "ˡ", 'm' to "ᵐ", 'n' to "ⁿ", 'o' to "ᵒ", 'p' to "ᵖ",
        'r' to "ʳ", 's' to "ˢ", 't' to "ᵗ", 'u' to "ᵘ", 'v' to "ᵛ", 'w' to "ʷ", 'x' to "ˣ", 'y' to "ʸ",
        'z' to "ᶻ", 'T' to "ᵀ", 'β' to "ᵝ", 'γ' to "ᵞ", 'δ' to "ᵟ", 'θ' to "ᶿ", 'φ' to "ᵠ", 'χ' to "ᵡ",
    )

    private val SUBSCRIPT = mapOf(
        '0' to "₀", '1' to "₁", '2' to "₂", '3' to "₃", '4' to "₄", '5' to "₅", '6' to "₆", '7' to "₇",
        '8' to "₈", '9' to "₉", '+' to "₊", '-' to "₋", '−' to "₋", '=' to "₌", '(' to "₍", ')' to "₎",
        'a' to "ₐ", 'e' to "ₑ", 'h' to "ₕ", 'i' to "ᵢ", 'j' to "ⱼ", 'k' to "ₖ", 'l' to "ₗ", 'm' to "ₘ",
        'n' to "ₙ", 'o' to "ₒ", 'p' to "ₚ", 'r' to "ᵣ", 's' to "ₛ", 't' to "ₜ", 'u' to "ᵤ", 'v' to "ᵥ",
        'x' to "ₓ", 'β' to "ᵦ", 'γ' to "ᵧ", 'ρ' to "ᵨ", 'φ' to "ᵩ", 'χ' to "ᵪ",
    )

    private val DOUBLE_STRUCK = mapOf(
        'A' to "𝔸", 'B' to "𝔹", 'C' to "ℂ", 'D' to "𝔻", 'E' to "𝔼", 'F' to "𝔽", 'G' to "𝔾", 'H' to "ℍ",
        'I' to "𝕀", 'J' to "𝕁", 'K' to "𝕂", 'L' to "𝕃", 'M' to "𝕄", 'N' to "ℕ", 'O' to "𝕆", 'P' to "ℙ",
        'Q' to "ℚ", 'R' to "ℝ", 'S' to "𝕊", 'T' to "𝕋", 'U' to "𝕌", 'V' to "𝕍", 'W' to "𝕎", 'X' to "𝕏",
        'Y' to "𝕐", 'Z' to "ℤ",
    )

    private val SCRIPT = mapOf(
        'A' to "𝒜", 'B' to "ℬ", 'C' to "𝒞", 'D' to "𝒟", 'E' to "ℰ", 'F' to "ℱ", 'G' to "𝒢", 'H' to "ℋ",
        'I' to "ℐ", 'J' to "𝒥", 'K' to "𝒦", 'L' to "ℒ", 'M' to "ℳ", 'N' to "𝒩", 'O' to "𝒪", 'P' to "𝒫",
        'Q' to "𝒬", 'R' to "ℛ", 'S' to "𝒮", 'T' to "𝒯", 'U' to "𝒰", 'V' to "𝒱", 'W' to "𝒲", 'X' to "𝒳",
        'Y' to "𝒴", 'Z' to "𝒵",
    )

    /**
     * Commands that stand for one character. Greek, operators, relations,
     * arrows, set theory, logic: what a model writing about code and maths
     * reaches for.
     */
    private val SYMBOLS = mapOf(
        // Greek, lower case
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ",
        "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "omicron" to "ο", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ",
        "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "varphi" to "ϕ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        // Greek, upper case
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
        "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        // Big operators
        "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "iint" to "∬",
        "iiint" to "∭", "oint" to "∮", "bigcup" to "⋃", "bigcap" to "⋂", "bigoplus" to "⨁",
        "bigotimes" to "⨂", "bigvee" to "⋁", "bigwedge" to "⋀",
        // Arithmetic
        "pm" to "±", "mp" to "∓", "times" to "×", "div" to "÷", "cdot" to "·", "ast" to "∗",
        "star" to "⋆", "circ" to "∘", "bullet" to "∙", "oplus" to "⊕", "ominus" to "⊖",
        "otimes" to "⊗", "oslash" to "⊘", "odot" to "⊙",
        // Relations
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "ll" to "≪", "gg" to "≫", "approx" to "≈", "sim" to "∼", "simeq" to "≃", "cong" to "≅",
        "equiv" to "≡", "propto" to "∝", "doteq" to "≐", "asymp" to "≍",
        "prec" to "≺", "succ" to "≻", "preceq" to "⪯", "succeq" to "⪰",
        // Sets and logic
        "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "supset" to "⊃",
        "subseteq" to "⊆", "supseteq" to "⊇", "subsetneq" to "⊊", "cup" to "∪", "cap" to "∩",
        "setminus" to "∖", "emptyset" to "∅", "varnothing" to "∅",
        "forall" to "∀", "exists" to "∃", "nexists" to "∄", "neg" to "¬", "lnot" to "¬",
        "land" to "∧", "wedge" to "∧", "lor" to "∨", "vee" to "∨",
        "top" to "⊤", "bot" to "⊥", "vdash" to "⊢", "models" to "⊨", "therefore" to "∴",
        "because" to "∵",
        // Arrows
        "to" to "→", "rightarrow" to "→", "longrightarrow" to "⟶", "Rightarrow" to "⇒",
        "implies" to "⟹", "leftarrow" to "←", "longleftarrow" to "⟵", "Leftarrow" to "⇐",
        "impliedby" to "⟸", "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "iff" to "⟺",
        "uparrow" to "↑", "downarrow" to "↓", "updownarrow" to "↕", "mapsto" to "↦",
        "hookrightarrow" to "↪", "rightharpoonup" to "⇀", "nearrow" to "↗", "searrow" to "↘",
        // Analysis and geometry
        "partial" to "∂", "nabla" to "∇", "infty" to "∞", "prime" to "′",
        "dagger" to "†", "ddagger" to "‡", "surd" to "√", "angle" to "∠", "measuredangle" to "∡",
        "perp" to "⊥", "parallel" to "∥", "nparallel" to "∦", "triangle" to "△",
        "square" to "□", "diamond" to "⋄", "aleph" to "ℵ", "hbar" to "ℏ", "ell" to "ℓ",
        "wp" to "℘", "Re" to "ℜ", "Im" to "ℑ", "imath" to "ı", "jmath" to "ȷ",
        // Dots
        "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
        // Brackets written as commands
        "langle" to "⟨", "rangle" to "⟩", "lceil" to "⌈", "rceil" to "⌉",
        "lfloor" to "⌊", "rfloor" to "⌋", "lbrace" to "{", "rbrace" to "}",
        "lbrack" to "[", "rbrack" to "]", "vert" to "|", "Vert" to "‖", "mid" to "∣",
        "backslash" to "\\", "textbackslash" to "\\",
        // Misc
        "degree" to "°", "circledR" to "®", "checkmark" to "✓", "percent" to "%",
    )
}
