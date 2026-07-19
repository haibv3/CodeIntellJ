package dev.haibachvan.codexintellij.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JEditorPane
import javax.swing.SwingUtilities

class HtmlSwingSafeTest {
    @Test
    fun `sanitize strips emoji surrogate pairs`() {
        val raw = "hello \uD83D\uDCC4 world"
        val clean = HtmlSwingSafe.sanitize(raw)
        assertFalse(clean.contains('\uD83D'))
        assertTrue(clean.contains("hello"))
        assertTrue(clean.contains("world"))
    }

    @Test
    fun `measurePreferredHeight grows with wrapped multiline html`() {
        var shortH = 0
        var tallH = 0
        SwingUtilities.invokeAndWait {
            val pane = JEditorPane("text/html", "").apply {
                isEditable = false
                contentType = "text/html"
            }
            pane.text = "<html><body><div>one line</div></body></html>"
            shortH = HtmlSwingSafe.measurePreferredHeight(pane, 240)
            pane.text = """
                <html><body><div style="width:200px">
                line1<br/>line2<br/>line3<br/>line4<br/>line5<br/>line6
                </div></body></html>
            """.trimIndent()
            tallH = HtmlSwingSafe.measurePreferredHeight(pane, 240)
        }
        assertTrue(tallH > shortH, "expected tall=$tallH > short=$shortH")
        assertTrue(tallH >= 60, "expected multi-line height >= 60, got $tallH")
    }

    @Test
    fun `measurePreferredHeight shrinks after collapsing tall html`() {
        var tallH = 0
        var shortH = 0
        SwingUtilities.invokeAndWait {
            val pane = JEditorPane("text/html", "").apply {
                isEditable = false
                contentType = "text/html"
            }
            pane.text = """
                <html><body><div>
                ${(1..20).joinToString("<br/>") { "Reasoning line $it" }}
                </div></body></html>
            """.trimIndent()
            tallH = HtmlSwingSafe.measurePreferredHeight(pane, 240)
            // ApplyMeasuredSize freezes preferredSize like production hosts do.
            HtmlSwingSafe.applyMeasuredSize(pane, 240)
            pane.text = "<html><body><div>Đã hoạt động trong 46s ›</div></body></html>"
            shortH = HtmlSwingSafe.measurePreferredHeight(pane, 240)
        }
        assertTrue(tallH > 80, "expected tall thinking body, got $tallH")
        assertTrue(
            shortH < tallH / 2,
            "collapse must drop stale tall height (tall=$tallH short=$shortH)",
        )
    }
}
