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
}
