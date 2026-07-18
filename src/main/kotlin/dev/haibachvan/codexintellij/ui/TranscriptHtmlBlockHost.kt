package dev.haibachvan.codexintellij.ui

import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent

/** Measured HTML transcript component with a locked BoxLayout-compatible height. */
internal class TranscriptHtmlBlockHost(
    private val artifactCache: TranscriptHtmlArtifactCache,
    private val onLink: (String, HyperlinkEvent) -> Unit,
    private val onRemeasured: () -> Unit,
) : JPanel(BorderLayout()) {
    val pane: JBHtmlPane = JBHtmlPane(
        JBHtmlPaneStyleConfiguration.builder()
            .enableInlineCodeBackground(false)
            .enableCodeBlocksBackground(false)
            .largeCodeFontSizeSelectors(emptyList())
            .build(),
        JBHtmlPaneConfiguration(),
    ).apply {
        font = CodexUiFonts.body()
        border = JBUI.Borders.empty(0, 12)
        isEditable = false
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        addHyperlinkListener { event ->
            if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val ref = event.description ?: event.url?.toExternalForm() ?: return@addHyperlinkListener
            onLink(ref, event)
        }
    }
    private var lockedWidth: Int = 480
    private var lockedHeight: Int = 24
    private var lastLaidOutWidth: Int = -1
    private var remasureScheduled: Boolean = false

    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty()
        add(pane, BorderLayout.CENTER)
    }

    fun updateContent(block: TranscriptBlock.Html, width: Int) {
        val html = artifactCache.document(block)
        HtmlSwingSafe.disableBidi(pane)
        try {
            pane.text = html
        } catch (_: Throwable) {
            pane.text = html.replace(Regex("""</?font\b[^>]*>""", RegexOption.IGNORE_CASE), "")
        }
        HtmlSwingSafe.applyUniformContentFont(pane)
        applyMeasuredWidth(width.coerceAtLeast(120))
    }

    fun ensureWidth(width: Int) {
        val nextWidth = width.coerceAtLeast(120)
        if (nextWidth == lockedWidth && lockedHeight > 24) return
        applyMeasuredWidth(nextWidth)
    }

    private fun applyMeasuredWidth(width: Int) {
        val size = HtmlSwingSafe.applyMeasuredSize(pane, width)
        lockedWidth = width
        lockedHeight = size.height
        lastLaidOutWidth = width
        revalidate()
    }

    override fun doLayout() {
        super.doLayout()
        val currentWidth = width
        if (currentWidth >= 120 && currentWidth != lastLaidOutWidth && !remasureScheduled) {
            remasureScheduled = true
            SwingUtilities.invokeLater {
                remasureScheduled = false
                if (!isShowing && parent == null) return@invokeLater
                val liveWidth = width.takeIf { it >= 120 } ?: return@invokeLater
                if (liveWidth == lastLaidOutWidth) return@invokeLater
                applyMeasuredWidth(liveWidth)
                onRemeasured()
            }
        }
    }

    override fun getPreferredSize(): Dimension = Dimension(lockedWidth, lockedHeight)

    override fun getMaximumSize(): Dimension = Dimension(Integer.MAX_VALUE, lockedHeight)

    override fun getMinimumSize(): Dimension = Dimension(0, lockedHeight)

    override fun paintChildren(g: java.awt.Graphics) {
        val clip = g.create(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
        try {
            super.paintChildren(clip)
        } finally {
            clip.dispose()
        }
    }

    fun dispose() {
        pane.dispose()
    }
}

internal class TranscriptHtmlArtifactCache(private val capacity: Int = 512) {
    data class Key(val id: String, val revision: TranscriptBlockRevision)

    private val documents = object : LinkedHashMap<Key, String>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>?): Boolean =
            size > capacity
    }

    init {
        require(capacity > 0)
    }

    fun document(block: TranscriptBlock.Html): String = documents.getOrPut(Key(block.id, block.revision)) {
        HtmlSwingSafe.sanitize(TranscriptRenderer.wrapDocument(block.fragment))
    }

    val size: Int get() = documents.size
}
