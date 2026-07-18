package dev.haibachvan.codexintellij.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Codex-style agent row: accent-star pill with name, then trailing status
 * (e.g. `[✦ Pending review] hoàn tất`).
 *
 * Uses an X-axis [BoxLayout] (not FlowLayout) so preferred height stays stable
 * under a parent Y-axis BoxLayout and cannot collapse into the next HTML block.
 */
class AgentChipPanel(
    agentId: String,
    statusLabel: String,
    summary: String?,
) : JPanel() {
    private var lockedHeight: Int = 0

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(6, 12, 8, 12)
        alignmentX = LEFT_ALIGNMENT
        val tip = listOfNotNull(agentId, summary).joinToString(" — ")
        add(pill(agentId, tip))
        add(Box.createHorizontalStrut(JBUI.scale(8)))
        add(
            JLabel(statusLabel).apply {
                foreground = CodexUiTheme.muted
                font = CodexUiFonts.secondary()
                toolTipText = tip
                alignmentY = CENTER_ALIGNMENT
            },
        )
        add(Box.createHorizontalGlue())
        lockedHeight = super.getPreferredSize().height.coerceAtLeast(JBUI.scale(32))
    }

    private fun pill(title: String, tip: String?): JPanel {
        val label = JLabel(title).apply {
            foreground = CodexUiTheme.muted
            font = CodexUiFonts.secondary()
            toolTipText = tip
        }
        val starLabel = JLabel("✦").apply {
            foreground = CodexUiTheme.agentStar
            font = CodexUiFonts.secondary()
        }
        return object : JPanel() {
            init {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(4, 10, 4, 12)
                alignmentY = CENTER_ALIGNMENT
                add(starLabel)
                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(label)
                toolTipText = tip
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = height.toFloat()
                g2.color = CodexUiTheme.agentPillBg
                g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
                g2.color = CodexUiTheme.agentPillBorder
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
                g2.dispose()
                super.paintComponent(g)
            }

            override fun getMaximumSize(): Dimension = preferredSize
        }
    }

    override fun getPreferredSize(): Dimension {
        val pref = super.getPreferredSize()
        val h = if (lockedHeight > 0) lockedHeight.coerceAtLeast(pref.height) else pref.height
        return Dimension(pref.width.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    override fun getMinimumSize(): Dimension =
        Dimension(0, preferredSize.height)

    override fun getMaximumSize(): Dimension =
        Dimension(Integer.MAX_VALUE, preferredSize.height)
}
