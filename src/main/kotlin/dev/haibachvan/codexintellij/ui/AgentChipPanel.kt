package dev.haibachvan.codexintellij.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Codex-style agent row: orange-star pill with name, then trailing status
 * (e.g. `[✦ Pending review] hoàn tất`).
 */
class AgentChipPanel(
    agentId: String,
    statusLabel: String,
    summary: String?,
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)) {
    private val muted = JBColor(Color(0xA8A8A8), Color(0xA8A8A8))
    private val pillBorder = JBColor(Color(0x5A5A5A), Color(0x5A5A5A))
    private val pillBg = JBColor(Color(0x2A2A2A), Color(0x2A2A2A))
    private val star = JBColor(Color(0xE8A87C), Color(0xE8A87C))

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 12, 6, 12)
        alignmentX = LEFT_ALIGNMENT
        val tip = listOfNotNull(agentId, summary).joinToString(" — ")
        add(pill(agentId, tip))
        add(
            JLabel(statusLabel).apply {
                foreground = muted
                font = CodexUiFonts.secondary()
                toolTipText = tip
            },
        )
    }

    private fun pill(title: String, tip: String?): JPanel {
        val label = JLabel(title).apply {
            foreground = muted
            font = CodexUiFonts.secondary()
            toolTipText = tip
        }
        val starLabel = JLabel("✦").apply {
            foreground = star
            font = CodexUiFonts.secondary()
        }
        return object : JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(4, 10, 4, 12)
                add(starLabel)
                add(label)
                toolTipText = tip
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = height.toFloat()
                g2.color = pillBg
                g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
                g2.color = pillBorder
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
                g2.dispose()
                super.paintComponent(g)
            }

            override fun getMaximumSize(): Dimension = preferredSize
        }
    }

    override fun getMaximumSize(): Dimension =
        Dimension(Integer.MAX_VALUE, preferredSize.height)
}
