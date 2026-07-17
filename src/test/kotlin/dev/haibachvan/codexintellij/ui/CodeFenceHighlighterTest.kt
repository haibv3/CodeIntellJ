package dev.haibachvan.codexintellij.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeFenceHighlighterTest {
    @Test
    fun `java sample emits font color tags`() {
        val html = CodeFenceHighlighter.toHtml(
            project = null,
            code = """
                public class Demo {
                  int x = 1;
                }
            """.trimIndent(),
            languageHint = "java",
        )
        assertTrue(html.contains("<font color=\"#"), "html=$html")
        assertTrue(html.contains("public") || html.contains("class") || html.contains("Demo"), html)
        assertFalse(html.contains("style=\"color"), html)
    }

    @Test
    fun `unknown language stays escaped plain`() {
        val html = CodeFenceHighlighter.toHtml(null, "a < b", "not-a-real-lang-xyz")
        assertTrue(html.contains("&lt;") || html == "a &lt; b", html)
        assertFalse(html.contains("<font"), html)
    }
}
