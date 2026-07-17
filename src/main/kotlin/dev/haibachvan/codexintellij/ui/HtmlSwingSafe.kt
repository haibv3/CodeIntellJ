package dev.haibachvan.codexintellij.ui

import javax.swing.JEditorPane

/**
 * Swing HTMLEditorKit + Bidi is fragile with supplementary-plane chars (emoji).
 * Keep helpers minimal — do not rewrite markdown code fences into custom HTML.
 */
object HtmlSwingSafe {
    /**
     * Drop unpaired surrogates and supplementary-plane code points (emoji)
     * that trigger `BidiUtils.getLevels` IndexOutOfBoundsException.
     */
    fun sanitize(html: String): String {
        if (html.isEmpty()) return html
        val out = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val ch = html[i]
            when {
                Character.isHighSurrogate(ch) -> {
                    if (i + 1 < html.length && Character.isLowSurrogate(html[i + 1])) {
                        i += 2
                    } else {
                        i++
                    }
                }
                Character.isLowSurrogate(ch) -> i++
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Disable bidirectional text analysis before loading HTML. */
    fun disableBidi(pane: JEditorPane) {
        runCatching {
            pane.document.putProperty("i18n", java.lang.Boolean.FALSE)
        }
    }

    /**
     * Force body + mono tags to the same px size. JBHtmlPane's default sheet sizes
     * `code`/`pre` from the editor scheme and often disagrees with the UI label font.
     */
    fun applyUniformContentFont(pane: JEditorPane, sizePx: Int = CodexUiFonts.BODY_PX) {
        runCatching {
            pane.font = CodexUiFonts.body()
            val sheet = javax.swing.text.html.StyleSheet()
            sheet.addRule(
                "body, p, p-implied, div, li, ol, ul, td, th, table, span, a, strong, em, b, i, h1, h2, h3, h4, h5, h6 " +
                    "{ font-size: ${sizePx}px; }",
            )
            sheet.addRule(
                "code, tt, samp, pre, .pre, .icode, .md, .md p, .md li, .md span, .md a, .md strong " +
                    "{ font-size: ${sizePx}px; }",
            )
            sheet.addRule("h1, h2, h3, h4, h5, h6 { font-size: ${sizePx}px; font-weight: bold; }")
            val doc = pane.document as? javax.swing.text.html.HTMLDocument ?: return@runCatching
            doc.styleSheet.addStyleSheet(sheet)
        }
    }
}
