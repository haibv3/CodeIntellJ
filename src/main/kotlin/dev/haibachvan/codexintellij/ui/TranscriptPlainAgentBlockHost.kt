package dev.haibachvan.codexintellij.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Lightweight host for agent prose that does not require Markdown rendering. */
internal class TranscriptPlainAgentBlockHost(
    onCopy: (String) -> Unit,
    private val onRemeasured: () -> Unit = {},
) : JPanel(BorderLayout(CodexUiMetrics.space8, 0)) {
    private var itemId: String = ""
    private val textView = PlainTextView().apply {
        isOpaque = false
        font = CodexUiFonts.body()
    }
    private val copyButton = CodexIconButton(
        icon = AllIcons.Actions.Copy,
        tooltip = "Sao chép câu trả lời",
        accessibleName = "Sao chép câu trả lời",
        onClick = { if (itemId.isNotBlank()) onCopy(itemId) },
    )
    private var lockedWidth = 480
    private var lockedHeight = 48
    private var lastLaidOutWidth = -1
    private var remeasureScheduled = false

    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        isFocusable = true
        getAccessibleContext().accessibleName = "Nội dung trả lời của Codex"
        border = JBUI.Borders.empty(CodexUiMetrics.space8, CodexUiMetrics.space12)
        add(textView, BorderLayout.CENTER)
        add(copyButton, BorderLayout.EAST)
    }

    fun updateContent(block: TranscriptBlock.PlainAgentMessage, width: Int) {
        itemId = block.itemId
        getAccessibleContext().accessibleDescription = block.text
        val nextWidth = width.coerceAtLeast(120)
        textView.updateText(block.text, textContentWidth(nextWidth))
        applyMeasuredWidth(nextWidth)
    }

    fun ensureWidth(width: Int) {
        val nextWidth = width.coerceAtLeast(120)
        if (nextWidth != lockedWidth) applyMeasuredWidth(nextWidth)
    }

    fun canRecycle(focusOwner: Component?): Boolean =
        focusOwner == null || (focusOwner !== this && !SwingUtilities.isDescendingFrom(focusOwner, this))

    private fun applyMeasuredWidth(width: Int) {
        val insets = insets
        val textWidth = textContentWidth(width)
        textView.updateWidth(textWidth)
        lockedWidth = width
        lockedHeight = maxOf(textView.preferredSize.height, copyButton.preferredSize.height) +
            insets.top + insets.bottom
        lastLaidOutWidth = width
        revalidate()
    }

    private fun textContentWidth(width: Int): Int {
        val insets = insets
        return (width - insets.left - insets.right - copyButton.preferredSize.width -
            CodexUiMetrics.space8).coerceAtLeast(1)
    }

    override fun getPreferredSize(): Dimension = Dimension(lockedWidth, lockedHeight)

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, lockedHeight)

    override fun getMinimumSize(): Dimension = Dimension(0, lockedHeight)

    override fun doLayout() {
        super.doLayout()
        val currentWidth = width
        if (currentWidth >= 120 && currentWidth != lastLaidOutWidth && !remeasureScheduled) {
            remeasureScheduled = true
            SwingUtilities.invokeLater {
                remeasureScheduled = false
                if (!isShowing && parent == null) return@invokeLater
                val liveWidth = width.takeIf { it >= 120 } ?: return@invokeLater
                if (liveWidth == lastLaidOutWidth) return@invokeLater
                applyMeasuredWidth(liveWidth)
                onRemeasured()
            }
        }
    }

    private class PlainTextView : JComponent() {
        private var text: String = ""
        private var contentWidth: Int = 1
        private var lines: List<String> = listOf("")

        fun updateText(value: String, width: Int) {
            if (text == value && contentWidth == width) return
            text = value
            contentWidth = width.coerceAtLeast(1)
            rewrap()
        }

        fun updateWidth(width: Int) {
            val nextWidth = width.coerceAtLeast(1)
            if (contentWidth == nextWidth) return
            contentWidth = nextWidth
            rewrap()
        }

        private fun rewrap() {
            val metrics = getFontMetrics(font)
            lines = PlainTextLineWrapper.wrap(
                text = text,
                contentWidth = contentWidth,
                textWidth = metrics::stringWidth,
                codePointWidth = metrics::charWidth,
            )
            revalidate()
            repaint()
        }

        override fun getPreferredSize(): Dimension {
            val metrics = getFontMetrics(font)
            return Dimension(contentWidth, (lines.size * metrics.height).coerceAtLeast(metrics.height))
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.font = font
                g2.color = CodexUiTheme.foreground
                val metrics = g2.fontMetrics
                var baseline = metrics.ascent
                lines.forEach { line ->
                    g2.drawString(line, 0, baseline)
                    baseline += metrics.height
                }
            } finally {
                g2.dispose()
            }
        }
    }
}

internal object PlainTextLineWrapper {
    fun wrap(
        text: String,
        contentWidth: Int,
        textWidth: (String) -> Int,
        codePointWidth: (Int) -> Int,
    ): List<String> {
        val tokenizer = java.util.StringTokenizer(text)
        if (!tokenizer.hasMoreTokens()) return listOf("")

        val maxWidth = contentWidth.coerceAtLeast(1)
        val spaceWidth = codePointWidth(' '.code).coerceAtLeast(0)
        val wrapped = ArrayList<String>()
        var line = StringBuilder()
        var lineWidth = 0

        fun flushLine() {
            if (line.isEmpty()) return
            wrapped += line.toString()
            line = StringBuilder()
            lineWidth = 0
        }

        while (tokenizer.hasMoreTokens()) {
            val word = tokenizer.nextToken()
            val wordWidth = textWidth(word)
            if (wordWidth > maxWidth) {
                flushLine()
                val chunk = StringBuilder()
                var chunkWidth = 0
                var offset = 0
                while (offset < word.length) {
                    val codePoint = word.codePointAt(offset)
                    val glyphWidth = codePointWidth(codePoint).coerceAtLeast(0)
                    if (chunk.isNotEmpty() && chunkWidth + glyphWidth > maxWidth) {
                        wrapped += chunk.toString()
                        chunk.setLength(0)
                        chunkWidth = 0
                    }
                    chunk.appendCodePoint(codePoint)
                    chunkWidth += glyphWidth
                    offset += Character.charCount(codePoint)
                }
                line = chunk
                lineWidth = chunkWidth
                continue
            }

            if (line.isEmpty()) {
                line.append(word)
                lineWidth = wordWidth
            } else if (lineWidth + spaceWidth + wordWidth > maxWidth) {
                flushLine()
                line.append(word)
                lineWidth = wordWidth
            } else {
                line.append(' ').append(word)
                lineWidth += spaceWidth + wordWidth
            }
        }
        flushLine()
        return wrapped
    }
}
