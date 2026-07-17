package dev.haibachvan.codexintellij.ui

import javax.swing.JEditorPane
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

/**
 * Swing HTMLEditorKit + Bidi crashes / freezes on custom code-card HTML
 * (tables, nested chrome). Keep fences as native `<pre><code>` (+ optional `<font>`).
 */
class SwingHtmlCodeBlockSafetyTest {
    @Test
    fun `native pre code with java sample sets without throwing`() {
        val code = (1..80).joinToString("\n") { i ->
            "CollapsingToolbarLayout toolbar$i = findViewById(R.id.toolbarLayout$i);"
        }
        val colored = code.lines().joinToString("\n") { line ->
            line.replace(
                "CollapsingToolbarLayout",
                """<font color="#e06c75">CollapsingToolbarLayout</font>""",
            )
        }
        val html = """
            <html><head></head><body>
            <div class="md">
              <pre><code class="language-java">$colored</code></pre>
            </div>
            </body></html>
        """.trimIndent()
        assertDoesNotThrow { setHtml(HtmlSwingSafe.sanitize(html)) }
    }

    @Test
    fun `sanitize strips emoji that break bidi levels`() {
        val withEmoji = "<html><body><p>file \uD83D\uDCC4 name</p></body></html>"
        val clean = HtmlSwingSafe.sanitize(withEmoji)
        assertDoesNotThrow { setHtml(clean) }
    }

    private fun setHtml(html: String) {
        val pane = JEditorPane("text/html", "")
        pane.isEditable = false
        pane.document.putProperty("i18n", java.lang.Boolean.FALSE)
        pane.text = html
    }
}
