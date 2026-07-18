package dev.haibachvan.codexintellij.ui

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.KeyStroke

internal class CodexIconButton(
    icon: Icon,
    tooltip: String,
    accessibleName: String,
    onClick: () -> Unit,
) : JButton(icon) {
    init {
        require(tooltip.isNotBlank()) { "Icon button tooltip must not be blank" }
        require(accessibleName.isNotBlank()) { "Icon button accessible name must not be blank" }
        toolTipText = tooltip
        getAccessibleContext().accessibleName = accessibleName
        getAccessibleContext().accessibleDescription = tooltip
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = true
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        margin = JBUI.emptyInsets()
        preferredSize = Dimension(CodexUiMetrics.control28, CodexUiMetrics.control28)
        minimumSize = preferredSize
        addActionListener { onClick() }

        val actionKey = "codex.activate"
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), actionKey)
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("SPACE"), actionKey)
        actionMap.put(
            actionKey,
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent?) {
                    doClick(0)
                }
            },
        )
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        if (!hasFocus() || !isEnabled) return
        val g2 = graphics.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = CodexUiTheme.focusRing
        g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
        val inset = JBUI.scale(2)
        val arc = CodexUiMetrics.radiusControl * 2
        g2.drawRoundRect(inset, inset, width - inset * 2 - 1, height - inset * 2 - 1, arc, arc)
        g2.dispose()
    }
}
