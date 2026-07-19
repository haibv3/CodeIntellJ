package dev.haibachvan.codexintellij.ui

import java.awt.Dimension
import javax.swing.JEditorPane
import javax.swing.text.View
import kotlin.math.ceil
import kotlin.math.max

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

    /**
     * Measure HTML content height for a fixed width.
     * [JEditorPane.getPreferredSize] alone often underestimates wrapped HTML (clips / overlaps
     * in BoxLayout); ask the root [View] after forcing a width.
     */
    fun measurePreferredHeight(pane: JEditorPane, width: Int): Int {
        val w = width.coerceAtLeast(1)
        // Drop cached Swing sizes first — after streaming setText(), preferredSize often
        // stays at the previous tall value and leaves a blank band under collapsed thinking.
        pane.preferredSize = null
        pane.minimumSize = null
        // Reset size so BasicTextUI rebuilds root-view metrics before measuring.
        pane.setSize(0, 0)
        pane.setSize(w, Short.MAX_VALUE.toInt())
        val insets = pane.insets
        val contentW = (w - insets.left - insets.right).coerceAtLeast(1).toFloat()
        val fromView = runCatching {
            val root = pane.ui.getRootView(pane)
            root.setSize(contentW, Float.MAX_VALUE)
            ceil(root.getPreferredSpan(View.Y_AXIS).toDouble()).toInt() + insets.top + insets.bottom
        }.getOrDefault(0)
        val fromPref = pane.preferredSize.height
        // Small padding covers border/radius paint that View span sometimes omits.
        // After clearing cached preferredSize above, max() no longer preserves a stale
        // tall height from the previous streaming/expanded document.
        return max(fromView, fromPref).coerceAtLeast(24) + 4
    }

    fun applyMeasuredSize(pane: JEditorPane, width: Int): Dimension {
        val h = measurePreferredHeight(pane, width)
        val size = Dimension(width.coerceAtLeast(1), h)
        // Do not freeze pane.maximumSize — only callers/hosts should lock layout height.
        pane.preferredSize = size
        pane.minimumSize = Dimension(0, h)
        return size
    }
}
