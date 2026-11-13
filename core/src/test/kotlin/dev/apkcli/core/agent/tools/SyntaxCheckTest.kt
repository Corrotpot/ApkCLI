package dev.apkcli.core.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether a file an edit wrote still parses, said in the edit's result: [SyntaxCheck]. */
class SyntaxCheckTest {

    @Test
    fun `JSON with comments and trailing commas parses, as tsconfig json has them`() {
        assertNull(SyntaxCheck.json("{\n  // what to compile to\n  \"target\": \"es2022\",\n  \"lib\": [\"dom\",],\n}\n"))
    }

    @Test
    fun `broken JSON is named with its line`() {
        val problem = checkNotNull(SyntaxCheck.json("{\n  \"a\": 1,\n  \"b\": ]\n}\n"))
        assertTrue(problem, problem.startsWith("line 3: "))
    }

    @Test
    fun `XML is checked for being well formed, with the line, and fetches no DTD`() {
        assertNull(SyntaxCheck.xml("<?xml version=\"1.0\"?>\n<!DOCTYPE x SYSTEM \"http://example.invalid/x.dtd\">\n<x a=\"1\"/>\n"))
        val problem = checkNotNull(SyntaxCheck.xml("<LinearLayout>\n  <TextView>\n</LinearLayout>\n"))
        assertTrue(problem, problem.startsWith("line 3: "))
    }

    @Test
    fun `Node's report gives the line and why`() {
        val stderr = "/p/app.js:3\nfoo(;\n    ^\n\nSyntaxError: Unexpected token ';'\n" +
            "    at wrapSafe (node:internal/modules/cjs/loader:1469:18)\n\nNode.js v22.11.0\n"
        assertEquals("line 3: Unexpected token ';'", SyntaxCheck.nodeProblem(stderr))
    }

    @Test
    fun `a file read as the other module kind is not a syntax error`() {
        val stderr = "/p/a.js:1\nimport x from 'x'\n^^^^^^\n\nSyntaxError: Cannot use import statement outside a module\n"
        assertNull(SyntaxCheck.nodeProblem(stderr))
    }
}
