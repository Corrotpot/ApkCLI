package dev.apkcli.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The preview's addresses: found in terminals, typed by the user, and the project's own files. */
class PreviewUrlsTest {

    @Test
    fun `servers are found as they print themselves, each once, newest last`() {
        val output = """
            VITE v6.0.0  ready in 312 ms
              ➜  Local:   http://localhost:5173/
              ➜  Network: use --host to expose
            Serving HTTP on 0.0.0.0 port 8000 (http://0.0.0.0:8000/) ...
             * Running on http://127.0.0.1:5000
              ➜  Local:   http://localhost:5173/
        """.trimIndent()
        assertEquals(
            listOf("http://localhost:8000/", "http://localhost:5000/", "http://localhost:5173/"),
            PreviewUrls.localUrls(output),
        )
    }

    @Test
    fun `what is typed becomes an address`() {
        assertEquals("http://localhost:3000/", PreviewUrls.fromInput("3000"))
        assertEquals("http://localhost:5173/", PreviewUrls.fromInput(" :5173 "))
        assertEquals("http://localhost:8080/app", PreviewUrls.fromInput("localhost:8080/app"))
        assertEquals("http://192.168.1.5:8080", PreviewUrls.fromInput("192.168.1.5:8080"))
        assertEquals("https://developer.android.com/docs", PreviewUrls.fromInput("developer.android.com/docs"))
        assertEquals("https://example.com/x", PreviewUrls.fromInput("https://example.com/x"))
        assertEquals(PreviewUrls.PROJECT_ROOT + "index.html", PreviewUrls.fromInput("index.html"))
        assertEquals(PreviewUrls.PROJECT_ROOT + "docs/index.html", PreviewUrls.fromInput("/docs/index.html"))
        assertNull(PreviewUrls.fromInput("  "))
    }

    @Test
    fun `the address field shows where the page is, briefly`() {
        assertEquals("localhost:5173", PreviewUrls.label("http://localhost:5173/"))
        assertEquals("docs/a.html", PreviewUrls.label(PreviewUrls.PROJECT_ROOT + "docs/a.html"))
        assertEquals("index.html", PreviewUrls.label(PreviewUrls.PROJECT_ROOT))
    }

    @Test
    fun `a project's file is served from the project, and nothing outside it`() {
        val parent = Files.createTempDirectory("preview").toFile()
        try {
            val root = File(parent, "site").apply { mkdirs() }
            File(root, "docs").mkdirs()
            File(root, "index.html").writeText("<p>home")
            File(root, "docs/index.html").writeText("<p>docs")
            File(parent, "site-other").mkdirs()
            File(parent, "site-other/secret.txt").writeText("key")

            assertEquals(File(root, "index.html").canonicalFile, PreviewUrls.projectFile(root, "/"))
            assertEquals(File(root, "docs/index.html").canonicalFile, PreviewUrls.projectFile(root, "/docs/"))
            assertNull(PreviewUrls.projectFile(root, "/../site-other/secret.txt"))
            assertNull(PreviewUrls.projectFile(root, "/missing.js"))
        } finally {
            parent.deleteRecursively()
        }
    }
}
